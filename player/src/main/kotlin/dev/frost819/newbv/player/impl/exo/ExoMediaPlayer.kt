package dev.frost819.newbv.player.impl.exo

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable
import dev.frost819.newbv.player.AbstractVideoPlayer
import dev.frost819.newbv.player.OkHttpUtil
import dev.frost819.newbv.player.VideoPlayerOptions
import dev.frost819.newbv.player.download.DownloadMemoryReader
import dev.frost819.newbv.player.download.DownloadMonitor
import dev.frost819.newbv.player.download.DownloadSnapshot
import dev.frost819.newbv.player.download.ParallelDownloadSession
import dev.frost819.newbv.player.download.PlaybackMemorySampler
import dev.frost819.newbv.player.download.VodPlaybackSource
import dev.frost819.newbv.player.formatMinSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/**
 * 基于 Media3 (ExoPlayer) 的播放器实现。
 *
 * 支持：
 * - DASH 视频流（视频+音频分离，使用 [MergingMediaSource] 合并）
 * - HLS / FLV 直播流（使用 [ProgressiveMediaSource]）
 * - 软件解码 / 硬件解码切换
 * - FFmpeg 音频渲染器（需配合 ffmpegDecoder 库）
 *
 * @param context Android Context
 * @param options 播放器配置
 */
@OptIn(UnstableApi::class)
class ExoMediaPlayer(
    private val context: Context,
    private val options: VideoPlayerOptions,
    private val traceStore: dev.frost819.newbv.player.download.DownloadTraceStore? = null,
) : AbstractVideoPlayer(),
    Player.Listener {
    companion object {
        private const val BEHIND_LIVE_WINDOW_RECOVER_WINDOW_MS = 15_000L
        private const val BEHIND_LIVE_WINDOW_RECOVER_MIN_INTERVAL_MS = 1_200L
        private const val BEHIND_LIVE_WINDOW_MAX_RECOVERS = 2
    }

    /** ExoPlayer 实例，在 [initPlayer] 中创建 */
    var mPlayer: ExoPlayer? = null
        private set

    /** 当前 MediaSource，在 [playUrl] 中创建 */
    protected var mMediaSource: MediaSource? = null

    /** 当前播放的流协议（"FLV" / "HLS" / "DASH" / "Unknown"），在 [playUrl] 中赋值 */
    var streamProtocol: String = "Unknown"
        private set

    // --- BEHIND_LIVE_WINDOW 恢复状态（参考 blbl tryRecoverBehindLiveWindow） ---

    private var behindLiveWindowWindowStartAtMs = 0L
    private var behindLiveWindowRecoverCount = 0
    private var behindLiveWindowLastRecoverAtMs = 0L

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _downloadSnapshot = MutableStateFlow(DownloadSnapshot())

    /** Stable flow across source, representation, and downloader session changes. */
    override val downloadSnapshot = _downloadSnapshot.asStateFlow()

    private val measuredLoadControl = MeasuredLoadControl()
    private val memoryTraceSession =
        "player-" +
            java.util.UUID
                .randomUUID()
                .toString()
    private var memorySampler: PlaybackMemorySampler? = null

    @Volatile private var memoryPlaybackFields: Map<String, Any?> = emptyMap()

    @Volatile private var memorySurfaceSize: Pair<Int, Int>? = null

    @Volatile private var videoDecoderName: String? = null

    @Volatile private var audioDecoderName: String? = null
    private var playbackSampleHandler: Handler? = null
    private val playbackSampleTick =
        object : Runnable {
            override fun run() {
                if (mPlayer == null) return
                sampleDownloadPlayback()
                playbackSampleHandler?.postDelayed(this, 250)
            }
        }
    private var downloadSession: ParallelDownloadSession? = null
    private var downloadMonitor: DownloadMonitor? = null
    private var downloadSnapshotJob: Job? = null
    private var vodSource: VodPlaybackSource? = null
    private var requestHeaders =
        options.parallelDownload.requestHeaders +
            buildMap {
                options.userAgent?.let { put("User-Agent", it) }
                options.referer?.let { put("Referer", it) }
            }
    private val httpClient = OkHttpUtil.generateCustomSslOkHttpClient(context)
    private val httpDataSourceFactory =
        OkHttpDataSource.Factory(httpClient).apply {
            options.userAgent?.let { setUserAgent(it) }
            setDefaultRequestProperties(requestHeaders)
        }

    private val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    init {
        initPlayer()
    }

    override fun initPlayer() {
        val renderersFactory =
            DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(
                    when (options.enableFfmpegAudioRenderer) {
                        true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    },
                )
                if (options.enableSoftwareVideoDecoder) {
                    // 强制软件解码：只选择 OMX.google.* / c2.android.* 开头的解码器
                    setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                        val allDecoders =
                            MediaCodecUtil.getDecoderInfos(
                                mimeType,
                                requiresSecureDecoder,
                                requiresTunnelingDecoder,
                            )
                        val softwareDecoders =
                            allDecoders.filter {
                                it.name.startsWith("OMX.google.") || it.name.startsWith("c2.android.")
                            }
                        // 兜底回退到硬解
                        softwareDecoders.ifEmpty { allDecoders }
                    }
                } else {
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                }
            }
        mPlayer =
            ExoPlayer
                .Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(measuredLoadControl)
                .setSeekForwardIncrementMs(1000 * 10)
                .setSeekBackIncrementMs(1000 * 5)
                .build()

        mPlayer?.addListener(this)
        mPlayer?.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onSurfaceSizeChanged(
                    eventTime: AnalyticsListener.EventTime,
                    width: Int,
                    height: Int,
                ) {
                    memorySurfaceSize = width to height
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    videoDecoderName = decoderName
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    audioDecoderName = decoderName
                }

                override fun onVideoDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    videoDecoderName =
                        null
                }

                override fun onAudioDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    audioDecoderName =
                        null
                }
            },
        )
        memorySampler?.close()
        if (traceStore != null) {
            memorySampler =
                PlaybackMemorySampler(
                    context.applicationContext,
                    downloadScope,
                    enabled = { traceStore.enabled },
                    record = { type, fields ->
                        traceStore.record(
                            memoryTraceSession,
                            type,
                            fields +
                                mapOf(
                                    "media3" to measuredLoadControl.memoryFields(),
                                    "playback" to memoryPlaybackFields,
                                    "videoDecoder" to videoDecoderName,
                                    "audioDecoder" to audioDecoderName,
                                ),
                        )
                    },
                )
        }
    }

    override fun setHeader(headers: Map<String, String>) {
        requestHeaders = requestHeaders + headers
        httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
    }

    override fun playUrl(
        videoUrl: String?,
        audioUrl: String?,
    ) {
        mPlayer?.stop()
        closeDownloadSession()
        vodSource = null
        streamProtocol = resolveStreamProtocol(videoUrl, audioUrl)
        val videoMediaSource = videoUrl?.let { createMediaSource(it) }
        val audioMediaSource = audioUrl?.let { createMediaSource(it) }

        val mediaSources = listOfNotNull(videoMediaSource, audioMediaSource)
        mMediaSource =
            if (mediaSources.size > 1) {
                @Suppress("SpreadOperator")
                MergingMediaSource(*mediaSources.toTypedArray())
            } else {
                mediaSources.firstOrNull()
            }
    }

    override fun playSource(source: VodPlaybackSource) {
        if (!options.parallelDownload.enabled) {
            super.playSource(source)
            return
        }
        mPlayer?.stop()
        closeDownloadSession()
        vodSource = source
        streamProtocol = "DASH"
        val config = options.parallelDownload.copy(requestHeaders = requestHeaders)
        val monitor = DownloadMonitor(config, traceStore = traceStore)
        val memoryReader = DownloadMemoryReader(context, measuredLoadControl::allocatedBytes)
        val session = ParallelDownloadSession(httpClient, config, source, monitor, memoryReader::sample)
        downloadMonitor = monitor
        downloadSession = session
        downloadSnapshotJob =
            downloadScope.launch {
                monitor.snapshots.collect { _downloadSnapshot.value = it }
            }
        val sources =
            listOfNotNull(source.video, source.audio).mapNotNull { track ->
                track.urls.firstOrNull()?.let { url ->
                    DefaultMediaSourceFactory(session.factory(track, dataSourceFactory))
                        .createMediaSource(MediaItem.fromUri(url))
                }
            }
        mMediaSource =
            if (sources.size > 1) {
                @Suppress("SpreadOperator")
                MergingMediaSource(*sources.toTypedArray())
            } else {
                sources.firstOrNull()
            }
    }

    private var lastBufferingSampleMs = 0L

    private fun sampleDownloadPlayback() {
        val player = mPlayer ?: return
        if (traceStore?.enabled == true) {
            val format = player.videoFormat
            memoryPlaybackFields =
                mapOf(
                    "sampleElapsedMs" to android.os.SystemClock.elapsedRealtime(),
                    "positionMs" to player.currentPosition,
                    "bufferedDurationMs" to player.totalBufferedDuration,
                    "state" to player.playbackState,
                    "playing" to player.isPlaying,
                    "playWhenReady" to player.playWhenReady,
                    "loading" to player.isLoading,
                    "videoWidth" to format?.width,
                    "videoHeight" to format?.height,
                    "videoMime" to format?.sampleMimeType,
                    "videoBitrate" to format?.bitrate,
                    "videoFrameRate" to format?.frameRate,
                    "surfaceWidth" to memorySurfaceSize?.first,
                    "surfaceHeight" to memorySurfaceSize?.second,
                    "colorTransfer" to format?.colorInfo?.colorTransfer,
                    "softwareVideoRequested" to options.enableSoftwareVideoDecoder,
                    "parallelEnabled" to options.parallelDownload.enabled,
                    "downloadTraceSession" to downloadMonitor?.traceSessionId,
                )
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (traceStore?.enabled == true && now - lastBufferingSampleMs >= 1000) {
            lastBufferingSampleMs = now
            traceStore.record(
                downloadMonitor?.traceSessionId ?: "player",
                "player_sample",
                memoryPlaybackFields + ("media3" to measuredLoadControl.memoryFields()),
            )
        }
        downloadMonitor?.updatePlayback(
            positionMs = player.currentPosition,
            speed = player.playbackParameters.speed,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            bufferedPositionMs = player.bufferedPosition,
            isReady = player.playbackState == Player.STATE_READY,
            retainedBufferValid =
                player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING,
        )
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        // Media3 invokes this on its application looper, including seeks, pauses, and speed changes.
        sampleDownloadPlayback()
    }

    private fun closeDownloadSession() {
        playbackSampleHandler?.removeCallbacks(playbackSampleTick)
        playbackSampleHandler = null
        downloadSnapshotJob?.cancel()
        downloadSnapshotJob = null
        downloadSession?.close()
        downloadSession = null
        downloadMonitor?.close()
        downloadMonitor = null
        _downloadSnapshot.value = DownloadSnapshot()
    }

    /**
     * 从 URL 推断流协议类型。
     *
     * - 双 URL（video + audio）→ DASH
     * - `.m3u8` → HLS
     * - 其余（`.flv` / progressive）→ FLV
     */
    private fun resolveStreamProtocol(
        videoUrl: String?,
        audioUrl: String?,
    ): String {
        if (videoUrl != null && audioUrl != null) return "DASH"
        val url = videoUrl ?: return "Unknown"
        val isHls =
            url
                .substringBefore('?')
                .trim()
                .lowercase(Locale.US)
                .endsWith(".m3u8")
        return if (isHls) "HLS" else "FLV"
    }

    /**
     * 根据 URL 创建对应的 [MediaSource]。
     *
     * B 站直播 HLS 流的 playlist 包含 `#EXT-X-START` 标签，
     * 会导致 ExoPlayer 错误计算直播窗口位置，触发 `ERROR_CODE_BEHIND_LIVE_WINDOW`。
     * 因此对 `.m3u8` URL 使用 [HlsMediaSource] 并通过自定义 [HlsPlaylistParserFactory]
     * 剥离该标签；其余格式（DASH / FLV / progressive）走 [DefaultMediaSourceFactory]。
     */
    private fun createMediaSource(url: String): MediaSource {
        val isHls =
            url
                .substringBefore('?')
                .trim()
                .lowercase(Locale.US)
                .endsWith(".m3u8")
        return if (isHls) {
            HlsMediaSource
                .Factory(dataSourceFactory)
                .setPlaylistParserFactory(ExtXStartStrippingHlsPlaylistParserFactory())
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
    }

    override fun prepare() {
        // A stopped session cannot be reused: recreate its request budget and workers.
        if (downloadSession == null) vodSource?.let { playSource(it) }
        mMediaSource?.let {
            mPlayer?.setMediaSource(it)
            mPlayer?.prepare()
            if (downloadMonitor != null || traceStore != null) {
                playbackSampleHandler?.removeCallbacks(playbackSampleTick)
                mPlayer?.let { player ->
                    playbackSampleHandler = Handler(player.applicationLooper).also { it.post(playbackSampleTick) }
                }
            }
        }
    }

    override fun start() {
        mPlayer?.play()
    }

    override fun pause() {
        mPlayer?.pause()
    }

    override fun stop() {
        mPlayer?.stop()
        closeDownloadSession()
    }

    override fun reset() {
        stop()
        mPlayer?.clearMediaItems()
        mMediaSource = null
        vodSource = null
    }

    override val isPlaying: Boolean
        get() = mPlayer?.isPlaying == true

    override fun seekTo(time: Long) {
        downloadMonitor?.clearPlayerBuffer()
        downloadSession?.prepareSeek(time)
        mPlayer?.seekTo(time)
        sampleDownloadPlayback()
    }

    override fun release() {
        memorySampler?.close()
        memorySampler = null
        closeDownloadSession()
        downloadScope.cancel()
        vodSource = null
        mMediaSource = null
        mPlayer?.release()
        mPlayer = null
    }

    override val currentPosition: Long
        get() = mPlayer?.currentPosition ?: 0
    override val duration: Long
        get() = mPlayer?.duration ?: 0
    override val bufferedPercentage: Int
        get() = mPlayer?.bufferedPercentage ?: 0

    override fun setOptions() {
        mPlayer?.playWhenReady = true
    }

    override var speed: Float
        get() = mPlayer?.playbackParameters?.speed ?: 1f
        set(value) {
            mPlayer?.setPlaybackSpeed(value)
        }

    override val tcpSpeed: Long
        get() = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> {}
            Player.STATE_BUFFERING -> mPlayerEventListener?.onBuffering()
            Player.STATE_READY -> mPlayerEventListener?.onReady()
            Player.STATE_ENDED -> mPlayerEventListener?.onEnd()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            mPlayerEventListener?.onPlay()
        } else {
            mPlayerEventListener?.onPause()
        }
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        mPlayerEventListener?.onSeekBack(seekBackIncrementMs)
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        mPlayerEventListener?.onSeekForward(seekForwardIncrementMs)
    }

    override val debugInfo: String
        get() {
            return """
                player: ${androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY}
                time: ${currentPosition.formatMinSec()} / ${duration.formatMinSec()}
                speed: ${speed}x
                buffered: $bufferedPercentage%
                resolution: ${mPlayer?.videoSize?.width} x ${mPlayer?.videoSize?.height}
                audio: ${mPlayer?.audioFormat?.bitrate ?: 0} kbps
                video codec: ${mPlayer?.videoFormat?.sampleMimeType ?: "null"}
                audio codec: ${mPlayer?.audioFormat?.sampleMimeType ?: "null"} ($audioRendererName)
                """.trimIndent()
        }

    /** 当前活跃的音频渲染器名称（如 "OMX.google.aac.decoder"）。 */
    val audioRendererName: String
        get() = findAudioRendererName()

    private fun findAudioRendererName(): String {
        val rendererCount = mPlayer?.rendererCount ?: return "UnknownRenderer"
        for (i in 0 until rendererCount) {
            val renderer = mPlayer!!.getRenderer(i)
            if (renderer.trackType == C.TRACK_TYPE_AUDIO && renderer.state == Renderer.STATE_STARTED) {
                return renderer.name
            }
        }
        return "UnknownRenderer"
    }

    override val videoWidth: Int
        get() = mPlayer?.videoSize?.width ?: 0
    override val videoHeight: Int
        get() = mPlayer?.videoSize?.height ?: 0

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            tryRecoverBehindLiveWindow(error)
            return
        }
        mPlayerEventListener?.onError(error)
    }

    /**
     * BEHIND_LIVE_WINDOW 两级恢复 + 频率限制（参考 blbl `tryRecoverBehindLiveWindow`）。
     *
     * - 第 1 次：`seekToDefaultPosition()` + `prepare()`（轻量恢复）
     * - 第 2 次：`stop()` + 重新 `setMediaSource()` + `prepare()`（重建 MediaSource）
     * - 超过 2 次（15 秒窗口内）：上报 error，不再自行恢复
     *
     * 频率限制：15 秒滑动窗口内最多 2 次，最小间隔 1.2 秒。
     */
    private fun tryRecoverBehindLiveWindow(error: PlaybackException) {
        val player =
            mPlayer ?: run {
                mPlayerEventListener?.onError(error)
                return
            }
        val nowMs = android.os.SystemClock.elapsedRealtime()

        // 重置窗口（超过 15 秒或首次）
        if (behindLiveWindowWindowStartAtMs <= 0L ||
            nowMs - behindLiveWindowWindowStartAtMs > BEHIND_LIVE_WINDOW_RECOVER_WINDOW_MS
        ) {
            behindLiveWindowWindowStartAtMs = nowMs
            behindLiveWindowRecoverCount = 0
        }

        // 最小间隔检查（防紧密循环）
        if (nowMs - behindLiveWindowLastRecoverAtMs < BEHIND_LIVE_WINDOW_RECOVER_MIN_INTERVAL_MS) {
            return
        }

        // 超过最大恢复次数，上报 error
        if (behindLiveWindowRecoverCount >= BEHIND_LIVE_WINDOW_MAX_RECOVERS) {
            mPlayerEventListener?.onError(error)
            return
        }

        behindLiveWindowRecoverCount++
        behindLiveWindowLastRecoverAtMs = nowMs

        if (behindLiveWindowRecoverCount == 1) {
            // 第 1 次：轻量恢复 — seekToDefaultPosition + prepare
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
        } else {
            // 第 2 次：重建 MediaSource — stop + setMediaSource + prepare
            mMediaSource?.let { source ->
                player.stop()
                player.setMediaSource(source)
                player.prepare()
                player.playWhenReady = true
            } ?: run {
                // 无 MediaSource 可重建，直接上报
                mPlayerEventListener?.onError(error)
            }
        }
    }
}

/**
 * 剥离 `#EXT-X-START` 标签的 HLS Playlist 解析器工厂。
 *
 * B 站直播 HLS playlist 包含 `#EXT-X-START` 标签，ExoPlayer 解析后
 * 会错误计算直播窗口的起始位置，导致播放几秒后触发
 * [PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW]。
 * 此工厂创建的解析器会在交给 ExoPlayer 默认解析器之前将该标签过滤掉。
 */
@OptIn(UnstableApi::class)
private class ExtXStartStrippingHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        ExtXStartStrippingParser(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        ExtXStartStrippingParser(
            delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
        )
}

/**
 * 解析 HLS playlist 时剥离 `#EXT-X-START` 行。
 */
@OptIn(UnstableApi::class)
private class ExtXStartStrippingParser(
    private val delegate: ParsingLoadable.Parser<HlsPlaylist>,
) : ParsingLoadable.Parser<HlsPlaylist> {
    override fun parse(
        uri: android.net.Uri,
        inputStream: InputStream,
    ): HlsPlaylist {
        val bytes = inputStream.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        if (!text.contains("#EXT-X-START", ignoreCase = true)) {
            return delegate.parse(uri, ByteArrayInputStream(bytes))
        }
        val filtered =
            text
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#EXT-X-START", ignoreCase = true) }
                .joinToString("\n")
        return delegate.parse(uri, ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8)))
    }
}

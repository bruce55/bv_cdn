package dev.frost819.newbv.player.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Session-owned, bounded telemetry; updates are coalesced to at most ten per second. */
class DownloadMonitor(
    private val config: ParallelDownloadConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val traceStore: DownloadTraceStore? = null,
) : AutoCloseable {
    private val traceSession =
        java.util.UUID
            .randomUUID()
            .toString()

    /** Correlates player memory samples with this downloader session in retained logs. */
    val traceSessionId: String get() = traceSession

    @Volatile
    private var traceSampler: (() -> Unit)? = null

    init {
        if (traceStore != null) {
            scope.launch {
                while (isActive) {
                    delay(1000)
                    // Never invoke transport while holding the monitor lock: readers call back into us.
                    if (traceEnabled) traceSampler?.invoke()
                }
            }
        }
    }

    /** Installs session telemetry sampled outside this monitor's lock, including when readers are idle. */
    fun setTraceSampler(sampler: () -> Unit) {
        traceSampler = sampler
    }

    private var lastPlaybackTrace = 0L
    private var lastPlaybackState = ""

    /** Whether remote recording is currently enabled, independently of the visual overlay. */
    val traceEnabled: Boolean get() = traceStore?.enabled == true

    /** Records primitives only; request addresses must be reduced to host names by the caller. */
    fun trace(
        type: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (traceEnabled) traceStore?.record(traceSession, type, mapOf(*fields))
    }

    private val mutableSnapshots = MutableStateFlow(DownloadSnapshot(maxRequests = config.maxRequests.coerceIn(2, 64)))

    /** Presentation state; network workers never invoke Compose directly. */
    val snapshots: StateFlow<DownloadSnapshot> = mutableSnapshots.asStateFlow()
    private val routes = linkedMapOf<String, DownloadRoute>()
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val blocks = linkedMapOf<Long, DownloadBlock>()
    private val prefixes = mutableMapOf<DownloadTrack, ByteArrayOutputStream>()
    private val indexes = mutableMapOf<DownloadTrack, List<IndexedSegment>>()
    private val schedulingHorizons = mutableMapOf<DownloadTrack, SchedulingHorizon>()
    private var readEpoch = 0L
    private var playback: PlaybackDeadline? = null
    private val scheduling = PlaybackSchedulingTracker()
    private val workTelemetry = DownloadWorkTelemetry(config.maxRequests.coerceIn(2, 64))
    private var waitReason: String? = null
    private val chartStart = System.nanoTime()
    private var chartAt = chartStart
    private val trafficBytes = mutableMapOf<String, Long>()
    private var downloadedBytes = 0L
    private var deliveredToPlayerBytes = 0L
    private var confirmedTransportDiscardedBytes = 0L
    private val consumedRanges = mutableMapOf<DownloadTrack, DownloadByteLedger>()
    private val history = java.util.ArrayDeque<DownloadSample>()
    private var bufferSeconds = 0.0
    private var playerBuffer: LongRange? = null

    private class DeliveredResidency(
        val epoch: Long,
        val bytes: DownloadByteLedger = DownloadByteLedger(),
    )

    private val deliveredResidency = mutableMapOf<DownloadTrack, DeliveredResidency>()

    private data class AvailabilitySource(
        val tracks: Set<DownloadTrack>,
        val sample: () -> Map<DownloadTrack, List<LongRange>>,
    )

    @Volatile private var availabilitySource: AvailabilitySource? = null

    @Volatile private var exactTransferSampler: (() -> ExactTransferLedger.Snapshot)? = null

    /** Independent provenance counters; the sampler never calls scheduling or storage code. */
    internal fun setExactTransferSampler(sampler: () -> ExactTransferLedger.Snapshot) {
        exactTransferSampler = sampler
    }

    @Volatile private var inFlightSampler: (() -> Map<DownloadTrack, List<LongRange>>)? = null
    private var explorationCount = 0
    private var rescueCount = 0
    private var failureCount = 0
    private var sequence = 0L
    private var active = 0
    private var publishing = false
    private var closed = false

    /** Supplies current validated local byte ranges; sampling never runs under this monitor's lock. */
    @Synchronized
    fun setAvailabilitySampler(
        tracks: Set<DownloadTrack>,
        sampler: () -> Map<DownloadTrack, List<LongRange>>,
    ) {
        if (closed) return
        availabilitySource = AvailabilitySource(tracks.toSet(), sampler)
        publishSoon()
    }

    /** Supplies currently retained in-flight body ranges; sampled outside the monitor lock. */
    @Synchronized
    fun setInFlightSampler(sampler: () -> Map<DownloadTrack, List<LongRange>>) {
        if (closed) return
        inFlightSampler = sampler
        publishSoon()
    }

    /**
     * Counts unique bytes delivered to Media3; cache rereads never add usage twice. A matching
     * [epoch] also extends this reader's per-track residency estimate, independent of the common
     * Media3 buffer endpoint. Seek/reset invalidates those estimates without clearing usage.
     */
    @Synchronized
    fun consumedBytes(
        track: DownloadTrack,
        start: Long,
        count: Int,
        epoch: Long? = null,
    ) {
        if (closed || start < 0 || count <= 0) return
        deliveredToPlayerBytes = saturatedByteSum(deliveredToPlayerBytes, count.toLong())
        val end = saturatedByteSum(start, count.toLong() - 1)
        consumedRanges.getOrPut(track) { DownloadByteLedger() }.add(start, end)
        deliveredResidency[track]?.takeIf { it.epoch == epoch }?.bytes?.add(start, end)
        publishSoon()
    }

    /** Drops the old Media3 buffer claim before seeking; cache residency remains independent. */
    @Synchronized
    fun clearPlayerBuffer() {
        if (closed) return
        playerBuffer = null
        deliveredResidency.clear()
        publishSoon()
    }

    /** Registers a planned inclusive byte range, returning its session-local identity. */
    @Synchronized
    fun plan(
        track: DownloadTrack,
        start: Long,
        end: Long,
    ): Long {
        val id = ++sequence
        trace("block_plan", "block" to id, "track" to track, "start" to start, "end" to end)
        if (closed || (!config.visualizationEnabled && !config.diagnosticsEnabled)) return id
        while (blocks.size >= 256) {
            val removable =
                blocks.values.firstOrNull { it.state in listOf(DownloadBlockState.Complete, DownloadBlockState.Failed) }
                    ?: blocks.values.firstOrNull() ?: break
            blocks.remove(removable.id)
        }
        blocks[id] = DownloadBlock(id, track, start, end)
        publishSoon()
        return id
    }

    /** Updates one block's lifecycle without changing its byte bounds. */
    @Synchronized
    fun state(
        id: Long,
        state: DownloadBlockState,
    ) {
        if (closed) return
        trace("block_state", "block" to id, "state" to state)
        blocks[id]?.let {
            blocks[id] =
                it.copy(
                    state = state,
                    hadFailure =
                        it.hadFailure || state == DownloadBlockState.Retrying || state == DownloadBlockState.Failed,
                )
        }
        publishSoon()
    }

    /** Keeps exploratory outcomes separate from the winning playback request. */
    @Synchronized
    fun exploration(
        id: Long,
        result: ExplorationState,
    ) {
        if (closed) return
        trace("exploration_state", "block" to id, "state" to result)
        if (result == ExplorationState.Testing) explorationCount++
        blocks[id]?.let { blocks[id] = it.copy(exploration = result) }
        publishSoon()
    }

    /** Marks a launched rescue, retaining that history after completion. */
    @Synchronized
    fun rescue(id: Long) {
        if (closed) return
        trace("block_rescue", "block" to id)
        rescueCount++
        blocks[id]?.let { blocks[id] = it.copy(rescued = true) }
        publishSoon()
    }

    /** Records the candidate's eventual result even when rescue delivered the block earlier. */
    @Synchronized
    fun explorationFinished(
        id: Long,
        succeeded: Boolean,
        cancelled: Boolean,
    ) {
        if (closed || blocks[id]?.exploration != ExplorationState.Testing) return
        exploration(
            id,
            when {
                cancelled -> ExplorationState.Cancelled
                succeeded -> ExplorationState.Succeeded
                else -> ExplorationState.Failed
            },
        )
    }

    /** Records bytes received for the current attempt, capped to the block's length. */
    @Synchronized
    fun progress(
        id: Long,
        receivedBytes: Long,
    ) {
        if (closed) return
        blocks[id]?.let {
            blocks[id] =
                it.copy(
                    receivedBytes = receivedBytes.coerceIn(0, it.endByte - it.startByte + 1),
                )
        }
        publishSoon()
    }

    private var videoProbes = 0
    private var audioProbes = 0
    private var startupProbing = false

    /** Reports validated startup results; in-flight requests do not count toward the target. */
    @Synchronized
    fun startupProbeStatus(
        video: Int,
        audio: Int,
        probing: Boolean,
    ) {
        if (closed) return
        if (videoProbes != video || audioProbes != audio || startupProbing != probing) {
            trace("startup_probes", "videoValid" to video, "audioValid" to audio, "probing" to probing)
        }
        videoProbes = video
        audioProbes = audio
        startupProbing = probing
        publishSoon()
    }

    /** Registers an actual slot occupant without changing legacy request/route counters. */
    @Synchronized
    fun workerStarted(
        attemptId: Long,
        host: String,
        track: DownloadTrack,
        kind: DownloadWorkKind,
    ) {
        if (closed) return
        workTelemetry.started(attemptId, host, track, kind)
        waitReason = null
        publishSoon()
    }

    /** Releases the displayed slot only when its actual HTTP attempt exits. */
    @Synchronized
    fun workerFinished(attemptId: Long) {
        if (closed) return
        workTelemetry.finished(attemptId)
        publishSoon()
    }

    /** Reports the current global admission wait reason; null means no admission wait. */
    @Synchronized
    fun schedulerWait(reason: String?) {
        if (closed) return
        waitReason = reason?.take(80)
        publishSoon()
    }

    /**
     * Attributes only bytes definitively discarded by transport. Useful winners and accepted
     * rescue prefixes must be excluded; work type alone never proves that traffic was wasted.
     */
    @Synchronized
    fun discardedTransfer(
        kind: DownloadOverheadKind,
        bytes: Long,
    ) {
        if (closed) return
        if (bytes > 0) confirmedTransportDiscardedBytes = saturatedByteSum(confirmedTransportDiscardedBytes, bytes)
        workTelemetry.discarded(kind, bytes)
        publishSoon()
    }

    /** Starts seek readiness without treating its buffering as an interruption penalty. */
    @Synchronized
    fun beginSeek(positionMs: Long? = null) {
        if (closed) return
        scheduling.beginSeek()
        clearPlayerBuffer()
        trace("playback_seek", "positionMs" to positionMs, "schedulingState" to scheduling.state)
        publishSoon()
    }

    /** Counts an actual started HTTP attempt, including retries and audio. */
    @Synchronized
    fun requestStarted() {
        if (closed) return
        active++
        publishSoon()
    }

    /** Ends an actual HTTP attempt. */
    @Synchronized
    fun requestFinished() {
        if (closed) return
        active = (active - 1).coerceAtLeast(0)
        publishSoon()
    }

    /** Lists eligible hosts without pretending that discovery is a successful probe. */
    @Synchronized
    fun routeCandidates(hosts: List<String>) {
        if (closed || !config.diagnosticsEnabled) return
        hosts.forEach { host ->
            if (routes.size < 32 && host !in routes) routes[host] = DownloadRoute(host)
        }
        publishSoon()
    }

    /** Marks a real HTTP attempt for a host; no URLs or credentials are retained. */
    @Synchronized
    fun routeStarted(host: String) {
        if (closed || !config.diagnosticsEnabled) return
        if (host !in routes && routes.size >= 32) {
            val removable = routes.values.firstOrNull { it.activeRequests == 0 } ?: return
            routes.remove(removable.host)
            cooldownUntil.remove(removable.host)
        }
        val route = routes[host] ?: DownloadRoute(host)
        routes[host] = route.copy(activeRequests = route.activeRequests + 1, attempts = route.attempts + 1)
        publishSoon()
    }

    /** Counts body bytes on every attempt, including failed and duplicated rescue data. */
    @Synchronized
    fun routeProgress(
        host: String,
        bytes: Int,
    ) {
        if (closed || bytes <= 0) return
        downloadedBytes = saturatedByteSum(downloadedBytes, bytes.toLong())
        publishSoon()
        if (!config.diagnosticsEnabled || host !in routes) return
        trafficBytes[host] = saturatedByteSum(trafficBytes[host] ?: 0, bytes.toLong())
    }

    /** Records a request that needed rescue independently of whether its eventual response is valid. */
    @Synchronized
    fun routeDeadlineMiss(
        host: String,
        confidence: Double = 1.0,
    ) {
        if (closed || !config.diagnosticsEnabled) return
        val route = routes[host] ?: return
        routes[host] = route.copy(deadlineMisses = route.deadlineMisses + 1, schedulingConfidence = confidence)
        publishSoon()
    }

    /** Refreshes a stronger penalty without counting the same assignment as another deadline miss. */
    @Synchronized
    fun routeConfidence(
        host: String,
        confidence: Double,
    ) {
        if (closed || !config.diagnosticsEnabled) return
        routes[host]?.let { routes[host] = it.copy(schedulingConfidence = confidence) }
        publishSoon()
    }

    /** Credits a validated rescue only when it wins delivery to the player. */
    @Synchronized
    fun routeRescueProvided(host: String) {
        if (closed || !config.diagnosticsEnabled) return
        val route = routes[host] ?: return
        routes[host] = route.copy(rescuesProvided = route.rescuesProvided + 1)
        publishSoon()
    }

    /** Completes a host attempt; cancellation never counts as a route success or failure. */
    @Synchronized
    fun routeFinished(
        host: String,
        bytes: Long,
        elapsedMs: Long,
        failure: String?,
        cooldownRemainingMs: Long = 0,
        cancelled: Boolean = false,
        confidence: Double = 1.0,
    ) {
        if (closed || !config.diagnosticsEnabled) return
        val route = routes[host] ?: return
        val succeeded = failure == null && !cancelled
        if (failure != null && !cancelled) failureCount++
        routes[host] =
            route.copy(
                activeRequests = (route.activeRequests - 1).coerceAtLeast(0),
                successes = route.successes + if (succeeded) 1 else 0,
                failures = route.failures + if (failure != null && !cancelled) 1 else 0,
                schedulingConfidence = confidence,
                cancellations = route.cancellations + if (cancelled) 1 else 0,
                lastSampleBytes = if (succeeded) bytes else route.lastSampleBytes,
                lastSampleDurationMs = if (succeeded) elapsedMs else route.lastSampleDurationMs,
                lastFailure = if (cancelled) route.lastFailure else failure,
                bytesPerSecond =
                    if (succeeded) {
                        bytes.coerceAtLeast(
                            0,
                        ) * 1000 / elapsedMs.coerceAtLeast(1)
                    } else {
                        route.bytesPerSecond
                    },
            )
        if (!cancelled) cooldownUntil[host] = System.nanoTime() / 1_000_000 + cooldownRemainingMs.coerceAtLeast(0)
        publishSoon()
    }

    /** Collects only a bounded contiguous file prefix to discover the real media index. */
    @Synchronized
    fun recordBytes(
        track: DownloadTrack,
        start: Long,
        bytes: ByteArray,
    ) {
        if (closed || indexes.containsKey(track)) return
        val prefix = prefixes.getOrPut(track) { ByteArrayOutputStream() }
        val current = prefix.size()
        if (start < 0 || start > current || start >= MAX_PREFIX_BYTES) return
        val skip = (current - start).toInt()
        if (skip >= bytes.size || current >= MAX_PREFIX_BYTES) return
        val count = minOf(bytes.size - skip, MAX_PREFIX_BYTES - current)
        prefix.write(bytes, skip, count)
        val parsed = SidxIndex.parse(prefix.toByteArray())
        if (parsed.isNotEmpty()) {
            trace(
                "media_index",
                "track" to track,
                "segments" to parsed.size,
                "averageSegmentBytes" to parsed.map { it.endByte - it.startByte + 1 }.average(),
            )
            indexes[track] = parsed
            prefixes.remove(track)
            publishSoon()
        }
    }

    /** Receives an application-looper playback sample without reading Media3 on worker threads. */
    @Synchronized
    fun updatePlayback(
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean,
        sampledAtNanos: Long = System.nanoTime(),
        bufferedPositionMs: Long = positionMs,
        isReady: Boolean = isPlaying,
        retainedBufferValid: Boolean = true,
    ) {
        if (closed) return
        val playbackState = "$playWhenReady/$isPlaying/$isBuffering/$isReady"
        if (traceEnabled &&
            (sampledAtNanos - lastPlaybackTrace >= 1_000_000_000L || playbackState != lastPlaybackState)
        ) {
            lastPlaybackTrace = sampledAtNanos
            lastPlaybackState = playbackState
            trace(
                "playback",
                "positionMs" to positionMs,
                "bufferedPositionMs" to bufferedPositionMs,
                "bufferMs" to (bufferedPositionMs - positionMs).coerceAtLeast(0),
                "speed" to speed,
                "playWhenReady" to playWhenReady,
                "playing" to isPlaying,
                "buffering" to isBuffering,
                "ready" to isReady,
                "activeRequests" to active,
                "limit" to config.maxRequests,
                "minimumBlockKiB" to config.minimumBlockKiB,
            )
        }
        playback = PlaybackDeadline(positionMs, speed, playWhenReady, isPlaying, isBuffering, sampledAtNanos)
        scheduling.update(playWhenReady, isPlaying, isBuffering, isReady)
        bufferSeconds = (bufferedPositionMs - positionMs).coerceAtLeast(0) / 1000.0
        playerBuffer =
            if (retainedBufferValid && positionMs >= 0 && bufferedPositionMs > positionMs) {
                positionMs until bufferedPositionMs
            } else {
                null
            }
        if (!retainedBufferValid) deliveredResidency.clear()
        // Use the actual segment start (not interpolated drawing time). Pausing retains
        // the last urgency value, while completed playback blocks no longer have a deadline.
        val now = System.nanoTime()
        blocks.values.toList().forEach { block ->
            val deadline = deadlineNanos(block.track, block.startByte)
            val urgency =
                when {
                    block.state == DownloadBlockState.Complete -> 0f
                    deadline == null -> block.deadlineUrgency
                    else -> (1.0 - (deadline - now).toDouble() / 5_000_000_000.0).coerceIn(0.0, 1.0).toFloat()
                }
            blocks[block.id] = block.copy(deadlineUrgency = urgency)
        }
        publishSoon()
    }

    /** Startup ends when Media3 is ready to play, even if paused, not when a probe finishes. */
    @Synchronized
    fun hasStartedPlayback(): Boolean = scheduling.everReady

    /** Initial load or seek has not become ready yet; pausing does not erase this phase. */
    @Synchronized
    fun isAwaitingReadiness(): Boolean = scheduling.awaitingReadiness

    /** True only while the playback clock is advancing, excluding startup, buffering and pauses. */
    @Synchronized
    fun isPlaybackAdvancing(): Boolean = playback?.isPlaying == true && playback?.playWhenReady == true

    /** True only for buffering after readiness, excluding startup, pending seeks and pauses. */
    @Synchronized
    fun isRebuffering(): Boolean = scheduling.rebuffering

    /** True only after playback explicitly reports a paused state; an unknown clock is not paused. */
    @Synchronized
    fun isPlaybackPaused(): Boolean = playback?.playWhenReady == false

    /**
     * Monotonic deadline for the containing media segment, including a 750 ms safety margin.
     * Segment starts are conservative: byte interpolation is suitable for drawing, not decode deadlines.
     * Returns null while paused or when the index or active playback clock is unavailable.
     */
    @Synchronized
    fun deadlineNanos(
        track: DownloadTrack,
        startByte: Long,
    ): Long? {
        if (closed || startByte < 0) return null
        val segments = indexes[track] ?: return null
        val segment = segments.firstOrNull { it.endByte >= startByte } ?: return null
        return playback?.forSegment(segment.startTimeMs)
    }

    /**
     * Starts a new scheduling epoch anchored at the current read position, replacing the old horizon.
     * Returns a token required by [rangeReady], so stale completions cannot affect priority after a seek.
     */
    @Synchronized
    fun beginRead(
        track: DownloadTrack,
        startByte: Long,
    ): Long {
        if (closed) return -1
        require(startByte >= 0)
        val epoch = ++readEpoch
        schedulingHorizons[track] = SchedulingHorizon(startByte, epoch)
        deliveredResidency[track] = DeliveredResidency(epoch)
        publishSoon()
        return epoch
    }

    /**
     * Advances dispatch's farthest validated end byte, including successfully downloaded future blocks
     * across gaps. Duplicate and overlapping completions never regress or accumulate byte counts.
     * This marker is not contiguous playable buffer and does not change playback deadlines.
     */
    @Synchronized
    fun rangeReady(
        track: DownloadTrack,
        start: Long,
        endExclusive: Long,
        epoch: Long,
    ) {
        if (closed || start < 0 || endExclusive <= start) return
        val horizon = schedulingHorizons[track]?.takeIf { it.epoch == epoch } ?: return
        horizon.endExclusive = maxOf(horizon.endExclusive, endExclusive)
    }

    /**
     * Approximate media-time dispatch target at the farthest validated future block's exclusive end.
     * Uses each actual SIDX segment's byte length and duration, with exact completed segment endpoints.
     * Gaps count toward this target, which must not be used as playable buffer or a decode deadline.
     * Metadata before the first segment maps to its start; absent index/read-epoch state returns null.
     */
    @Synchronized
    fun schedulingHorizonTimeMs(track: DownloadTrack): Long? {
        if (closed) return null
        val horizon = schedulingHorizons[track]?.endExclusive ?: return null
        val segments = indexes[track]?.takeIf { it.isNotEmpty() } ?: return null
        val segment = segments.firstOrNull { it.endByte >= horizon - 1 } ?: return segments.last().endTimeMs
        if (horizon <= segment.startByte) return segment.startTimeMs
        val length = segment.endByte - segment.startByte + 1
        val completed = horizon - segment.startByte
        if (completed >= length) return segment.endTimeMs
        return segment.startTimeMs +
            ((segment.endTimeMs - segment.startTimeMs).toDouble() * completed / length).toLong()
    }

    /** Track with the lower dispatch horizon; unknown mappings or equal horizons have no preference. */
    @Synchronized
    fun laggingTrack(): DownloadTrack? {
        val video = schedulingHorizonTimeMs(DownloadTrack.Video) ?: return null
        val audio = schedulingHorizonTimeMs(DownloadTrack.Audio) ?: return null
        return when {
            video < audio -> DownloadTrack.Video
            audio < video -> DownloadTrack.Audio
            else -> null
        }
    }

    private class SchedulingHorizon(
        var endExclusive: Long,
        val epoch: Long,
    )

    /**
     * Media-duration budget for an exploration, before the caller applies its timeout multiplier.
     * Weights each byte overlap by that actual SIDX segment’s duration and adjusts for playback speed.
     * Extra buffered time never lengthens this budget. This estimates media duration, not decode
     * readiness; missing byte mappings return null.
     */
    @Synchronized
    fun explorationBudgetNanos(
        track: DownloadTrack,
        start: Long,
        end: Long,
    ): Long? {
        if (closed || start < 0 || end < start || end == Long.MAX_VALUE) return null
        val segments = indexes[track] ?: return null
        var covered = 0L
        var durationMs = 0.0
        for (segment in segments) {
            val overlapStart = maxOf(start, segment.startByte)
            val overlapEnd = minOf(end, segment.endByte)
            if (overlapStart > overlapEnd) continue
            val bytes = overlapEnd - overlapStart + 1
            covered += bytes
            durationMs += (segment.endTimeMs - segment.startTimeMs).toDouble() * bytes /
                (segment.endByte - segment.startByte + 1)
        }
        if (covered != end - start + 1 || durationMs <= 0) return null
        val speed = playback?.speed?.takeIf { it.isFinite() && it > 0 } ?: 1f
        return (durationMs / speed * 1_000_000).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong().coerceAtLeast(1)
    }

    /**
     * Inclusive byte bounds of the indexed media segment containing [startByte].
     * Metadata and unknown indexes return null; callers must not invent segment boundaries.
     */
    @Synchronized
    internal fun segmentBounds(
        track: DownloadTrack,
        startByte: Long,
    ): LongRange? {
        if (closed || startByte < 0) return null
        val segment = indexes[track]?.firstOrNull { startByte in it.startByte..it.endByte } ?: return null
        return segment.startByte..segment.endByte
    }

    /**
     * Bounded inclusive byte hint for retaining useful work before Media3 supplies its exact seek range.
     * Includes the preceding indexed segment when the target segment's start still fits, allowing
     * decoder/keyframe lookback. This is a retention hint, not a replacement for Media3 seek logic.
     * Positions beyond the indexed duration select the final segment; invalid input or unknown indexes
     * return null. The exact data-source open must refine this approximate window.
     */
    @Synchronized
    fun seekWindow(
        track: DownloadTrack,
        positionMs: Long,
        maxBytes: Long,
    ): LongRange? {
        if (closed || positionMs < 0 || maxBytes <= 0) return null
        val segments = indexes[track]?.takeIf { it.isNotEmpty() } ?: return null
        val targetIndex = segments.indexOfFirst { it.endTimeMs > positionMs }.takeIf { it >= 0 } ?: segments.lastIndex
        val target = segments[targetIndex]
        val previous = segments.getOrNull(targetIndex - 1)
        val start =
            if (previous != null && target.startByte - previous.startByte < maxBytes) {
                previous.startByte
            } else {
                target.startByte
            }
        // Subtract before adding, so even a near-Long.MAX_VALUE SIDX offset stays bounded.
        val end = start + minOf(maxBytes - 1, segments.last().endByte - start)
        return start..end
    }

    /** Stable media-time priority for comparing queued work while the playback clock is buffering. */
    @Synchronized
    internal fun schedulingTimeMs(
        track: DownloadTrack,
        startByte: Long,
    ): Long? = if (closed) null else indexes[track]?.firstOrNull { it.endByte >= startByte }?.startTimeMs

    /** Removes abandoned ranges when a data source is closed or replaced. */
    @Synchronized
    fun discard(ids: Collection<Long>) {
        if (closed) return
        ids.forEach(blocks::remove)
        publishSoon()
    }

    @Synchronized
    private fun publishSoon() {
        if (closed || publishing) return
        publishing = true
        scope.launch {
            delay(100)
            // Cache/reader locks must never nest inside the monitor lock: workers report here.
            val residency = availabilitySource
            val localRanges = residency?.sample?.invoke().orEmpty()
            val inFlightRanges = inFlightSampler?.invoke().orEmpty()
            synchronized(this@DownloadMonitor) {
                if (!closed) {
                    sampleHistory()
                    val available = availableRanges(residency?.tracks.orEmpty(), localRanges)
                    mutableSnapshots.value =
                        DownloadSnapshot(
                            active,
                            config.maxRequests.coerceIn(2, 64),
                            blocks.values.map(::mapBlock),
                            routes.values.map { route ->
                                route.copy(
                                    cooldownRemainingMs =
                                        (
                                            (cooldownUntil[route.host] ?: 0) -
                                                System.nanoTime() / 1_000_000
                                        ).coerceAtLeast(0),
                                )
                            },
                            history.toList(),
                            segmentStats(),
                            videoProbes,
                            audioProbes,
                            startupProbing && !scheduling.everReady,
                            available,
                            transferStats(localRanges, inFlightRanges),
                            workTelemetry.workers(),
                            waitReason,
                            scheduling.state,
                            transferMeasurements(localRanges, inFlightRanges),
                            commonBufferedRanges(available, residency?.tracks.orEmpty()),
                        )
                }
                publishing = false
            }
        }
    }

    private fun transferMeasurements(
        local: Map<DownloadTrack, List<LongRange>>,
        inFlight: Map<DownloadTrack, List<LongRange>>,
    ): DownloadTransferMeasurements {
        fun unionBytes(ranges: Map<DownloadTrack, List<LongRange>>): Long =
            ranges.values.fold(0L) { total, intervals ->
                val ledger = DownloadByteLedger()
                intervals.forEach { ledger.add(it.first, it.last) }
                saturatedByteSum(total, ledger.totalBytes())
            }
        val retained = DownloadTrack.entries.associateWith { local[it].orEmpty() + inFlight[it].orEmpty() }
        return DownloadTransferMeasurements(
            downloadedBytes,
            deliveredToPlayerBytes,
            consumedRanges.values.fold(0L) { total, ledger -> saturatedByteSum(total, ledger.totalBytes()) },
            unionBytes(local),
            unionBytes(inFlight),
            unionBytes(retained),
            confirmedTransportDiscardedBytes,
        )
    }

    private fun transferStats(
        local: Map<DownloadTrack, List<LongRange>>,
        inFlight: Map<DownloadTrack, List<LongRange>>,
    ): DownloadTransferStats {
        exactTransferSampler?.invoke()?.let { measured ->
            return DownloadTransferStats(
                measured.downloadedBytes,
                measured.usedBytes,
                measured.pendingBytes,
                measured.discardedBytes,
                measured.discardedByKind.map { (kind, bytes) -> DownloadOverhead(kind, bytes) },
                exact = true,
            )
        }
        val used = consumedRanges.values.fold(0L) { sum, ledger -> saturatedByteSum(sum, ledger.totalBytes()) }
        val pending =
            DownloadTrack.entries.fold(0L) { sum, track ->
                val ledger = consumedRanges[track] ?: DownloadByteLedger()
                saturatedByteSum(sum, ledger.unconsumedBytes(local[track].orEmpty() + inFlight[track].orEmpty()))
            }
        // Sampling crosses transport locks; clamp a transient sample to the cumulative body total.
        val accountedUsed = minOf(used, downloadedBytes)
        val accountedPending = minOf(pending, downloadedBytes - accountedUsed)
        val overhead = downloadedBytes - accountedUsed - accountedPending
        return DownloadTransferStats(
            downloadedBytes,
            accountedUsed,
            accountedPending,
            overhead,
            workTelemetry.overhead(overhead),
        )
    }

    private fun availableRanges(
        tracks: Set<DownloadTrack>,
        local: Map<DownloadTrack, List<LongRange>>,
    ): List<DownloadAvailableRange> =
        tracks.flatMap { track ->
            val times = mutableListOf<LongRange>()
            playerBuffer?.let(times::add)
            times.addAll(mapByteRanges(track, local[track].orEmpty()))
            // Public Media3 buffering is a common endpoint and hides a leading audio/video queue.
            // Current-epoch delivered bytes approximate that track's queue through SIDX mapping.
            // No back buffer is configured: once played, only downloader residency may keep it green.
            val playhead = playback?.positionMs?.coerceAtLeast(0) ?: 0L
            val delivered = deliveredResidency[track]?.bytes?.ranges().orEmpty()
            for (range in mapByteRanges(track, delivered)) {
                if (range.last >= playhead) times.add(maxOf(range.first, playhead)..range.last)
            }
            mergeRanges(times).map { DownloadAvailableRange(track, it.first, it.last + 1) }
        }

    private fun mapByteRanges(
        track: DownloadTrack,
        ranges: List<LongRange>,
    ): List<LongRange> {
        val times = mutableListOf<LongRange>()
        val segments = indexes[track].orEmpty()
        for (bytes in mergeRanges(ranges)) {
            for (segment in segments) {
                if (segment.endByte < bytes.first) continue
                if (segment.startByte > bytes.last) break
                val size = segment.endByte - segment.startByte + 1
                val duration = segment.endTimeMs - segment.startTimeMs
                val startFraction = (maxOf(bytes.first, segment.startByte) - segment.startByte).toDouble() / size
                val endFraction = (minOf(bytes.last, segment.endByte) - segment.startByte + 1).toDouble() / size
                val start = segment.startTimeMs + kotlin.math.ceil(duration * startFraction).toLong()
                val end = segment.startTimeMs + (duration * endFraction).toLong()
                if (end > start) times.add(start until end)
            }
        }
        return times
    }

    // Union actual intervals; never fill a hole merely because a farther block completed.
    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        val result = mutableListOf<LongRange>()
        for (range in ranges.filter { it.first >= 0 && !it.isEmpty() }.sortedBy { it.first }) {
            val previous = result.lastOrNull()
            if (previous != null && (range.first <= previous.last || range.first - previous.last == 1L)) {
                result[result.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                result.add(range)
            }
        }
        return result
    }

    private fun segmentStats(): List<DownloadSegmentStats> =
        indexes.map { (track, segments) ->
            DownloadSegmentStats(
                track,
                segments.size,
                segments.map { (it.endByte - it.startByte + 1).toDouble() }.average(),
            )
        }

    private fun sampleHistory() {
        if (!config.diagnosticsEnabled) return
        val now = System.nanoTime()
        val elapsed = (now - chartAt) / 1_000_000_000.0
        if (elapsed < 1.0) return
        val segments = segmentStats()

        fun mean(track: DownloadTrack): Double = segments.firstOrNull { it.track == track }?.averageBytes ?: 0.0
        history.addLast(
            DownloadSample(
                (now - chartStart) / 1_000_000,
                active,
                bufferSeconds,
                mean(DownloadTrack.Video),
                mean(DownloadTrack.Audio),
                routes.values.associate { route ->
                    route.host to
                        DownloadHostSample(
                            (trafficBytes[route.host] ?: 0) / elapsed,
                            route.bytesPerSecond.toDouble(),
                            route.bytesPerSecond * route.schedulingConfidence,
                        )
                },
                explorationCount,
                rescueCount,
                failureCount,
            ),
        )
        while (history.size > 120) history.removeFirst()
        trafficBytes.clear()
        chartAt = now
    }

    private fun mapBlock(block: DownloadBlock): DownloadBlock {
        val segments = indexes[block.track] ?: return block
        val first =
            segments.firstOrNull { it.endByte >= block.startByte && it.startByte <= block.endByte } ?: return block
        val last =
            segments.lastOrNull { it.startByte <= block.endByte && it.endByte >= block.startByte } ?: return block

        // Segment boundaries are indexed; positions within each segment are approximate.
        fun timeAt(
            segment: IndexedSegment,
            position: Long,
        ): Long {
            val fraction = (position - segment.startByte).toDouble() / (segment.endByte - segment.startByte + 1)
            return segment.startTimeMs + ((segment.endTimeMs - segment.startTimeMs) * fraction).toLong()
        }
        return block.copy(
            startTimeMs = timeAt(first, maxOf(block.startByte, first.startByte)),
            endTimeMs =
                timeAt(
                    last,
                    minOf(block.endByte, last.endByte) + 1,
                ),
        )
    }

    /** Cancels pending publications and clears all state on playback replacement. */
    @Synchronized
    override fun close() {
        if (closed) return
        trace("session_closed")
        closed = true
        traceSampler = null
        exactTransferSampler = null
        availabilitySource = null
        inFlightSampler = null
        consumedRanges.clear()
        deliveredResidency.clear()
        downloadedBytes = 0
        deliveredToPlayerBytes = 0
        confirmedTransportDiscardedBytes = 0
        workTelemetry.clear()
        waitReason = null
        playerBuffer = null
        scope.cancel()
        blocks.clear()
        history.clear()
        trafficBytes.clear()
        routes.clear()
        cooldownUntil.clear()
        prefixes.clear()
        indexes.clear()
        schedulingHorizons.clear()
        playback = null
        active = 0
        mutableSnapshots.value = DownloadSnapshot(maxRequests = config.maxRequests.coerceIn(2, 64))
    }

    private companion object {
        const val MAX_PREFIX_BYTES = 2 * 1024 * 1024
    }
}

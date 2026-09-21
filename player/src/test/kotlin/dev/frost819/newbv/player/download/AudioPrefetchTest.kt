package dev.frost819.newbv.player.download

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies ahead-track prefetch keeps using free capacity even when the other track cannot advance. */
class AudioPrefetchTest {
    private val audio = MediaTrackSource("audio", DownloadTrack.Audio, listOf("https://media.example/audio"))
    private val video = MediaTrackSource("video", DownloadTrack.Video, listOf("https://media.example/video"))
    private val config = ParallelDownloadConfig(enabled = true, maxRequests = 4, minimumBlockKiB = 64)
    private val headBytes = 65536
    private val segmentBytes = 65536

    @BeforeEach
    fun mockAndroid() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun restoreAndroid() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `ahead audio keeps prefetching while video is stalled`() {
        exerciseAheadTrack(DownloadTrack.Audio)
    }

    @Test
    fun `ahead video keeps prefetching while audio is stalled`() {
        exerciseAheadTrack(DownloadTrack.Video)
    }

    private fun exerciseAheadTrack(kind: DownloadTrack) {
        // The old audio gate stopped the third segment here despite free HTTP slots.
        val bytes = indexedMedia(earliestMs = 104000)
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val monitor = DownloadMonitor(config)
        val other = if (kind == DownloadTrack.Audio) DownloadTrack.Video else DownloadTrack.Audio
        monitor.recordBytes(other, 0, bytes.copyOf(headBytes))
        monitor.beginRead(other, headBytes.toLong())
        monitor.updatePlayback(105981, 1f, true, false, true, bufferedPositionMs = 106333, isReady = true)
        val thirdStart = headBytes + 2 * segmentBytes
        val thirdRequested = CountDownLatch(1)
        try {
            ParallelDownloadSession(
                client(bytes, requested) { if (it == thirdStart) thirdRequested.countDown() },
                config,
                VodPlaybackSource("test", video, audio),
                monitor,
            ).use { session ->
                val reader =
                    session
                        .factory(
                            if (kind == DownloadTrack.Audio) audio else video,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                try {
                    reader.open(spec(kind))
                    // No player read or movement on the other track is needed to dispatch ahead work.
                    assertTrue(thirdRequested.await(3, TimeUnit.SECONDS), "Ahead track left spare capacity idle")
                    assertEquals(104000L, monitor.schedulingHorizonTimeMs(other))
                    val output = ByteArray(segmentBytes)
                    var loaded = 0
                    while (true) {
                        val count = reader.read(output, 0, output.size)
                        if (count < 0) break
                        loaded += count
                    }
                    assertEquals(bytes.size, loaded)
                    assertEquals(other, monitor.laggingTrack())
                } finally {
                    reader.close()
                }
            }
        } finally {
            monitor.close()
        }
    }

    @Test
    fun `audio only playback is not constrained by an absent video reader`() {
        val bytes = indexedMedia()
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val monitor = DownloadMonitor(config)
        // Even stale video index information must not apply the paired-track policy to audio-only media.
        monitor.recordBytes(DownloadTrack.Video, 0, bytes.copyOf(headBytes))
        monitor.updatePlayback(0, 1f, true, false, true, isReady = true)
        val worker = Executors.newSingleThreadExecutor()
        try {
            ParallelDownloadSession(
                client(bytes, requested),
                config,
                VodPlaybackSource("test", null, audio),
                monitor,
            ).use { session ->
                val reader =
                    session
                        .factory(
                            audio,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                try {
                    reader.open(spec())
                    val loaded =
                        worker.submit<Int> {
                            val buffer = ByteArray(segmentBytes)
                            var total = 0
                            while (true) {
                                val count = reader.read(buffer, 0, buffer.size)
                                if (count < 0) break
                                total += count
                            }
                            total
                        }
                    assertEquals(bytes.size, loaded.get(3, TimeUnit.SECONDS))
                } finally {
                    reader.close()
                }
            }
        } finally {
            worker.shutdownNow()
            monitor.close()
        }
    }

    private fun client(
        bytes: ByteArray,
        requested: MutableList<Int>,
        onRequest: (Int) -> Unit = {},
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val range = requireNotNull(chain.request().header("Range")).removePrefix("bytes=").split("-")
                val start = range[0].toInt()
                val end = minOf(range[1].toInt(), bytes.lastIndex)
                requested.add(start)
                onRequest(start)
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Range")
                    .header("Content-Range", "bytes $start-$end/${bytes.size}")
                    .body(bytes.copyOfRange(start, end + 1).toResponseBody())
                    .build()
            }.build()

    private fun spec(kind: DownloadTrack = DownloadTrack.Audio): DataSpec {
        val uri = mockk<Uri>()
        every { uri.toString() } returns (if (kind == DownloadTrack.Audio) audio else video).urls.first()
        return DataSpec.Builder().setUri(uri).build()
    }

    private fun indexedMedia(earliestMs: Int = 0): ByteArray {
        val count = 8
        val size = 32 + 12 * count
        val bytes = ByteArray(headBytes + segmentBytes * count) { (it % 251).toByte() }
        ByteBuffer.wrap(bytes).apply {
            putInt(size)
                .putInt(0x73696478)
                .putInt(0)
                .putInt(1)
                .putInt(1000)
            putInt(earliestMs).putInt(headBytes - size).putShort(0).putShort(count.toShort())
            repeat(count) { putInt(segmentBytes).putInt(2000).putInt(0) }
        }
        return bytes
    }
}

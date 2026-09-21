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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rescue copies follow the earliest consumer-blocking range, including across media tracks. */
class BlockingRescueOwnershipTest {
    @BeforeEach
    fun mockLogs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun restoreLogs() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `overdue queued block cannot rescue until preceding block reaches consumer`() {
        exercise(crossTrack = false)
    }

    @Test
    fun `video and audio share one earliest blocking rescue owner`() {
        exercise(crossTrack = true)
    }

    private fun exercise(crossTrack: Boolean) {
        val video =
            MediaTrackSource(
                "v",
                DownloadTrack.Video,
                listOf("https://one.example.com/video", "https://two.example.com/video"),
            )
        val audio =
            MediaTrackSource(
                "a",
                DownloadTrack.Audio,
                listOf("https://one.example.com/audio", "https://two.example.com/audio"),
            )
        val size = 65536 + if (crossTrack) 131072 else 262144
        val data = ByteArray(size) { (it % 251).toByte() }
        val threatened = AtomicBoolean()
        val initialBodies = CountDownLatch(2)
        val firstRescue = CountDownLatch(1)
        val releaseFirstRescue = CountDownLatch(1)
        val laterRescue = CountDownLatch(1)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } answers { !threatened.get() }
        every { monitor.isPlaybackAdvancing() } returns true
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>()
            start..minOf(size - 1L, start + 131071)
        }
        every { monitor.schedulingTimeMs(any(), any()) } answers {
            if (firstArg<DownloadTrack>() == DownloadTrack.Audio) 1000L else secondArg<Long>() / 65536
        }
        every { monitor.deadlineNanos(any(), any()) } answers {
            if (threatened.get()) System.nanoTime() - 1 else null
        }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val bounds =
                        requireNotNull(
                            request.header("Range"),
                        ).removePrefix("bytes=").split("-").map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], data.lastIndex)
                    val path = request.url.encodedPath
                    if (start != 0) {
                        val callNumber = counts.computeIfAbsent("$path:$start") { AtomicInteger() }.incrementAndGet()
                        if (callNumber == 1) {
                            initialBodies.countDown()
                            val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                            while (!chain.call().isCanceled() && System.nanoTime() < timeout) Thread.sleep(10)
                            assertTrue(
                                chain.call().isCanceled(),
                                "Original blocked body was not cancelled by its winner",
                            )
                            throw IOException("cancelled")
                        }
                        assertEquals(2, callNumber, "Only one alternative host exists")
                        if (path == "/video" && start == 65536) {
                            firstRescue.countDown()
                            assertTrue(releaseFirstRescue.await(5, TimeUnit.SECONDS))
                        } else {
                            laterRescue.countDown()
                        }
                    }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(data.copyOfRange(start, end + 1).toResponseBody())
                        .build()
                }.build()
        val worker = Executors.newFixedThreadPool(2)
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = 8, minimumBlockKiB = 128),
                VodPlaybackSource("test", video, if (crossTrack) audio else null),
                monitor,
            ).use { session ->
                val tracks = if (crossTrack) listOf(video, audio) else listOf(video)
                val readers =
                    tracks.map { track ->
                        session
                            .factory(
                                track,
                                DataSource.Factory { error("Unexpected fallback") },
                            ).createDataSource()
                            .also { reader ->
                                val uri = mockk<Uri>()
                                every { uri.toString() } returns track.urls.first()
                                reader.open(DataSpec.Builder().setUri(uri).build())
                            }
                    }
                val results =
                    readers.map { reader ->
                        worker.submit<ByteArray> {
                            val result = ByteArray(size)
                            var position = 0
                            while (position < size) {
                                val count = reader.read(result, position, size - position)
                                assertTrue(count > 0)
                                position += count
                            }
                            result
                        }
                    }
                assertTrue(initialBodies.await(2, TimeUnit.SECONDS))
                // Let both consumer reads reach their awaited ranges before moving deadlines.
                Thread.sleep(100)
                threatened.set(true)
                assertTrue(firstRescue.await(2, TimeUnit.SECONDS))
                assertFalse(laterRescue.await(1300, TimeUnit.MILLISECONDS), "Later overdue range stole rescue capacity")
                releaseFirstRescue.countDown()
                assertTrue(laterRescue.await(3, TimeUnit.SECONDS), "Rescue owner did not move after first winner")
                results.forEach { assertContentEquals(data, it.get(3, TimeUnit.SECONDS)) }
                readers.forEach { it.close() }
            }
        } finally {
            releaseFirstRescue.countDown()
            worker.shutdownNow()
        }
    }
}

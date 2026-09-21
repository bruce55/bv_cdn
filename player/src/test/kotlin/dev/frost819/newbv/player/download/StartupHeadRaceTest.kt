package dev.frost819.newbv.player.download

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Startup probes seed several measured routes without delaying the first playable head. */
class StartupHeadRaceTest {
    @BeforeEach fun mockLogs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach fun restoreLogs() {
        unmockkStatic(Log::class)
    }

    @Test fun `first head returns while other probes finish measurement within shared cap`() {
        exercise(false)
    }

    @Test fun `failed startup candidate cannot invalidate an already delivered valid head`() {
        exercise(true)
    }

    @Test fun `audio startup probes return first data then cancel unfinished duplicates at readiness`() {
        val urls = (0..5).map { "https://audio$it.example.com/audio" }
        val audio = MediaTrackSource("a", DownloadTrack.Audio, urls)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        val ready =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        every { monitor.hasStartedPlayback() } answers { ready.get() }
        val initial = CountDownLatch(6)
        val next = CountDownLatch(1)
        val releaseWinner = CountDownLatch(1)
        val cancelled = CountDownLatch(5)
        val count = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val ordinal = count.incrementAndGet()
                    val concurrent = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, concurrent) }
                    initial.countDown()
                    if (ordinal == 3) next.countDown()
                    try {
                        if (ordinal == 1) {
                            assertTrue(releaseWinner.await(3, TimeUnit.SECONDS))
                        } else {
                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                            while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(10)
                            assertTrue(chain.call().isCanceled())
                            cancelled.countDown()
                            throw java.io.IOException("cancelled probe")
                        }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes 0-65535/65536")
                            .body(ByteArray(65536).toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                    }
                }.build()
        val worker =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = 8),
                VodPlaybackSource("test", null, audio),
                monitor,
            ).use { session ->
                val reader =
                    session
                        .factory(
                            audio,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns urls.first()
                val opened = worker.submit<Long> { reader.open(DataSpec.Builder().setUri(uri).build()) }
                assertTrue(initial.await(2, TimeUnit.SECONDS))
                assertEquals(6, count.get())
                releaseWinner.countDown()
                assertEquals(65536L, opened.get(2, TimeUnit.SECONDS))
                assertTrue(next.await(2, TimeUnit.SECONDS))
                ready.set(true)
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertEquals(6, count.get())
                assertEquals(6, peak.get())
                verify(timeout = 1000, exactly = 5) {
                    monitor.routeFinished(any(), any(), any(), null, any(), true, any())
                }
                reader.close()
            }
        } finally {
            releaseWinner.countDown()
            ready.set(true)
            worker.shutdownNow()
        }
    }

    private fun exercise(invalidSlowCandidate: Boolean) {
        val urls = listOf("fast.example.com", "slow.example.com", "other.example.com").map { "https://$it/video" }
        val track = MediaTrackSource("v", DownloadTrack.Video, urls)
        val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } returns null
        val allStarted = CountDownLatch(3)
        val release = CountDownLatch(1)
        val othersFinished = CountDownLatch(2)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bytes = ByteArray(65536) { (it % 251).toByte() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    val slow = chain.request().url.host != "fast.example.com"
                    try {
                        assertEquals("bytes=0-65535", chain.request().header("Range"))
                        allStarted.countDown()
                        assertTrue(allStarted.await(3, TimeUnit.SECONDS))
                        if (slow) {
                            assertTrue(release.await(3, TimeUnit.SECONDS))
                            assertFalse(chain.call().isCanceled())
                        }
                        val malformed = invalidSlowCandidate && chain.request().url.host == "slow.example.com"
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message(
                                "Partial Content",
                            ).header("Content-Range", if (malformed) "bytes 1-65535/65536" else "bytes 0-65535/65536")
                            .body(bytes.toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                        if (slow) othersFinished.countDown()
                    }
                }.build()
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
                val reader =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns urls.first()
                assertEquals(65536L, reader.open(DataSpec.Builder().setUri(uri).build()))
                val actual = ByteArray(65536)
                assertEquals(65536, reader.read(actual, 0, actual.size))
                assertContentEquals(bytes, actual)
                assertEquals(2L, othersFinished.count)
                release.countDown()
                assertTrue(othersFinished.await(2, TimeUnit.SECONDS))
                verify(
                    timeout = 1000,
                ) { monitor.routeFinished("other.example.com", 65536L, any(), null, any(), false, any()) }
                if (!invalidSlowCandidate) {
                    verify(
                        timeout = 1000,
                    ) { monitor.routeFinished("slow.example.com", 65536L, any(), null, any(), false, any()) }
                } else {
                    verify(
                        timeout = 1000,
                    ) { monitor.routeFinished("slow.example.com", any(), any(), any<String>(), any(), false, any()) }
                }
                reader.close()
            }
            assertEquals(3, peak.get())
        } finally {
            release.countDown()
        }
    }
}

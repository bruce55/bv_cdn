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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies protected route exploration and first validated winner semantics. */
class ProtectedRangeRaceTest {
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
    fun `alternative wins stalled primary and cancellation does not fail route`() {
        exerciseRace(false)
    }

    @Test
    fun `invalid alternative cannot cancel good primary or reach consumer`() {
        exerciseRace(true)
    }

    @Test
    fun `late block deadline rescues before network failure`() {
        exerciseRace(false, urgent = true)
    }

    @Test
    fun `rebuffering escalates the blocked range and cancels ordinary losers`() {
        exerciseEscalation(false)
    }

    @Test
    fun `deadline ramp supplies the blocked range before playback stalls`() {
        exerciseEscalation(true)
    }

    private fun exerciseEscalation(preemptive: Boolean) {
        val urls = (0..5).map { "https://cdn$it.example.com/video" }
        val track = MediaTrackSource("v", DownloadTrack.Video, urls)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        every { monitor.deadlineNanos(any(), any()) } returns if (preemptive) deadline else null
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } returns null
        every { monitor.isRebuffering() } returns !preemptive
        every { monitor.isPlaybackAdvancing() } returns preemptive
        val ready =
            java.util.concurrent.atomic
                .AtomicBoolean(!preemptive)
        every { monitor.hasStartedPlayback() } answers { ready.get() }
        val attempts = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val losers = CountDownLatch(2)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val head = chain.request().header("Range") == "bytes=0-65535"
                    val concurrent = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, concurrent) }
                    try {
                        if (!head && attempts.incrementAndGet() <= 2) {
                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
                            while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(10)
                            assertTrue(chain.call().isCanceled())
                            losers.countDown()
                            throw IOException("cancelled")
                        }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Range")
                            .header("Content-Range", if (head) "bytes 0-65535/196608" else "bytes 65536-196607/196608")
                            .body(ByteArray(if (head) 65536 else 131072) { 7 }.toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                    }
                }.build()
        ParallelDownloadSession(
            client,
            ParallelDownloadConfig(enabled = true, maxRequests = 8),
            VodPlaybackSource("test", if (preemptive) track else null, null),
            monitor,
        ).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            val uri = mockk<Uri>()
            every { uri.toString() } returns urls.first()
            assertEquals(196608L, reader.open(DataSpec.Builder().setUri(uri).build()))
            if (preemptive) {
                verify(
                    timeout = 2000,
                    atLeast = 3,
                ) { monitor.routeFinished(any(), 65536L, any(), null, any(), false, any()) }
                ready.set(true)
            }
            val head = ByteArray(65536)
            assertEquals(65536, reader.read(head, 0, head.size))
            val tail = ByteArray(131072)
            assertEquals(131072, reader.read(tail, 0, tail.size))
            assertContentEquals(ByteArray(131072) { 7 }, tail)
            assertEquals(3, attempts.get())
            assertTrue(peak.get() <= 8)
            if (preemptive) assertTrue(System.nanoTime() < deadline)
            verify(exactly = 1) { monitor.routeRescueProvided(any()) }
            // Unknown routes now enter as protected recovery measurements. The ordinary
            // primary is cancelled by the winner; its exploratory rival must keep measuring.
            verify(timeout = 2000, exactly = 1) {
                monitor.routeFinished(any(), any(), any(), null, any(), true, any())
            }
            assertEquals(1L, losers.count)
            verify(atLeast = 1) { monitor.exploration(any(), ExplorationState.Testing) }
            reader.close()
            assertTrue(losers.await(2, TimeUnit.SECONDS))
            verify(timeout = 1000, exactly = 2) {
                monitor.routeFinished(any(), any(), any(), null, any(), true, any())
            }
        }
    }

    @Test
    fun `EOF response cannot shorten previously validated total`() {
        val url = "https://primary.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(url))
        val config = ParallelDownloadConfig(enabled = true)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } returns null
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val first = chain.request().header("Range")?.startsWith("bytes=0-") == true
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (first) 206 else 416)
                        .message("Range")
                        .header("Content-Range", if (first) "bytes 0-65535/65537" else "bytes */65536")
                        .body(ByteArray(if (first) 65536 else 0).toResponseBody())
                        .build()
                }.build()
        ParallelDownloadSession(client, config, VodPlaybackSource("test", null, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            val uri = mockk<Uri>()
            every { uri.toString() } returns url
            assertEquals(65537L, reader.open(DataSpec.Builder().setUri(uri).build()))
            assertEquals(65536, reader.read(ByteArray(65536), 0, 65536))
            assertFailsWith<IOException> { reader.read(ByteArray(1), 0, 1) }
            reader.close()
        }
    }

    private fun exerciseRace(
        invalidAlternative: Boolean,
        urgent: Boolean = false,
    ) {
        val primary = "https://primary.example.com/video"
        val alternate = "https://alternate.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(primary, alternate))
        val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } answers
            { if (urgent) System.nanoTime() - 1 else null }
        every { monitor.isPlaybackPaused() } returns false
        val alternateStarted = CountDownLatch(1)
        val primaryFinished = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bytes = ByteArray(131072) { (it % 251).toByte() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (chain.request().header("Range") == "bytes=0-65535") {
                        return@addInterceptor Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Range")
                            .header("Content-Range", "bytes 0-65535/196608")
                            .body(ByteArray(65536).toResponseBody())
                            .build()
                    }
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    try {
                        if (chain.request().url.host == "primary.example.com") {
                            assertTrue(alternateStarted.await(3, TimeUnit.SECONDS))
                            if (invalidAlternative) {
                                Thread.sleep(100)
                            } else {
                                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                                while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(10)
                                assertTrue(chain.call().isCanceled())
                                throw IOException("cancelled")
                            }
                        } else {
                            alternateStarted.countDown()
                        }
                        val corrupt = invalidAlternative && chain.request().url.host == "alternate.example.com"
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header(
                                "Content-Range",
                                if (corrupt) "bytes 65537-196607/196608" else "bytes 65536-196607/196608",
                            ).body(bytes.toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                        if (chain.request().url.host == "primary.example.com") primaryFinished.countDown()
                    }
                }.build()
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", null, null), monitor).use { session ->
                val reader =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns primary
                assertEquals(196608L, reader.open(DataSpec.Builder().setUri(uri).build()))
                assertEquals(65536, reader.read(ByteArray(65536), 0, 65536))
                val actual = ByteArray(bytes.size)
                assertEquals(bytes.size, reader.read(actual, 0, actual.size))
                assertContentEquals(bytes, actual)
                assertTrue(primaryFinished.await(3, TimeUnit.SECONDS))
                reader.close()
            }
            verify(exactly = if (invalidAlternative) 0 else 1) {
                monitor.routeRescueProvided("alternate.example.com")
            }
            assertEquals(2, peak.get())
            if (urgent) {
                verify { Log.d("BvRange", match { it.contains("reason=deadline") }) }
                verify { monitor.routeDeadlineMiss("primary.example.com", 1.0) }
            }
            if (!invalidAlternative) {
                verify(
                    timeout = 1000,
                ) { monitor.routeFinished("primary.example.com", any(), any(), null, any(), true, any()) }
            }
        } finally {
            unmockkStatic(Log::class)
        }
    }
}

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recovery measurements use blocked playback work even when no distant deadline exists. */
class LowBufferExplorationTest {
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
    fun `unknown CDN recovers a blocked range with only two slots and no deadline`() {
        exercise(workers = 2, primaryWins = false)
    }

    @Test
    fun `sixteen slots explore a smaller finite pool without exceeding request limits`() {
        exercise(workers = 16, primaryWins = false)
    }

    @Test
    fun `unknown measurement finishes after known primary delivers the range`() {
        exercise(workers = 2, primaryWins = true)
    }

    @Test
    fun `paused playback does not launch low buffer exploration`() {
        exercise(workers = 16, primaryWins = true, paused = true)
    }

    @Test
    fun `exploration call timeout is one and a half times its media duration budget`() {
        exercise(workers = 2, primaryWins = false, explorationBudgetNanos = TimeUnit.SECONDS.toNanos(4))
    }

    private fun exercise(
        workers: Int,
        primaryWins: Boolean,
        paused: Boolean = false,
        explorationBudgetNanos: Long? = null,
    ) {
        val primary = "https://primary.example.com/video"
        val alternate = "https://alternate.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(primary, alternate))
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns paused
        every { monitor.isRebuffering() } returns !paused
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns explorationBudgetNanos
        val candidateStarted = CountDownLatch(1)
        val candidateReleased = CountDownLatch(1)
        val candidateFinished = CountDownLatch(1)
        val primaryFinished = CountDownLatch(1)
        val candidateCancelled = AtomicBoolean()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val candidateRequests = AtomicInteger()
        val data = ByteArray(65536 + 512 * 1024) { (it % 251).toByte() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range =
                        requireNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split("-")
                            .map(String::toInt)
                    val start = range[0]
                    val end = minOf(range[1], data.lastIndex)
                    val candidate = chain.request().url.host == "alternate.example.com"
                    val concurrent = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, concurrent) }
                    try {
                        if (start != 0 && !candidate) {
                            if (paused) {
                                Thread.sleep(1200)
                            } else {
                                assertTrue(
                                    candidateStarted.await(4, TimeUnit.SECONDS),
                                    "Unknown CDN was never dispatched",
                                )
                                if (!primaryWins) {
                                    val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                                    while (!chain.call().isCanceled() && System.nanoTime() < timeout) Thread.sleep(10)
                                    assertTrue(
                                        chain.call().isCanceled(),
                                        "Playback winner did not cancel ordinary loser",
                                    )
                                    throw IOException("cancelled")
                                }
                            }
                        }
                        if (candidate) {
                            assertEquals(
                                explorationBudgetNanos?.let { it + it / 2 } ?: TimeUnit.SECONDS.toNanos(15),
                                chain.call().timeout().timeoutNanos(),
                                "Protected exploration must use media-duration timeout or the unknown-duration fallback",
                            )
                            candidateRequests.incrementAndGet()
                            assertEquals(65536, start, "Probe must reuse the blocked media range")
                            assertEquals(data.lastIndex, end)
                            candidateStarted.countDown()
                            if (primaryWins) {
                                assertTrue(candidateReleased.await(4, TimeUnit.SECONDS))
                                candidateCancelled.set(chain.call().isCanceled())
                            }
                        }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Range")
                            .header("Content-Range", "bytes $start-$end/${data.size}")
                            .body(data.copyOfRange(start, end + 1).toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                        if (candidate) candidateFinished.countDown()
                        if (start != 0 && !candidate) primaryFinished.countDown()
                    }
                }.build()
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = workers, minimumBlockKiB = 512),
                VodPlaybackSource("test", track, null),
                monitor,
            ).use { session ->
                val reader =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns primary
                assertEquals(data.size.toLong(), reader.open(DataSpec.Builder().setUri(uri).build()))
                val actual = ByteArray(data.size)
                var offset = 0
                while (offset < actual.size) {
                    val count = reader.read(actual, offset, actual.size - offset)
                    assertTrue(count > 0)
                    offset += count
                }
                assertContentEquals(data, actual)
                assertTrue(primaryFinished.await(2, TimeUnit.SECONDS))
                if (paused) {
                    assertEquals(0, candidateRequests.get())
                    verify(exactly = 0) { monitor.exploration(any(), ExplorationState.Testing) }
                } else {
                    verify(exactly = 1) { monitor.exploration(any(), ExplorationState.Testing) }
                    if (primaryWins) {
                        assertEquals(1L, candidateFinished.count, "Measurement ended with playback winner")
                        candidateReleased.countDown()
                    }
                    assertTrue(candidateFinished.await(2, TimeUnit.SECONDS))
                    assertFalse(candidateCancelled.get())
                    verify(timeout = 2000, exactly = 1) { monitor.explorationFinished(any(), true, false) }
                    assertEquals(1, candidateRequests.get())
                    assertEquals(2, peak.get())
                }
                assertTrue(peak.get() <= workers)
                reader.close()
            }
        } finally {
            candidateReleased.countDown()
        }
    }
}

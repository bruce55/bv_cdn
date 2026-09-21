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
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Useful-half exploration keeps candidate measurement independent of playback rescue. */
class SplitExplorationTest {
    @BeforeEach fun mockLogs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach fun restoreLogs() {
        unmockkStatic(Log::class)
    }

    @Test fun `only farthest block splits and candidate survives known half completion`() {
        exercise(false, false)
    }

    @Test fun `invalid candidate half retries known route without corrupting joined bytes`() {
        exercise(true, false)
    }

    @Test fun `deadline rescue serves bytes while candidate continues full measurement`() {
        exercise(false, true)
    }

    @Test fun `closing reader cancels retained candidate measurement`() {
        exercise(false, true, cancelMeasurement = true)
    }

    @Test fun `automatic eight and sixty four workers explore their existing farthest block`() {
        exercise(false, false, workers = 8)
        exercise(false, false, workers = 8, minimumBlockKiB = 512)
        exercise(false, false, workers = 64)
    }

    @Test fun `512KiB block explores exact existing range and survives deadline rescue`() {
        exercise(false, true, workers = 8, segmentBytes = 3 * 1024 * 1024)
    }

    @Test fun `short segment explores whole without violating the selected block floor`() {
        val known = "https://primary.example.com/video"
        val candidate = "https://alternate.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(known, candidate))
        val size = 65536 + 100000
        val data = ByteArray(size) { (it % 251).toByte() }
        val sampled = Collections.synchronizedList(mutableListOf<String>())
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } returns (65536L until size.toLong())
        every { monitor.schedulingTimeMs(any(), any()) } returns 10000L
        every { monitor.deadlineNanos(any(), any()) } returns (System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = requireNotNull(chain.request().header("Range"))
                    if (chain.request().url.host == "alternate.example.com") sampled.add(range)
                    val bounds = range.removePrefix("bytes=").split("-").map(String::toInt)
                    val end = minOf(bounds[1], size - 1)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes ${bounds[0]}-$end/$size")
                        .body(data.copyOfRange(bounds[0], end + 1).toResponseBody())
                        .build()
                }.build()
        ParallelDownloadSession(
            client,
            ParallelDownloadConfig(enabled = true, maxRequests = 64, minimumBlockKiB = 4096),
            VodPlaybackSource("test", null, null),
            monitor,
        ).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            val uri = mockk<Uri>()
            every { uri.toString() } returns known
            reader.open(DataSpec.Builder().setUri(uri).build())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            assertContentEquals(data, out.toByteArray())
            assertEquals(listOf("bytes=65536-${size - 1}"), sampled.toList())
            reader.close()
        }
    }

    private fun exercise(
        invalid: Boolean,
        rescue: Boolean,
        cancelMeasurement: Boolean = false,
        workers: Int = 4,
        minimumBlockKiB: Int = 0,
        segmentBytes: Int = 4 * 1024 * 1024,
    ) {
        val known = "https://primary.example.com/video"
        val candidate = "https://alternate.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(known, candidate))
        val config = ParallelDownloadConfig(enabled = true, maxRequests = workers, minimumBlockKiB = minimumBlockKiB)
        val size = 65536 + segmentBytes
        val budget = DownloadMemoryBudget(workers, minimumBlockKiB * 1024L)
        var available = budget.sharedLimitBytes - 2 * budget.planningSpanLimitBytes - 2 * 65536
        val farthestRange =
            RangePartition
                .split(
                    65536,
                    size.toLong() - 1,
                    RangePartition.pieceBudget(workers, true, DownloadTrack.Video),
                    minimumBlockKiB * 1024L,
                ).takeWhile {
                    val charged = 2 * (it.last - it.first + 1)
                    (charged <= available).also { fits -> if (fits) available -= charged }
                }.last()
        val farthest = farthestRange.first.toInt()
        val length = farthestRange.last - farthestRange.first + 1
        val split = length >= maxOf(RangePartition.MIN_EXPLORATION_BYTES, minimumBlockKiB * 2048L)
        val midpoint = if (split) farthest + (length / 2).toInt() else farthest
        val candidateBytes = farthestRange.last.toInt() + 1 - midpoint
        if (segmentBytes == 3 * 1024 * 1024 && workers == 8) assertEquals(512 * 1024, candidateBytes)
        val data = ByteArray(size) { (it % 251).toByte() }
        val candidateStarted = CountDownLatch(1)
        val firstHalfFinished = CountDownLatch(if (split) 1 else 0)
        val releaseCandidate = CountDownLatch(1)
        val candidateFinished = CountDownLatch(1)
        val threatened = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val candidateRanges = Collections.synchronizedList(mutableListOf<LongRange>())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } returns 0L
        every { monitor.segmentBounds(any(), any()) } returns (0L until size.toLong())
        every { monitor.deadlineNanos(any(), any()) } answers {
            if (threatened.get()) System.nanoTime() - 1 else deadline
        }
        every { monitor.isPlaybackPaused() } returns false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    val request = chain.request()
                    val bounds =
                        requireNotNull(
                            request.header("Range"),
                        ).removePrefix("bytes=").split("-").map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], size - 1)
                    val probing = request.url.host == "alternate.example.com"
                    try {
                        if (probing) {
                            candidateRanges.add(start.toLong()..end.toLong())
                            candidateStarted.countDown()
                            assertTrue(firstHalfFinished.await(3, TimeUnit.SECONDS))
                            if (rescue) {
                                threatened.set(true)
                                assertTrue(releaseCandidate.await(5, TimeUnit.SECONDS))
                            } else {
                                Thread.sleep(100)
                            }
                            cancelled.set(chain.call().isCanceled())
                        }
                        val responseStart = if (probing && invalid) start + 1 else start
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes $responseStart-$end/$size")
                            .body(data.copyOfRange(start, end + 1).toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                        if (!probing && start == farthest && end == midpoint - 1) firstHalfFinished.countDown()
                        if (probing) candidateFinished.countDown()
                    }
                }.build()
        val worker = Executors.newSingleThreadExecutor()
        lateinit var ledger: ExactTransferLedger
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", null, null), monitor).use { session ->
                ledger =
                    ParallelDownloadSession::class.java.getDeclaredField("transferLedger").run {
                        isAccessible = true
                        get(session) as ExactTransferLedger
                    }
                val reader =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns known
                assertEquals(size.toLong(), reader.open(DataSpec.Builder().setUri(uri).build()))
                val result =
                    worker.submit<ByteArray> {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = reader.read(buffer, 0, buffer.size)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                assertTrue(candidateStarted.await(3, TimeUnit.SECONDS))
                assertContentEquals(data, result.get(4, TimeUnit.SECONDS))
                assertEquals(data.size.toLong(), ledger.snapshot().usedBytes, "Joined child spans must count once")
                if (rescue) {
                    assertEquals(1L, candidateFinished.count)
                    verify { monitor.routeDeadlineMiss("alternate.example.com", any()) }
                    if (cancelMeasurement) reader.close()
                    releaseCandidate.countDown()
                }
                assertTrue(candidateFinished.await(2, TimeUnit.SECONDS))
                assertEquals(cancelMeasurement, cancelled.get())
                if (!invalid && !cancelMeasurement) {
                    verify(timeout = 1000) {
                        monitor.routeFinished(
                            "alternate.example.com",
                            candidateBytes.toLong(),
                            any(),
                            null,
                            any(),
                            false,
                            any(),
                        )
                    }
                }
                reader.close()
            }
            val settledBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (ledger.snapshot().liveSourceCount > 0 && System.nanoTime() < settledBy) Thread.sleep(5)
            val snapshot = ledger.snapshot()
            assertEquals(data.size.toLong(), snapshot.usedBytes)
            assertEquals(snapshot.downloadedBytes, snapshot.usedBytes + snapshot.pendingBytes + snapshot.discardedBytes)
            assertEquals(snapshot.discardedBytes, snapshot.discardedByKind.values.sum())
            assertEquals(0L, snapshot.pendingBytes)
            assertEquals(0, snapshot.liveSourceCount)
            assertEquals(0, snapshot.liveRangeCount)
            assertEquals(listOf(midpoint.toLong()..farthestRange.last), candidateRanges.toList())
            assertTrue(peak.get() <= workers)
        } finally {
            releaseCandidate.countDown()
            worker.shutdownNow()
        }
    }
}

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
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the original browser's balanced piece arithmetic and startup reservations. */
class RangePartitionTest {
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
    fun `64 request budget retains rescue capacity and balanced automatic subdivision`() {
        val budget = RangePartition.pieceBudget(64, false, DownloadTrack.Video)
        assertEquals(63, budget)
        val pieces = RangePartition.split(0, 8 * 1024 * 1024L - 1, budget)
        assertEquals(63, pieces.size)
        assertEquals(8 * 1024 * 1024L, pieces.sumOf { it.last - it.first + 1 })
        assertTrue(pieces.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
        assertEquals(62, RangePartition.pieceBudget(64, true, DownloadTrack.Video))
        assertEquals(1, RangePartition.pieceBudget(64, true, DownloadTrack.Audio))
    }

    @Test
    fun `configured floor limits subdivision but cannot enlarge a short segment`() {
        val mib = 1024 * 1024L
        val pieces = RangePartition.split(0, 5 * mib - 1, 7, 2 * mib)
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.last - it.first + 1 >= 2 * mib })
        assertEquals(listOf(0L until mib), RangePartition.split(0, mib - 1, 7, 2 * mib))
        assertEquals(7, RangePartition.split(0, 5 * mib - 1, 7).size)
    }

    @Test
    fun `whole file Media3 request is partitioned at indexed segment boundaries`() {
        val url = "https://media.example.com/video"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(url))
        val config = ParallelDownloadConfig(enabled = true, maxRequests = 8)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        val firstSegmentEnd = 5 * 1024 * 1024L - 1
        val total = 6 * 1024 * 1024L
        every { monitor.segmentBounds(any(), any()) } answers {
            if (secondArg<Long>() <= firstSegmentEnd) 0L..firstSegmentEnd else firstSegmentEnd + 1 until total
        }
        every { monitor.deadlineNanos(any(), any()) } returns null
        val requested = Collections.synchronizedList(mutableListOf<LongRange>())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val bounds =
                        requireNotNull(
                            chain.request().header("Range"),
                        ).removePrefix("bytes=").split("-").map(String::toLong)
                    val range = bounds[0]..minOf(bounds[1], total - 1)
                    requested.add(range)
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes ${range.first}-${range.last}/$total")
                        .body(ByteArray((range.last - range.first + 1).toInt()).toResponseBody())
                        .build()
                }.build()
        ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            val uri = mockk<Uri>()
            every { uri.toString() } returns url
            assertEquals(total, reader.open(DataSpec.Builder().setUri(uri).build()))
            val buffer = ByteArray(65536)
            var delivered = 0L
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                delivered += count
            }
            assertEquals(total, delivered)
            reader.close()
        }
        val expected =
            listOf(0L..65535L) + RangePartition.split(65536, firstSegmentEnd, 6) +
                RangePartition.split(firstSegmentEnd + 1, total - 1, 7)
        assertEquals(expected, requested.sortedBy { it.first })
    }

    @Test
    fun `original policy splits a segment by worker count without a 2MiB floor`() {
        val small = RangePartition.split(100, 100 + 100 * 1024 - 1, 7)
        assertEquals(listOf(100L..51299L, 51300L..102499L), small)
        val large = RangePartition.split(65536, 65536 + 5 * 1024 * 1024 - 1, 7)
        assertEquals(7, large.size)
        assertEquals(5 * 1024 * 1024L, large.sumOf { it.last - it.first + 1 })
        assertTrue(large.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
        val sizes = large.map { it.last - it.first + 1 }
        assertTrue(sizes.max() - sizes.min() <= 1)
        assertTrue(sizes.min() > 256 * 1024)
    }

    @Test
    fun `startup uses six video pieces and one audio piece with eight requests`() {
        assertEquals(6, RangePartition.pieceBudget(8, true, DownloadTrack.Video))
        assertEquals(1, RangePartition.pieceBudget(8, true, DownloadTrack.Audio))
        assertEquals(7, RangePartition.pieceBudget(8, false, DownloadTrack.Video))
        assertEquals(7, RangePartition.pieceBudget(8, false, DownloadTrack.Audio))
    }
}

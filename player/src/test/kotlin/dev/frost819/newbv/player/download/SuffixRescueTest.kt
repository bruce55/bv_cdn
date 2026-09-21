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
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises published-prefix rescue offsets against a body that continues to advance. */
class SuffixRescueTest {
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
    fun `rescue requests missing suffix and stitches exact bytes while original advances`() {
        exercise()
    }

    @Test
    fun `invalid suffix Content Range cannot replace original bytes`() {
        exercise(invalidSuffix = true)
    }

    @Test
    fun `remaining sixty four KiB waits for current response without duplicate`() {
        exercise(tinyTail = true)
    }

    private fun exercise(
        invalidSuffix: Boolean = false,
        tinyTail: Boolean = false,
    ) {
        val headSize = 65536
        val published = 65536
        val blockSize = if (tinyTail) 131072 else 262144
        val data = ByteArray(headSize + blockSize) { (it % 251).toByte() }
        val track =
            MediaTrackSource(
                "v",
                DownloadTrack.Video,
                listOf("https://primary.example.com/video", "https://alternate.example.com/video"),
            )
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns false
        val prefixReady = CountDownLatch(1)
        val suffixStarted = CountDownLatch(1)
        val originalAdvanced = CountDownLatch(1)
        val originalDone = CountDownLatch(1)
        val releaseOriginal = AtomicBoolean()
        val suffixRequests = AtomicInteger()
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
                    val alternate = request.url.host == "alternate.example.com"
                    val response =
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Range")
                    if (alternate) {
                        suffixRequests.incrementAndGet()
                        assertEquals(
                            headSize + published,
                            start,
                            "Rescue re-requested bytes already published by original",
                        )
                        assertEquals(data.lastIndex, end)
                        suffixStarted.countDown()
                        assertTrue(originalAdvanced.await(3, TimeUnit.SECONDS))
                        if (invalidSuffix) releaseOriginal.set(true)
                        response
                            .header(
                                "Content-Range",
                                "bytes ${if (invalidSuffix) start + 1 else start}-$end/${data.size}",
                            ).body(data.copyOfRange(start, end + 1).toResponseBody())
                            .build()
                    } else if (start == 0) {
                        response
                            .header("Content-Range", "bytes 0-65535/${data.size}")
                            .body(data.copyOfRange(0, headSize).toResponseBody())
                            .build()
                    } else {
                        assertEquals(headSize, start)
                        val streaming =
                            object : Source {
                                private var position = 0

                                override fun read(
                                    sink: Buffer,
                                    byteCount: Long,
                                ): Long {
                                    if (position >= blockSize) return -1
                                    if (position >= published && !releaseOriginal.get()) {
                                        prefixReady.countDown()
                                        if (position == published && !tinyTail) {
                                            assertTrue(suffixStarted.await(4, TimeUnit.SECONDS))
                                        } else {
                                            originalAdvanced.countDown()
                                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                                            while (!releaseOriginal.get() &&
                                                !chain.call().isCanceled() &&
                                                System.nanoTime() < until
                                            ) {
                                                Thread.sleep(5)
                                            }
                                            if (chain.call().isCanceled()) throw IOException("cancelled")
                                            assertTrue(releaseOriginal.get(), "Original body never released")
                                        }
                                    }
                                    val count = minOf(byteCount.toInt(), 8192, blockSize - position)
                                    sink.write(data, headSize + position, count)
                                    position += count
                                    return count.toLong()
                                }

                                override fun timeout(): Timeout = Timeout.NONE

                                override fun close() {
                                    originalDone.countDown()
                                }
                            }.buffer()
                        val body =
                            object : ResponseBody() {
                                override fun contentType(): MediaType? = null

                                override fun contentLength(): Long = blockSize.toLong()

                                override fun source(): BufferedSource = streaming
                            }
                        response.header("Content-Range", "bytes $start-$end/${data.size}").body(body).build()
                    }
                }.build()
        val worker = Executors.newSingleThreadExecutor()
        lateinit var ledger: ExactTransferLedger
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = 4, minimumBlockKiB = 512),
                VodPlaybackSource("test", track, null),
                monitor,
            ).use { session ->
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
                every { uri.toString() } returns track.urls.first()
                reader.open(DataSpec.Builder().setUri(uri).build())
                val result =
                    worker.submit<ByteArray> {
                        val out = ByteArray(data.size)
                        var position = 0
                        while (position < out.size) {
                            val count = reader.read(out, position, out.size - position)
                            assertTrue(count > 0)
                            position += count
                        }
                        out
                    }
                assertTrue(prefixReady.await(2, TimeUnit.SECONDS))
                if (tinyTail) {
                    Thread.sleep(1300)
                    assertEquals(0, suffixRequests.get())
                    releaseOriginal.set(true)
                }
                assertContentEquals(data, result.get(5, TimeUnit.SECONDS))
                assertEquals(data.size.toLong(), ledger.snapshot().usedBytes, "Copied rescue prefix must count once")
                assertPartition(ledger.snapshot())
                assertTrue(originalDone.await(2, TimeUnit.SECONDS))
                assertEquals(if (tinyTail) 0 else 1, suffixRequests.get())
                if (invalidSuffix) {
                    verify(timeout = 1000) {
                        monitor.routeFinished("alternate.example.com", any(), any(), any<String>(), any(), false, any())
                    }
                }
                reader.close()
            }
            val snapshot = awaitSettled(ledger)
            assertEquals(data.size.toLong(), snapshot.usedBytes)
            if (!tinyTail && !invalidSuffix) {
                assertTrue(snapshot.discardedBytes > 0, "Original advancement beyond copied prefix must be discarded")
            } else {
                assertEquals(0L, snapshot.discardedBytes)
            }
        } finally {
            releaseOriginal.set(true)
            suffixStarted.countDown()
            worker.shutdownNow()
        }
    }

    private fun awaitSettled(ledger: ExactTransferLedger): ExactTransferLedger.Snapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (ledger.snapshot().liveSourceCount > 0 && System.nanoTime() < deadline) Thread.sleep(5)
        return ledger.snapshot().also {
            assertPartition(it)
            assertEquals(0L, it.pendingBytes)
            assertEquals(0, it.liveSourceCount)
            assertEquals(0, it.liveRangeCount)
        }
    }

    private fun assertPartition(snapshot: ExactTransferLedger.Snapshot) {
        assertEquals(snapshot.downloadedBytes, snapshot.usedBytes + snapshot.pendingBytes + snapshot.discardedBytes)
        assertEquals(snapshot.discardedBytes, snapshot.discardedByKind.values.sum())
    }
}

package dev.frost819.newbv.player.download

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Seek reuse must preserve validated bytes and useful requests without retaining obsolete network work. */
class SeekReuseTest {
    private val url = "https://media.example.com/video"
    private val track = MediaTrackSource("video", DownloadTrack.Video, listOf(url))
    private val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)
    private val fallback = DataSource.Factory { error("Unexpected sequential fallback") }

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
    fun `storage sample deduplicates cache and reader and drops consumed reader reference`() {
        val data = payload(BLOCK)
        val monitor = monitor(data.size)
        val sampler = AtomicReference<() -> Unit>({})
        val capacity = AtomicReference<Map<String, Any?>>(emptyMap())
        every { monitor.traceEnabled } returns true
        every { monitor.setTraceSampler(any()) } answers { sampler.set(firstArg()) }
        every { monitor.trace(any(), *anyVararg()) } answers {
            if (firstArg<String>() == "session_capacity") {
                capacity.set(secondArg<Array<out Pair<String, Any?>>>().toMap())
            }
            Unit
        }
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), data) }.build()
        ParallelDownloadSession(client, config, source(), monitor).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            reader.open(spec(0, 4096))
            sampler.get().invoke()
            val before = capacity.get()["storage"] as Map<*, *>
            assertEquals(4096L, before["knownBytes"])
            assertEquals(1, before["knownArrays"])
            assertEquals(4096L, before["cacheBytes"])
            assertEquals(4096L, before["readerBytes"])
            assertContentEquals(data.copyOfRange(0, 4096), readAll(reader))
            sampler.get().invoke()
            val after = capacity.get()["storage"] as Map<*, *>
            assertEquals(0L, capacity.get()["payloadUsed"])
            assertEquals(4096L, after["knownBytes"])
            assertEquals(0L, after["consumedCurrentBytes"])
            assertEquals(0L, after["readerBytes"])
            reader.close()
            sampler.get().invoke()
            val closed = capacity.get()["storage"] as Map<*, *>
            assertEquals(4096L, closed["knownBytes"])
            assertEquals(0L, closed["readerBytes"])
        }
    }

    @Test
    fun `completed bytes survive reader close and unaligned reopen without another HTTP request`() {
        val data = payload(4 * BLOCK)
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    response(chain.request(), data)
                }.build()
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            assertEquals(BLOCK.toLong(), reader.open(spec(0, BLOCK.toLong())))
            assertContentEquals(data.copyOfRange(0, BLOCK), readAll(reader))
            reader.close()
            assertEquals(1, requests.get())
            assertEquals(5000L, reader.open(spec(123, 5000)))
            assertContentEquals(data.copyOfRange(123, 5123), readAll(reader))
            reader.close()
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `seek retains overlapping in flight range but cancels earlier and far future work`() {
        val data = payload(4 * BLOCK)
        val monitor = monitor(data.size)
        every { monitor.seekWindow(DownloadTrack.Video, 1000L, any()) } returns
            (2L * BLOCK until 3L * BLOCK)
        val pendingStarted = CountDownLatch(3)
        val obsoleteCancelled = CountDownLatch(2)
        val releaseRetained = CountDownLatch(1)
        val retained = AtomicReference<Call>()
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = requestRange(chain.request())
                    ranges.add(range)
                    if (range.first > 0) {
                        if (range.first == 2L * BLOCK) retained.set(chain.call())
                        pendingStarted.countDown()
                        if (range.first == 2L * BLOCK) {
                            assertTrue(releaseRetained.await(5, TimeUnit.SECONDS))
                            assertFalse(chain.call().isCanceled())
                        } else {
                            awaitCancelled(chain.call())
                            obsoleteCancelled.countDown()
                            throw IOException("Obsolete seek range cancelled")
                        }
                    }
                    response(chain.request(), data)
                }.build()
        val worker = Executors.newSingleThreadExecutor()
        try {
            ParallelDownloadSession(client, config, source(), monitor).use { session ->
                val reader = session.factory(track, fallback).createDataSource()
                reader.open(spec(0))
                assertTrue(pendingStarted.await(3, TimeUnit.SECONDS))
                val retainedCall = assertNotNull(retained.get())
                session.prepareSeek(1000L)
                reader.close()
                assertTrue(obsoleteCancelled.await(2, TimeUnit.SECONDS))
                assertFalse(retainedCall.isCanceled())
                val opening = CountDownLatch(1)
                val result =
                    worker.submit<ByteArray> {
                        opening.countDown()
                        assertEquals(1000L, reader.open(spec(2L * BLOCK + 123, 1000)))
                        readAll(reader)
                    }
                assertTrue(opening.await(1, TimeUnit.SECONDS))
                assertFalse(retainedCall.isCanceled())
                releaseRetained.countDown()
                assertContentEquals(
                    data.copyOfRange(2 * BLOCK + 123, 2 * BLOCK + 1123),
                    result.get(3, TimeUnit.SECONDS),
                )
                reader.close()
                val requested = synchronized(ranges) { ranges.toList() }
                assertEquals(4, requested.size)
                assertEquals(1, requested.count { 2L * BLOCK + 123 in it })
            }
        } finally {
            releaseRetained.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `future retained block spanning new partitions is consumed without duplicating its suffix`() {
        val data = payload(3 * BLOCK)
        val monitor = monitor(data.size)
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>()
            start..minOf(data.lastIndex.toLong(), start + 2L * BLOCK - 1)
        }
        every { monitor.seekWindow(DownloadTrack.Video, 1000L, any()) } returns (0L until data.size.toLong())
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val range = requestRange(chain.request())
                    ranges.add(range)
                    if (range == BLOCK.toLong() until 3L * BLOCK) {
                        originalStarted.countDown()
                        assertTrue(releaseOriginal.await(5, TimeUnit.SECONDS))
                        assertFalse(chain.call().isCanceled())
                    }
                    response(chain.request(), data)
                }.build()
        try {
            ParallelDownloadSession(client, config.copy(minimumBlockKiB = 128), source(), monitor).use { session ->
                val reader = session.factory(track, fallback).createDataSource()
                reader.open(spec(0))
                assertTrue(originalStarted.await(3, TimeUnit.SECONDS))
                session.prepareSeek(1000L)
                reader.close()
                // Newly discovered segment boundaries split the retained 128 KiB request
                // into two 64 KiB planned pieces. Its ownership must still cover both.
                every { monitor.segmentBounds(any(), any()) } answers {
                    val start = secondArg<Long>() / BLOCK * BLOCK
                    start..minOf(data.lastIndex.toLong(), start + BLOCK - 1)
                }
                reader.open(spec(0))
                releaseOriginal.countDown()
                assertContentEquals(data, readAll(reader))
                reader.close()
                assertEquals(listOf(0L until BLOCK.toLong(), BLOCK.toLong() until 3L * BLOCK), ranges.toList())
            }
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `cached bytes are not reused across representation identities or playback sessions`() {
        val data = payload(2 * BLOCK)
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    response(chain.request(), data)
                }.build()
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val original = session.factory(track, fallback).createDataSource()
            original.open(spec(0, BLOCK.toLong()))
            assertContentEquals(data.copyOfRange(0, BLOCK), readAll(original))
            original.close()
            val other = session.factory(track.copy(id = "different-quality"), fallback).createDataSource()
            other.open(spec(17, 1000))
            assertContentEquals(data.copyOfRange(17, 1017), readAll(other))
            other.close()
            assertEquals(2, requests.get())
        }
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            reader.open(spec(17, 1000))
            assertContentEquals(data.copyOfRange(17, 1017), readAll(reader))
            reader.close()
            assertEquals(3, requests.get())
        }
    }

    @Test
    fun `completed read ahead extends while memory fits and unread completion survives seek reuse`() {
        val data = payload(32 * BLOCK)
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    response(chain.request(), data)
                }.build()
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            reader.open(spec(0))
            val owner =
                requireNotNull(
                    reader.javaClass
                        .getDeclaredField("reader")
                        .apply { isAccessible = true }
                        .get(reader),
                )
            val refill = owner.javaClass.getDeclaredMethod("fillWindow").apply { isAccessible = true }
            val pending =
                owner.javaClass
                    .getDeclaredField("pending")
                    .apply { isAccessible = true }
                    .get(owner) as java.util.ArrayDeque<*>
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                refill.invoke(owner)
                val done =
                    synchronized(pending) {
                        pending.size == 31 &&
                            pending.all {
                                val future =
                                    it!!
                                        .javaClass
                                        .getDeclaredField("future")
                                        .apply { isAccessible = true }
                                        .get(it) as java.util.concurrent.Future<*>
                                future.isDone
                            }
                    }
                if (done) break
                assertTrue(
                    System.nanoTime() < until,
                    "Available memory did not permit completing the finite read-ahead range",
                )
                Thread.sleep(10)
            }
            val completedRequests = requests.get()
            assertTrue(
                completedRequests > config.maxRequests,
                "Completed blocks must not impose an old count-based window",
            )
            // No bytes were delivered from this reader: close transfers completed storage to cache.
            reader.close()
            val seekStart = 13L * BLOCK + 17
            val seekLength = BLOCK + 100L
            reader.open(spec(seekStart, seekLength))
            assertContentEquals(data.copyOfRange(seekStart.toInt(), (seekStart + seekLength).toInt()), readAll(reader))
            assertEquals(completedRequests, requests.get(), "Seeking into completed unread storage must reuse it")
            reader.close()
        }
    }

    @Test
    fun `cancelled socket retains byte reservation until worker acknowledges cancellation`() {
        val data = payload(2 * BLOCK)
        val started = CountDownLatch(1)
        val permitExit = CountDownLatch(1)
        val sampler = AtomicReference<() -> Unit>({})
        val capacity = AtomicReference<Map<String, Any?>>(emptyMap())
        val monitor = monitor(data.size)
        every { monitor.traceEnabled } returns true
        every { monitor.setTraceSampler(any()) } answers { sampler.set(firstArg()) }
        every { monitor.trace(any(), *anyVararg()) } answers {
            if (firstArg<String>() == "session_capacity") {
                capacity.set(secondArg<Array<out Pair<String, Any?>>>().toMap())
            }
            Unit
        }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (requestRange(chain.request()).first > 0) {
                        started.countDown()
                        // Simulate a socket which acknowledges cancellation late.
                        while (permitExit.count > 0) {
                            try {
                                permitExit.await(20, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                            }
                        }
                        throw IOException("Cancelled socket exited")
                    }
                    response(chain.request(), data)
                }.build()
        try {
            ParallelDownloadSession(client, config, source(), monitor).use { session ->
                val reader = session.factory(track, fallback).createDataSource()
                reader.open(spec(0))
                assertTrue(started.await(3, TimeUnit.SECONDS))
                reader.close()
                sampler.get().invoke()
                assertTrue((capacity.get()["payloadUsed"] as Long) > 0)
                permitExit.countDown()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                do {
                    sampler.get().invoke()
                    if (capacity.get()["payloadUsed"] == 0L) break
                    Thread.sleep(10)
                } while (System.nanoTime() < until)
                assertEquals(0L, capacity.get()["payloadUsed"])
            }
        } finally {
            permitExit.countDown()
        }
    }

    @Test
    fun `ordinary reader close without seek hint cancels in flight requests`() {
        val data = payload(2 * BLOCK)
        val pendingStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (requestRange(chain.request()).first > 0) {
                        pendingStarted.countDown()
                        awaitCancelled(chain.call())
                        cancelled.countDown()
                        throw IOException("Closed reader")
                    }
                    response(chain.request(), data)
                }.build()
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            reader.open(spec(0))
            assertTrue(pendingStarted.await(3, TimeUnit.SECONDS))
            reader.close()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `reading across cached prefix and suffix downloads only the intervening gap`() {
        val data = payload(3 * BLOCK)
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    ranges.add(requestRange(chain.request()))
                    response(chain.request(), data)
                }.build()
        ParallelDownloadSession(client, config, source(), monitor(data.size)).use { session ->
            val reader = session.factory(track, fallback).createDataSource()
            reader.open(spec(0, BLOCK.toLong()))
            assertContentEquals(data.copyOfRange(0, BLOCK), readAll(reader))
            reader.close()
            reader.open(spec(2L * BLOCK, BLOCK.toLong()))
            assertContentEquals(data.copyOfRange(2 * BLOCK, 3 * BLOCK), readAll(reader))
            reader.close()
            assertEquals(2, ranges.size)
            assertEquals(3L * BLOCK, reader.open(spec(0, 3L * BLOCK)))
            assertContentEquals(data, readAll(reader))
            reader.close()
            val requested = synchronized(ranges) { ranges.toList() }
            assertEquals(
                listOf(0L until BLOCK.toLong(), BLOCK.toLong() until 2L * BLOCK, 2L * BLOCK until 3L * BLOCK),
                requested.sortedBy { it.first },
            )
        }
    }

    @Test
    fun `failed retained range is fetched afresh instead of reusing its failed result`() {
        val data = payload(2 * BLOCK)
        val monitor = monitor(data.size)
        every { monitor.seekWindow(DownloadTrack.Video, 1000L, any()) } returns
            (BLOCK.toLong() until 2L * BLOCK)
        val originalStarted = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val failed = CountDownLatch(1)
        every { monitor.state(any(), DownloadBlockState.Failed) } answers {
            failed.countDown()
        }
        val attempts = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (requestRange(chain.request()).first == BLOCK.toLong() && attempts.incrementAndGet() == 1) {
                        originalStarted.countDown()
                        assertTrue(releaseFailure.await(5, TimeUnit.SECONDS))
                        assertFalse(chain.call().isCanceled())
                        throw IOException("Retained range failed after seek")
                    }
                    response(chain.request(), data)
                }.build()
        try {
            ParallelDownloadSession(client, config, source(), monitor).use { session ->
                val reader = session.factory(track, fallback).createDataSource()
                reader.open(spec(0))
                assertTrue(originalStarted.await(3, TimeUnit.SECONDS))
                session.prepareSeek(1000L)
                reader.close()
                releaseFailure.countDown()
                // The failure is observed before reopening; cleanup may still be finishing,
                // so adopting a just-failed retained Future must also recover correctly.
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertEquals(BLOCK.toLong(), reader.open(spec(BLOCK.toLong(), BLOCK.toLong())))
                assertContentEquals(data.copyOfRange(BLOCK, 2 * BLOCK), readAll(reader))
                reader.close()
                assertEquals(2, attempts.get())
            }
        } finally {
            releaseFailure.countDown()
        }
    }

    @Test
    fun `repeated seek handoffs reuse one in flight range then release old readers into cache`() {
        val data = payload(2 * BLOCK)
        val monitor = monitor(data.size)
        every { monitor.seekWindow(DownloadTrack.Video, 1000L, any()) } returns
            (BLOCK.toLong() until 2L * BLOCK)
        val sampler = AtomicReference<() -> Unit>({})
        every { monitor.traceEnabled } returns true
        every { monitor.setTraceSampler(any()) } answers { sampler.set(firstArg()) }
        val capacity = AtomicReference<Map<String, Any?>>(emptyMap())
        val waits = List(2) { CountDownLatch(1) }
        val waitCount = AtomicInteger()
        val cacheHits = AtomicInteger()
        every { monitor.trace(any(), *anyVararg()) } answers {
            when (firstArg<String>()) {
                "seek_reuse_wait" -> waits.getOrNull(waitCount.getAndIncrement())?.countDown()
                "session_capacity" -> capacity.set(secondArg<Array<out Pair<String, Any?>>>().toMap())
                "cache_hit" -> cacheHits.incrementAndGet()
            }
            Unit
        }
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val originalCall = AtomicReference<Call>()
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    if (requestRange(chain.request()).first > 0) {
                        originalCall.set(chain.call())
                        originalStarted.countDown()
                        assertTrue(releaseOriginal.await(5, TimeUnit.SECONDS))
                        assertFalse(chain.call().isCanceled())
                    }
                    response(chain.request(), data)
                }.build()
        val workers = Executors.newFixedThreadPool(2)
        try {
            ParallelDownloadSession(client, config, source(), monitor).use { session ->
                val reader = session.factory(track, fallback).createDataSource()
                reader.open(spec(0))
                assertTrue(originalStarted.await(3, TimeUnit.SECONDS))
                val call = assertNotNull(originalCall.get())
                session.prepareSeek(1000L)
                reader.close()
                val abandonedOpens =
                    (0..1).map { index ->
                        val opening =
                            workers.submit<Result<Long>> {
                                runCatching { reader.open(spec(BLOCK + index * 17L, BLOCK - index * 17L)) }
                            }
                        // This trace is emitted before waiting on the retained Future, after the new
                        // reader has recorded its awaited range and can safely hand it back.
                        assertTrue(waits[index].await(2, TimeUnit.SECONDS))
                        session.prepareSeek(1000L)
                        reader.close()
                        assertFalse(call.isCanceled())
                        opening
                    }
                releaseOriginal.countDown()
                abandonedOpens.forEach { assertTrue(it.get(3, TimeUnit.SECONDS).isFailure) }
                val cacheDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

                fun cached(): Boolean {
                    sampler.get().invoke()
                    return (capacity.get()["cacheBytes"] as? Number)?.toLong() == 2L * BLOCK
                }
                while (!cached() && System.nanoTime() < cacheDeadline) Thread.sleep(5)
                assertTrue(cached(), "Retained completion was not transferred to cache")
                assertEquals(5000L, reader.open(spec(BLOCK + 123L, 5000)))
                assertContentEquals(data.copyOfRange(BLOCK + 123, BLOCK + 5123), readAll(reader))
                reader.close()
                assertEquals(2, requests.get())
                assertTrue(cacheHits.get() > 0)
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

                fun drained(): Boolean {
                    sampler.get().invoke()
                    val values = capacity.get()
                    return listOf(
                        "readers",
                        "payloadUsed",
                        "plannedWork",
                        "coordinatorActive",
                        "coordinatorQueued",
                        "headCoordinatorActive",
                        "headCoordinatorQueued",
                    ).all { (values[it] as? Number)?.toLong() == 0L }
                }
                while (!drained() && System.nanoTime() < cleanupDeadline) Thread.sleep(5)
                assertTrue(drained(), "Old reader or payload ownership remains: ${capacity.get()}")
                assertEquals(2L * BLOCK, (capacity.get()["cacheBytes"] as Number).toLong())
            }
        } finally {
            releaseOriginal.countDown()
            workers.shutdownNow()
        }
    }

    private fun source() = VodPlaybackSource("test", track, null)

    private fun monitor(size: Int): DownloadMonitor {
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.snapshots } returns kotlinx.coroutines.flow.MutableStateFlow(DownloadSnapshot())
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns false
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>() / BLOCK * BLOCK
            start..minOf(size - 1L, start + BLOCK - 1)
        }
        every { monitor.seekWindow(any(), any(), any()) } returns null
        return monitor
    }

    private fun spec(
        start: Long,
        length: Long? = null,
    ): DataSpec {
        val uri = mockk<Uri>()
        every { uri.toString() } returns url
        val builder = DataSpec.Builder().setUri(uri).setPosition(start)
        if (length != null) builder.setLength(length)
        return builder.build()
    }

    private fun requestRange(request: Request): LongRange {
        val bounds = requireNotNull(request.header("Range")).removePrefix("bytes=").split("-").map(String::toLong)
        return bounds[0]..bounds[1]
    }

    private fun response(
        request: Request,
        data: ByteArray,
    ): Response {
        val range = requestRange(request)
        val end = minOf(range.last, data.lastIndex.toLong())
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("Content-Range", "bytes ${range.first}-$end/${data.size}")
            .body(data.copyOfRange(range.first.toInt(), end.toInt() + 1).toResponseBody())
            .build()
    }

    private fun awaitCancelled(call: Call) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!call.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(call.isCanceled())
    }

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun readAll(reader: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = reader.read(buffer, 0, buffer.size)
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
        }
    }

    private companion object {
        const val BLOCK = 65536
    }
}

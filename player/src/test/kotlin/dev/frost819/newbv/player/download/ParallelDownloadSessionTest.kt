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
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Deterministic transport coverage using an in-process OkHttp response interceptor. */
class ParallelDownloadSessionTest {
    private val url = "https://media.example.com/video"
    private val track = MediaTrackSource("v", DownloadTrack.Video, listOf(url))
    private val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)

    @BeforeEach
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    private fun spec(start: Long = 0): DataSpec {
        val uri = mockk<Uri>()
        every { uri.toString() } returns url
        return DataSpec
            .Builder()
            .setUri(uri)
            .setPosition(start)
            .build()
    }

    @Test
    fun `transport provenance survives cache replay and counts a fresh download after eviction`() {
        val bytes = ByteArray(98_304) { (it % 251).toByte() }
        val requests = AtomicInteger()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        val ids = AtomicLong()
        every { monitor.plan(any(), any(), any()) } answers { ids.incrementAndGet() }
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    val bounds =
                        requireNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split("-")
                            .map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], bytes.lastIndex)
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
        val session = ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor)
        val ledger = transferLedger(session)
        try {
            val factory = session.factory(track, DataSource.Factory { error("Unexpected fallback") })

            fun consume() {
                val reader = factory.createDataSource()
                try {
                    assertEquals(bytes.size.toLong(), reader.open(spec()))
                    assertContentEquals(bytes, readAll(reader))
                } finally {
                    reader.close()
                }
            }
            consume()
            every { monitor.hasStartedPlayback() } returns true
            val first = ledger.snapshot()
            assertEquals(bytes.size.toLong(), first.downloadedBytes)
            assertEquals(bytes.size.toLong(), first.usedBytes)
            assertEquals(0L, first.pendingBytes)
            assertEquals(0L, first.discardedBytes)
            val firstRequests = requests.get()
            consume()
            assertEquals(firstRequests, requests.get(), "Backward cache replay must not issue a new request")
            assertEquals(first.downloadedBytes, ledger.snapshot().downloadedBytes)
            assertEquals(first.usedBytes, ledger.snapshot().usedBytes)
            val cache =
                session.javaClass
                    .getDeclaredField("cache")
                    .apply { isAccessible = true }
                    .get(session) as RangeBlockCache
            assertEquals(bytes.size.toLong(), cache.evictUnread(track, 0, bytes.size.toLong()))
            consume()
            assertEquals(bytes.size * 2L, ledger.snapshot().downloadedBytes)
            assertEquals(bytes.size * 2L, ledger.snapshot().usedBytes)
            assertEquals(0L, ledger.snapshot().discardedBytes)
        } finally {
            session.close()
        }
        awaitTransferSettlement(ledger)
        val settled = ledger.snapshot()
        assertEquals(0L, settled.pendingBytes)
        assertEquals(0L, settled.discardedBytes, "Delivered cache disposal is never download overhead")
        assertEquals(settled.downloadedBytes, settled.usedBytes)
    }

    @Test
    fun `truncated transport accounts received bytes as discarded when attempts terminate`() {
        val calls = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    calls.incrementAndGet()
                    val truncated =
                        object : ResponseBody() {
                            private val input = Buffer().write(byteArrayOf(1, 2))

                            override fun contentType(): MediaType? = null

                            override fun contentLength(): Long = 10

                            override fun source(): BufferedSource = input
                        }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes 0-9/10")
                        .body(truncated)
                        .build()
                }.build()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns true
        every { monitor.deadlineNanos(any(), any()) } returns null
        val session = ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor)
        val ledger = transferLedger(session)
        val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
        try {
            assertFailsWith<IOException> { reader.open(spec()) }
        } finally {
            reader.close()
            session.close()
        }
        awaitTransferSettlement(ledger)
        val settled = ledger.snapshot()
        assertTrue(calls.get() > 0)
        assertEquals(calls.get() * 2L, settled.downloadedBytes)
        assertEquals(0L, settled.usedBytes)
        assertEquals(0L, settled.pendingBytes)
        assertEquals(settled.downloadedBytes, settled.discardedBytes)
    }

    private fun transferLedger(session: ParallelDownloadSession): ExactTransferLedger =
        session.javaClass
            .getDeclaredField("transferLedger")
            .apply { isAccessible = true }
            .get(session) as ExactTransferLedger

    private fun awaitTransferSettlement(ledger: ExactTransferLedger) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (ledger.snapshot().liveSourceCount != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(0, ledger.snapshot().liveSourceCount, "All terminated network and cache owners must settle")
    }

    @Test
    fun `later ranges keep downloading beyond worker count while first range stalls and memory fits`() {
        val size = 1024 * 1024
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val later = CountDownLatch(2)
        val excess = CountDownLatch(1)
        val laterCount = AtomicInteger()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>()
            start..minOf(size - 1L, start + 65535)
        }
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val bounds =
                        requireNotNull(
                            chain.request().header("Range"),
                        ).removePrefix("bytes=").split("-").map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], size - 1)
                    if (start == 65536) {
                        stalled.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    } else if (start > 65536) {
                        later.countDown()
                        if (laterCount.incrementAndGet() > config.maxRequests) excess.countDown()
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(bytes.copyOfRange(start, end + 1).toResponseBody())
                        .build()
                }.build()
        val worker = Executors.newSingleThreadExecutor()
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = 4),
                VodPlaybackSource("test", null, null),
                monitor,
            ).use { session ->
                val reader =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                reader.open(spec())
                val loaded =
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
                assertTrue(stalled.await(2, TimeUnit.SECONDS))
                assertTrue(later.await(2, TimeUnit.SECONDS))
                assertTrue(
                    excess.await(3, TimeUnit.SECONDS),
                    "Available memory must admit more than a fixed worker-count window",
                )
                assertTrue(!loaded.isDone, "The first missing range must still block ordered delivery")
                release.countDown()
                assertContentEquals(bytes, loaded.get(3, TimeUnit.SECONDS))
                reader.close()
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `retired future range is downloaded again in order without reusing stale cached bytes`() {
        val blockSize = 65_536
        val bytes = ByteArray(blockSize * 12) { (it % 251).toByte() }
        val victimStart = blockSize * 5
        val victimRequests = AtomicInteger()
        val blockIds = AtomicLong()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.plan(any(), any(), any()) } answers { blockIds.incrementAndGet() }
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>()
            start..minOf(bytes.lastIndex.toLong(), start + blockSize - 1)
        }
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val bounds =
                        requireNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split("-")
                            .map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], bytes.lastIndex)
                    val body = bytes.copyOfRange(start, end + 1)
                    if (start == victimStart && victimRequests.incrementAndGet() == 1) {
                        // A distinguishable old body makes accidental reuse observable, not just a request-count check.
                        body.fill(42)
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes $start-$end/${bytes.size}")
                        .body(body.toResponseBody())
                        .build()
                }.build()
        val worker = Executors.newSingleThreadExecutor()
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
                val reader =
                    session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
                try {
                    assertEquals(bytes.size.toLong(), reader.open(spec()))
                    val owner =
                        requireNotNull(
                            reader.javaClass
                                .getDeclaredField("reader")
                                .apply { isAccessible = true }
                                .get(reader),
                        )
                    val candidates = owner.javaClass.getDeclaredMethod("recoveryVictims").apply { isAccessible = true }
                    val retire = owner.javaClass.declaredMethods.single { it.name == "retireForMemory" }
                    retire.isAccessible = true
                    var victim: Any? = null
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (victim == null && System.nanoTime() < deadline) {
                        val pending = candidates.invoke(owner) as List<*>
                        victim =
                            pending.map { (it as Pair<*, *>).second }.firstOrNull { candidate ->
                                val item = requireNotNull(candidate)
                                val start = item.javaClass.getDeclaredField("start").apply { isAccessible = true }
                                val future = item.javaClass.getDeclaredField("future").apply { isAccessible = true }
                                start.getLong(item) == victimStart.toLong() &&
                                    (future.get(item) as java.util.concurrent.Future<*>).isDone
                            }
                        if (victim == null) Thread.sleep(10)
                    }
                    val readyVictim = requireNotNull(victim) { "Expected a completed far-future recovery victim" }
                    assertEquals(1, victimRequests.get())
                    assertEquals(true, retire.invoke(owner, readyVictim))
                    val loaded = worker.submit<ByteArray> { readAll(reader) }
                    assertContentEquals(bytes, loaded.get(5, TimeUnit.SECONDS))
                    assertEquals(2, victimRequests.get(), "Retired range must be fetched again, not skipped or cached")
                } finally {
                    reader.close()
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `repeated memory recovery waits for canceled farthest worker to release its allocation`() {
        val blockSize = 65_536
        val data = ByteArray(blockSize * 4)
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val calls = ConcurrentHashMap<Int, Call>()
        val blockIds = AtomicLong()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.plan(any(), any(), any()) } answers { blockIds.incrementAndGet() }
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>()
            start..minOf(data.lastIndex.toLong(), start + blockSize - 1)
        }
        every { monitor.schedulingTimeMs(any(), any()) } answers { secondArg<Long>() }
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val bounds =
                        requireNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split("-")
                            .map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], data.lastIndex)
                    if (start > 0) {
                        calls[start] = chain.call()
                        started.countDown()
                        // Simulate a socket/body reader which has received cancellation but has
                        // not returned yet. Its owned allocation must remain charged until exit.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                        while (release.count > 0 && System.nanoTime() < deadline) {
                            try {
                                release.await(10, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                // Intentionally hold the attempt until the test releases it.
                            }
                        }
                        check(release.count == 0L) { "Test never released held HTTP requests" }
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
                }.build()
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
                val reader =
                    session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
                try {
                    reader.open(spec())
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    val owner =
                        requireNotNull(
                            reader.javaClass
                                .getDeclaredField("reader")
                                .apply { isAccessible = true }
                                .get(reader),
                        )
                    val budget =
                        session.javaClass
                            .getDeclaredField("memoryBudget")
                            .apply { isAccessible = true }
                            .get(session) as DownloadMemoryBudget
                    val recovery = session.javaClass.declaredMethods.single { it.name == "reclaimForRecovery" }
                    recovery.isAccessible = true
                    val key = Any()
                    budget.refreshMemory(DownloadMemorySnapshot(0, 0, 0, 64L * 1024 * 1024, 0, true))
                    budget.claimRecovery(key, blockSize.toLong())
                    val charged = budget.snapshot().sharedUsedBytes
                    assertTrue(budget.recoveryShortfall(blockSize.toLong(), key) > 0)
                    recovery.invoke(session, key, blockSize.toLong(), owner, blockSize.toLong())
                    val farthest = requireNotNull(calls[blockSize * 3])
                    assertTrue(farthest.isCanceled(), "Memory recovery must pick the farthest active range")
                    assertEquals(charged, budget.snapshot().sharedUsedBytes, "Cancellation is not allocation release")
                    recovery.invoke(session, key, blockSize.toLong(), owner, blockSize.toLong())
                    assertEquals(1, calls.values.count { it.isCanceled() }, "Do not cascade retirement before release")
                    assertEquals(charged, budget.snapshot().sharedUsedBytes)
                } finally {
                    release.countDown()
                    reader.close()
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `expired capacity wait throws read error releases claim and does not restart transport`() {
        val bytes = byteArrayOf(3, 1, 4, 1)
        val requests = AtomicInteger()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes 0-3/4")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            try {
                assertEquals(4L, reader.open(spec()))
                val owner =
                    requireNotNull(
                        reader.javaClass
                            .getDeclaredField("reader")
                            .apply { isAccessible = true }
                            .get(reader),
                    )
                val budget =
                    session.javaClass
                        .getDeclaredField("memoryBudget")
                        .apply { isAccessible = true }
                        .get(session) as DownloadMemoryBudget
                val key =
                    requireNotNull(
                        owner.javaClass
                            .getDeclaredField("memoryDemandKey")
                            .apply { isAccessible = true }
                            .get(owner),
                    )
                budget.claimRecovery(key, 65_536L)
                assertEquals(131_072L, budget.capacityFields()["pendingRecoveryBytes"])
                val occupied = budget.usage()
                owner.javaClass
                    .getDeclaredField("memoryDeniedSince")
                    .apply { isAccessible = true }
                    .setLong(owner, System.nanoTime() - TimeUnit.SECONDS.toNanos(31))
                val wait = owner.javaClass.getDeclaredMethod("waitForPlanning").apply { isAccessible = true }
                val failure = assertFailsWith<InvocationTargetException> { wait.invoke(owner) }
                assertIs<DownloadCapacityException>(failure.cause)
                assertEquals(0L, budget.capacityFields()["pendingRecoveryBytes"])
                assertEquals(occupied, budget.usage(), "Timing out must not release still-owned payload memory")
                assertEquals(1, requests.get(), "Capacity timeout must not automatically restart transport")
                assertContentEquals(bytes, readAll(reader), "Existing downloaded data is preserved for caller recovery")
                assertEquals(1, requests.get())
            } finally {
                reader.close()
            }
        }
    }

    @Test
    fun `immediate original borrows recovery reserve and releases its tracked ownership`() {
        val bytes = byteArrayOf(2, 7, 1, 8)
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns true
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes 0-3/4")
                        .body(bytes.toResponseBody())
                        .build()
                }.build()
        ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            try {
                reader.open(spec())
                assertContentEquals(bytes, readAll(reader))
                val owner =
                    requireNotNull(
                        reader.javaClass
                            .getDeclaredField("reader")
                            .apply { isAccessible = true }
                            .get(reader),
                    )
                val budget =
                    session.javaClass
                        .getDeclaredField("memoryBudget")
                        .apply { isAccessible = true }
                        .get(session) as DownloadMemoryBudget
                val ordinaryBytes = (budget.sharedLimitBytes - budget.snapshot().sharedUsedBytes) / 2
                assertTrue(budget.tryReservePayload(ordinaryBytes))
                try {
                    val neededBytes = 65_536L
                    assertTrue(!budget.tryReservePayload(neededBytes), "Ordinary admission must be exhausted")
                    assertTrue(budget.canReserveRescue(neededBytes), "Protected recovery capacity must remain")
                    val before = budget.usage()
                    // Invoke the actual demand admission at the reader's current position after
                    // its previous body was consumed. Network planning is outside this test.
                    val reserve =
                        owner.javaClass
                            .getDeclaredMethod(
                                "reservePayload",
                                Long::class.javaPrimitiveType,
                                Long::class.javaPrimitiveType,
                                Long::class.javaPrimitiveType,
                            ).apply { isAccessible = true }
                    assertEquals(true, reserve.invoke(owner, 4L, neededBytes, 0L))
                    val keys =
                        owner.javaClass
                            .getDeclaredField("recoveryPayloadKeys")
                            .apply { isAccessible = true }
                            .get(owner) as Map<*, *>
                    assertTrue(keys.containsKey(4L))
                    assertEquals(before.first + neededBytes * 2, budget.usage().first)
                    assertEquals(before.second, budget.usage().second, "Original must not be charged as duplicate too")
                    assertEquals(neededBytes * 2, budget.capacityFields()["recoveryPayloadBytes"])
                    assertEquals(0L, budget.capacityFields()["pendingRecoveryBytes"])
                    owner.javaClass
                        .getDeclaredMethod("releasePayload", Long::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(owner, 4L)
                    assertTrue(!keys.containsKey(4L))
                    assertEquals(before, budget.usage())
                    assertEquals(0L, budget.capacityFields()["recoveryPayloadBytes"])
                } finally {
                    budget.releasePayload(ordinaryBytes)
                }
            } finally {
                reader.close()
            }
        }
    }

    @Test
    fun `required audio data and video can proceed alongside a startup probe`() {
        val audio = MediaTrackSource("a", DownloadTrack.Audio, listOf("https://media.example.com/audio"))
        val firstAudio = CountDownLatch(1)
        val secondAudio = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val audioStarts = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (chain.request().url.encodedPath == "/audio") {
                        if (audioStarts.incrementAndGet() == 1) {
                            firstAudio.countDown()
                            assertTrue(releaseFirst.await(4, TimeUnit.SECONDS))
                        } else {
                            secondAudio.countDown()
                        }
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 0-3/4")
                        .body(byteArrayOf(1, 2, 3, 4).toResponseBody())
                        .build()
                }.build()
        val monitor = DownloadMonitor(config)
        val workers = Executors.newFixedThreadPool(2)
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", track, audio), monitor).use { session ->
                fun openAudio(): Long {
                    val reader =
                        session
                            .factory(
                                audio,
                                DataSource.Factory { error("Unexpected fallback") },
                            ).createDataSource()
                    val uri = mockk<Uri>()
                    every { uri.toString() } returns audio.urls.first()
                    return try {
                        reader.open(DataSpec.Builder().setUri(uri).build())
                    } finally {
                        reader.close()
                    }
                }
                val first = workers.submit<Long> { openAudio() }
                assertTrue(firstAudio.await(2, TimeUnit.SECONDS))
                val second = workers.submit<Long> { openAudio() }
                assertTrue(secondAudio.await(2, TimeUnit.SECONDS))
                val video =
                    session
                        .factory(
                            track,
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                assertEquals(4L, video.open(spec()))
                video.close()
                monitor.updatePlayback(0, 1f, true, true, false)
                assertTrue(secondAudio.await(2, TimeUnit.SECONDS))
                releaseFirst.countDown()
                assertEquals(4L, first.get(2, TimeUnit.SECONDS))
                assertEquals(4L, second.get(2, TimeUnit.SECONDS))
            }
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            monitor.close()
        }
    }

    @Test
    fun `out of order ranges deliver exact bytes and reader reopens at seek`() {
        val data = ByteArray(2_500_000) { (it % 251).toByte() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val secondStarted = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val values =
                        requireNotNull(chain.request().header("Range"))
                            .removePrefix("bytes=")
                            .split("-")
                            .map(String::toInt)
                    val start = values[0]
                    val end = minOf(values[1], data.lastIndex)
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    try {
                        if (start == 65_536) {
                            firstStarted.countDown()
                            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
                        }
                        if (start == 65_536 + RangePartition.UNKNOWN_INDEX_BYTES.toInt()) {
                            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))
                            secondStarted.countDown()
                        }
                        Response
                            .Builder()
                            .request(
                                chain.request(),
                            ).protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes $start-$end/${data.size}")
                            .body(data.copyOfRange(start, end + 1).toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                    }
                }.build()
        val capture = DownloadTraceStore().apply { setEnabled(true) }
        val monitor = DownloadMonitor(config, traceStore = capture)
        ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            assertEquals(data.size.toLong(), reader.open(spec()))
            assertContentEquals(data, readAll(reader))
            reader.close()
            assertEquals(data.size - 700_000L, reader.open(spec(700_000)))
            assertContentEquals(data.copyOfRange(700_000, data.size), readAll(reader))
            reader.close()
        }
        monitor.close()
        val events = capture.snapshot().events
        assertTrue(events.any { it.type == "attempt_start" && it.fields["host"] == "media.example.com" })
        assertTrue(events.any { it.type == "attempt_end" && it.fields["outcome"] == "complete" })
        assertTrue(events.any { it.type == "scheduler" && "globalSlotsFree" in it.fields })
        assertTrue(events.none { it.toString().contains(url) })
        assertTrue(peak.get() >= 2)
        assertTrue(peak.get() <= 4)
    }

    @Test
    fun `eight request budget schedules seven video ranges without audio`() {
        val settings = config.copy(maxRequests = 8)
        val started = CountDownLatch(7)
        val size = 9_000_000
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val bounds =
                        requireNotNull(
                            chain.request().header("Range"),
                        ).removePrefix("bytes=").split("-").map(String::toInt)
                    val start = bounds[0]
                    val end = minOf(bounds[1], size - 1)
                    if (start > 0) {
                        started.countDown()
                        assertTrue(started.await(3, TimeUnit.SECONDS))
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray(end - start + 1).toResponseBody())
                        .build()
                }.build()
        val monitor = DownloadMonitor(settings)
        ParallelDownloadSession(client, settings, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            reader.open(spec())
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertEquals(size, readAll(reader).size)
            reader.close()
        }
        monitor.close()
    }

    @Test
    fun `truncated body is rejected before bytes reach consumer`() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 0-9/10")
                        .body(byteArrayOf(1, 2).toResponseBody())
                        .build()
                }.build()
        val monitor = DownloadMonitor(config)
        ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            kotlin.test.assertFailsWith<java.io.IOException> { reader.open(spec()) }
        }
        monitor.close()
    }

    @Test
    fun `closing session cancels compatibility request while open is pending`() {
        val fallbackStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (calls.incrementAndGet() == 1) {
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ByteArray(16).toResponseBody())
                            .build()
                    } else {
                        fallbackStarted.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                        if (!chain.call().isCanceled()) throw AssertionError("Fallback call survived session close")
                        throw IOException("Canceled")
                    }
                }.build()
        val monitor = DownloadMonitor(config)
        val session = ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor)
        val executor = Executors.newSingleThreadExecutor()
        mockkStatic(Uri::class)
        try {
            val uri = mockk<Uri>()
            every { uri.toString() } returns url
            every { Uri.parse(any()) } returns uri
            val reader =
                session
                    .factory(
                        track,
                        DataSource.Factory {
                            error("Expected tracked compatibility source")
                        },
                    ).createDataSource()
            val result =
                executor.submit<Boolean> {
                    try {
                        reader.open(DataSpec.Builder().setUri(uri).build())
                        false
                    } catch (_: IOException) {
                        true
                    } finally {
                        reader.close()
                    }
                }
            assertTrue(fallbackStarted.await(3, TimeUnit.SECONDS))
            session.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally {
            session.close()
            monitor.close()
            executor.shutdownNow()
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun `closing session drains admitted HTTP attempts queued behind exiting workers`() {
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    requests.incrementAndGet()
                    throw IOException("A cancelled queued attempt must not start HTTP")
                }.build()
        val monitor = DownloadMonitor(config)
        val session = ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor)
        val httpExecutor =
            ParallelDownloadSession::class.java.getDeclaredField("requestExecutor").run {
                isAccessible = true
                get(session) as ThreadPoolExecutor
            }
        val budget =
            ParallelDownloadSession::class.java.getDeclaredField("memoryBudget").run {
                isAccessible = true
                get(session) as DownloadMemoryBudget
            }
        val ledger = transferLedger(session)
        val started = CountDownLatch(config.maxRequests)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            repeat(config.maxRequests) {
                httpExecutor.execute {
                    started.countDown()
                    val unblockBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (release.count > 0 && System.nanoTime() < unblockBy) {
                        try {
                            release.await(10, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Model an exiting wrapper still holding its physical executor thread.
                        }
                    }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val reader = session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
            val result =
                worker.submit<Boolean> {
                    try {
                        reader.open(spec())
                        false
                    } catch (_: IOException) {
                        true
                    } finally {
                        reader.close()
                    }
                }
            val queuedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (httpExecutor.queue.isEmpty() && System.nanoTime() < queuedBy) Thread.sleep(5)
            assertTrue(httpExecutor.queue.isNotEmpty(), "Startup HTTP attempt must be queued after admission")
            assertTrue(budget.usage().second > 0, "Queued startup probe must already own rescue memory")
            session.close()
            release.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertTrue(httpExecutor.awaitTermination(2, TimeUnit.SECONDS))
            val releasedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (budget.snapshot().sharedUsedBytes > 0 && System.nanoTime() < releasedBy) Thread.sleep(5)
            assertEquals(0, requests.get())
            assertEquals(0L to 0L, budget.usage())
            assertEquals(0L, budget.snapshot().sharedUsedBytes)
            assertEquals(0L, ledger.snapshot().pendingBytes)
            assertEquals(0, ledger.snapshot().liveSourceCount)
            assertEquals(0, ledger.snapshot().liveRangeCount)
        } finally {
            release.countDown()
            session.close()
            monitor.close()
            worker.shutdownNow()
        }
    }

    private fun readAll(reader: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = reader.read(buffer, 0, buffer.size)
            if (count == -1) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

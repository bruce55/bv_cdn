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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Downstream HTTP dispatch preserves demanded media-time priority, then FIFO, with no second track gate. */
class TrackDispatchTest {
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
    fun `earlier demanded audio gets next slot ahead of queued later video`() {
        exercise(DownloadTrack.Audio)
    }

    @Test
    fun `earlier demanded video gets next slot ahead of queued later audio`() {
        exercise(DownloadTrack.Video)
    }

    @Test
    fun `absent earlier demand does not idle a free slot`() {
        exercise(DownloadTrack.Audio, queuePreferred = false)
    }

    @Test
    fun `closing earlier demanded reader removes it without leaking request slots`() {
        exercise(DownloadTrack.Audio, cancelPreferred = true)
    }

    @Test
    fun `video uses spare slot while earlier demanded audio is still running`() {
        exercise(DownloadTrack.Audio, holdPreferred = true)
    }

    @Test
    fun `audio uses spare slot while earlier demanded video is still running`() {
        exercise(DownloadTrack.Video, holdPreferred = true)
    }

    @Test
    fun `equal playback demand preserves admission FIFO across tracks`() {
        exercise(DownloadTrack.Audio, equalDemand = true)
    }

    private fun exercise(
        preferred: DownloadTrack,
        queuePreferred: Boolean = true,
        cancelPreferred: Boolean = false,
        holdPreferred: Boolean = false,
        equalDemand: Boolean = false,
    ) {
        val other = if (preferred == DownloadTrack.Audio) DownloadTrack.Video else DownloadTrack.Audio
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns true
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } answers {
            if (equalDemand || firstArg<DownloadTrack>() == preferred) 0L else 10_000L
        }
        every { monitor.traceEnabled } returns true
        val aheadQueued = CountDownLatch(1)
        val preferredQueued = CountDownLatch(1)
        val readerRoles = ConcurrentHashMap<Long, String>()
        every { monitor.trace(any(), *anyVararg()) } answers {
            val fields = secondArg<Array<out Pair<String, Any?>>>().toMap()
            val reader = fields["reader"] as? Long
            if (firstArg<String>() == "reader_open" && reader != null) readerRoles[reader] = Thread.currentThread().name
            if (firstArg<String>() == "ordinary_queued") {
                when (readerRoles[reader]) {
                    "dispatch-test-ahead" -> aheadQueued.countDown()
                    "dispatch-test-preferred" -> preferredQueued.countDown()
                }
            }
        }
        val blockersStarted = CountDownLatch(3)
        val releaseFirst = CountDownLatch(1)
        val releaseAll = CountDownLatch(1)
        val releasePreferred = CountDownLatch(1)
        val preferredStarted = CountDownLatch(1)
        val aheadStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val dispatches = Collections.synchronizedList(mutableListOf<String>())
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    val start =
                        requireNotNull(
                            request.header("Range"),
                        ).removePrefix("bytes=").substringBefore('-').toInt()
                    val blocker = path.startsWith("/blocker")
                    val concurrent = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, concurrent) }
                    try {
                        if (blocker && start != 0) {
                            blockersStarted.countDown()
                            val gate = if (path == "/blocker1") releaseFirst else releaseAll
                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                            while (!gate.await(10, TimeUnit.MILLISECONDS) &&
                                !chain.call().isCanceled() &&
                                System.nanoTime() < until
                            ) {
                                Unit
                            }
                            if (chain.call().isCanceled()) throw IOException("cancelled")
                            assertEquals(0L, gate.count, "Test never released occupied request slot")
                        } else if (!blocker) {
                            dispatches.add(path)
                            if (path == "/ahead") aheadStarted.countDown()
                            if (path == "/preferred") {
                                preferredStarted.countDown()
                                if (holdPreferred) {
                                    assertTrue(
                                        releasePreferred.await(6, TimeUnit.SECONDS),
                                        "Preferred request never released",
                                    )
                                }
                            }
                        }
                        val length = if (blocker && start == 0) 65536 else 1
                        val total = if (blocker) 65537 else 1
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Range")
                            .header("Content-Range", "bytes $start-${start + length - 1}/$total")
                            .body(ByteArray(length).toResponseBody())
                            .build()
                    } finally {
                        active.decrementAndGet()
                    }
                }.build()
        val workers = Executors.newFixedThreadPool(2)
        val readers = mutableListOf<DataSource>()

        fun spec(path: String): DataSpec {
            val uri = mockk<Uri>()
            every { uri.toString() } returns "https://media.example.com/$path"
            return DataSpec.Builder().setUri(uri).build()
        }
        try {
            ParallelDownloadSession(
                client,
                // Three ordinary blockers fill N−1; the rescue slot is not ordinary capacity.
                ParallelDownloadConfig(enabled = true, maxRequests = 4),
                VodPlaybackSource(
                    "test",
                    MediaTrackSource("v", DownloadTrack.Video, listOf("https://media.example.com/video")),
                    MediaTrackSource("a", DownloadTrack.Audio, listOf("https://media.example.com/audio")),
                ),
                monitor,
            ).use { session ->
                fun reader(
                    path: String,
                    kind: DownloadTrack,
                ): DataSource =
                    session
                        .factory(
                            MediaTrackSource(path, kind, listOf("https://media.example.com/$path")),
                            DataSource.Factory { error("Unexpected fallback") },
                        ).createDataSource()
                        .also { readers.add(it) }
                reader("blocker1", other).open(spec("blocker1"))
                reader("blocker2", other).open(spec("blocker2"))
                reader("blocker3", other).open(spec("blocker3"))
                assertTrue(blockersStarted.await(2, TimeUnit.SECONDS))
                val ahead = reader("ahead", other)
                val aheadResult =
                    workers.submit<Long> {
                        Thread.currentThread().name = "dispatch-test-ahead"
                        ahead.open(spec("ahead"))
                    }
                assertTrue(aheadQueued.await(2, TimeUnit.SECONDS))
                val lagging = if (queuePreferred) reader("preferred", preferred) else null
                val preferredResult =
                    lagging?.let { queuedReader ->
                        workers.submit<Long> {
                            Thread.currentThread().name = "dispatch-test-preferred"
                            queuedReader.open(spec("preferred"))
                        }
                    }
                if (queuePreferred) assertTrue(preferredQueued.await(2, TimeUnit.SECONDS))
                assertTrue(dispatches.isEmpty(), "Occupied slots must prevent queued requests from starting")
                if (cancelPreferred) {
                    requireNotNull(lagging).close()
                    assertFailsWith<ExecutionException> { requireNotNull(preferredResult).get(2, TimeUnit.SECONDS) }
                }
                releaseFirst.countDown()
                if (holdPreferred) {
                    assertTrue(
                        preferredStarted.await(2, TimeUnit.SECONDS),
                        "Earlier demanded range must take the first scarce slot",
                    )
                    releaseAll.countDown()
                    assertTrue(aheadStarted.await(2, TimeUnit.SECONDS), "Later range must use another available slot")
                    assertFalse(
                        requireNotNull(preferredResult).isDone,
                        "Later range must not wait for earlier transfer completion",
                    )
                }
                assertEquals(1L, aheadResult.get(2, TimeUnit.SECONDS))
                releasePreferred.countDown()
                if (queuePreferred &&
                    !cancelPreferred
                ) {
                    assertEquals(1L, requireNotNull(preferredResult).get(2, TimeUnit.SECONDS))
                }
                assertEquals(
                    if (queuePreferred &&
                        !cancelPreferred
                    ) {
                        if (equalDemand) listOf("/ahead", "/preferred") else listOf("/preferred", "/ahead")
                    } else {
                        listOf("/ahead")
                    },
                    dispatches.toList(),
                )
                assertTrue(peak.get() <= 3)
                releaseAll.countDown()
                readers.forEach { it.close() }
            }
        } finally {
            releasePreferred.countDown()
            releaseFirst.countDown()
            releaseAll.countDown()
            workers.shutdownNow()
        }
    }
}

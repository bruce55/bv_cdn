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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises actual cooldown admission, including waiting without occupying HTTP capacity. */
class CooldownRecoveryTransportTest {
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
    fun `second demanded reader waits without consuming slots and resumes after successful recovery`() {
        exercise(cancelFirst = false)
    }

    @Test
    fun `cancelled recovery releases admission to waiting demanded reader`() {
        exercise(cancelFirst = true)
    }

    private fun exercise(cancelFirst: Boolean) {
        val waiting = CountDownLatch(1)
        val began = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns true
        every { monitor.segmentBounds(any(), any()) } returns null
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.explorationBudgetNanos(any(), any(), any()) } returns null
        every { monitor.schedulingTimeMs(any(), any()) } returns null
        every { monitor.traceEnabled } returns true
        every { monitor.trace(any(), *anyVararg()) } answers {
            val fields = secondArg<Array<out Pair<String, Any?>>>().toMap()
            if (firstArg<String>() == "scheduler" && fields["reason"] == "cdn_recovery_wait") {
                assertEquals(3, fields["globalSlotsFree"])
                waiting.countDown()
            }
        }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (requests.incrementAndGet() == 1) {
                        began.countDown()
                        while (!release.await(10, TimeUnit.MILLISECONDS)) {
                            if (chain.call().isCanceled()) throw IOException("cancelled")
                        }
                    }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Range")
                        .header("Content-Range", "bytes 0-0/1")
                        .body(byteArrayOf(42).toResponseBody())
                        .build()
                }.build()
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf("https://media.example/video"))
        val workers = Executors.newFixedThreadPool(2)
        try {
            ParallelDownloadSession(
                client,
                ParallelDownloadConfig(enabled = true, maxRequests = 4),
                VodPlaybackSource("test", track, null),
                monitor,
            ).use { session ->
                val resolverField =
                    ParallelDownloadSession::class.java.getDeclaredField("resolver").apply {
                        isAccessible =
                            true
                    }
                (resolverField.get(session) as CdnResolver).failure(track.urls.single())
                val factory = session.factory(track, DataSource.Factory { error("Unexpected fallback") })
                val first = factory.createDataSource()
                val second = factory.createDataSource()
                val uri = mockk<Uri>()
                every { uri.toString() } returns track.urls.single()
                val spec =
                    DataSpec
                        .Builder()
                        .setUri(uri)
                        .setLength(1)
                        .build()
                val firstResult = workers.submit<Long> { first.open(spec) }
                assertTrue(began.await(2, TimeUnit.SECONDS))
                val secondResult = workers.submit<Long> { second.open(spec) }
                assertTrue(waiting.await(3, TimeUnit.SECONDS), "Second reader should wait on cooldown admission")
                assertEquals(1, requests.get())
                assertFalse(secondResult.isDone, "Waiting must not consume retry budget or fail the reader")
                if (cancelFirst) {
                    first.close()
                    assertFailsWith<ExecutionException> { firstResult.get(2, TimeUnit.SECONDS) }
                } else {
                    release.countDown()
                    assertEquals(1L, firstResult.get(2, TimeUnit.SECONDS))
                }
                assertEquals(1L, secondResult.get(2, TimeUnit.SECONDS))
                assertEquals(2, requests.get())
                first.close()
                second.close()
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}

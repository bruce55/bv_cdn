package dev.frost819.newbv.player.download

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A late reader-side refill must not create work after seek handover cleaned up its queue. */
class DetachedReaderRefillTest {
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
    fun `late timeout refill cannot allocate new downloads on detached transport owner`() {
        val blockSize = 65536
        val payload = ByteArray(8 * blockSize) { (it % 251).toByte() }
        val url = "https://media.example.com/video"
        val track = MediaTrackSource("video", DownloadTrack.Video, listOf(url))
        val monitor = mockk<DownloadMonitor>(relaxed = true)
        every { monitor.snapshots } returns kotlinx.coroutines.flow.MutableStateFlow(DownloadSnapshot())
        every { monitor.hasStartedPlayback() } returns true
        every { monitor.isPlaybackPaused() } returns false
        every { monitor.deadlineNanos(any(), any()) } returns null
        every { monitor.segmentBounds(any(), any()) } answers {
            val start = secondArg<Long>() / blockSize * blockSize
            start..minOf(payload.lastIndex.toLong(), start + blockSize - 1)
        }
        every { monitor.seekWindow(any(), any(), any()) } returns (blockSize.toLong() until 5L * blockSize)
        val sampler = AtomicReference<() -> Unit>({})
        every { monitor.traceEnabled } returns true
        every { monitor.setTraceSampler(any()) } answers { sampler.set(firstArg()) }
        val capacity = AtomicReference<Map<String, Any?>>(emptyMap())
        val dispatches = AtomicInteger()
        every { monitor.trace(any(), *anyVararg()) } answers {
            when (firstArg<String>()) {
                "session_capacity" -> capacity.set(secondArg<Array<out Pair<String, Any?>>>().toMap())
                "dispatch" -> dispatches.incrementAndGet()
            }
            Unit
        }
        // N−1 ordinary requests; the fourth slot remains available to recovery.
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<Call>())
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
                    val end = minOf(bounds[1], payload.lastIndex)
                    if (start > 0) {
                        calls.add(chain.call())
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Retained requests were never released" }
                        check(!chain.call().isCanceled()) { "Seek cancelled useful work" }
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/${payload.size}")
                        .body(payload.copyOfRange(start, end + 1).toResponseBody())
                        .build()
                }.build()
        val uri = mockk<Uri>()
        every { uri.toString() } returns url
        val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)
        try {
            ParallelDownloadSession(client, config, VodPlaybackSource("test", track, null), monitor).use { session ->
                val reader =
                    session.factory(track, DataSource.Factory { error("Unexpected fallback") }).createDataSource()
                reader.open(DataSpec.Builder().setUri(uri).build())
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue()
                // Capture the transport owner, because the public wrapper intentionally forgets
                // it on close. Direct invocation orders the timeout/refill interleaving exactly.
                val owner =
                    requireNotNull(
                        reader.javaClass
                            .getDeclaredField("reader")
                            .apply { isAccessible = true }
                            .get(reader),
                    )
                session.prepareSeek(1000)
                reader.close()
                sampler.get().invoke()
                val before = capacity.get()
                val dispatchesBefore = dispatches.get()
                assertThat((before["payloadUsed"] as Number).toLong()).isEqualTo(8L * blockSize)
                assertThat(calls.all { !it.isCanceled() }).isTrue()

                owner.javaClass
                    .getDeclaredMethod("fillWindow")
                    .apply { isAccessible = true }
                    .invoke(owner)

                sampler.get().invoke()
                assertThat(dispatches.get()).isEqualTo(dispatchesBefore)
                assertThat(capacity.get()["payloadUsed"]).isEqualTo(before["payloadUsed"])
                assertThat(capacity.get()["plannedWork"]).isEqualTo(before["plannedWork"])
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)

                fun drained(): Boolean {
                    sampler.get().invoke()
                    return listOf("payloadUsed", "readers", "plannedWork").all {
                        (capacity.get()[it] as? Number)?.toLong() == 0L
                    }
                }
                while (!drained() && System.nanoTime() < deadline) Thread.sleep(5)
                assertThat(drained()).isTrue()
            }
        } finally {
            release.countDown()
        }
    }
}

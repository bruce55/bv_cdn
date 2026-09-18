package dev.frost819.newbv.player.download

import android.net.Uri
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
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic transport coverage using an in-process OkHttp response interceptor. */
class ParallelDownloadSessionTest {
    private val url = "https://media.example.com/video"
    private val track = MediaTrackSource("v", DownloadTrack.Video, listOf(url))
    private val config = ParallelDownloadConfig(enabled = true, maxRequests = 4)

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
    fun `out of order ranges deliver exact bytes and reader reopens at seek`() {
        val data = ByteArray(900_000) { (it % 251).toByte() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val secondStarted = CountDownLatch(1)
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
                        if (start == 262_144) assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
                        if (start == 524_288) secondStarted.countDown()
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
        val monitor = DownloadMonitor(config)
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
        assertTrue(peak.get() >= 2)
        assertTrue(peak.get() <= 4)
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

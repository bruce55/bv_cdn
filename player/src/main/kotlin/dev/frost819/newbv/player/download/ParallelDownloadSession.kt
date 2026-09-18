package dev.frost819.newbv.player.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bounded VOD range transport. Audio and video share the same request budget and route health.
 * Each reader retains at most five 256 KiB blocks; closing a source cancels its outstanding work.
 */
class ParallelDownloadSession(
    client: OkHttpClient,
    private val config: ParallelDownloadConfig,
    private val source: VodPlaybackSource,
    private val monitor: DownloadMonitor,
) : Closeable {
    private val client =
        client
            .newBuilder()
            .connectTimeout(
                8,
                TimeUnit.SECONDS,
            ).readTimeout(4, TimeUnit.SECONDS)
            .build()
    private val limit = config.maxRequests.coerceIn(2, 8)
    private val executor =
        Executors.newFixedThreadPool(limit) { task ->
            Thread(task, "vod-range").apply {
                isDaemon =
                    true
            }
        }
    private val permits = Semaphore(limit, true)
    private val resolver = CdnResolver(config)
    private val readers = ConcurrentHashMap.newKeySet<RangeDataSource>()

    @Volatile private var closed = false

    /** Creates readers for one representation while preserving a sequential compatibility path. */
    fun factory(
        track: MediaTrackSource,
        fallback: DataSource.Factory,
    ): DataSource.Factory =
        DataSource.Factory {
            RangeDataSource(track, fallback).also { readers.add(it) }
        }

    /** Cancels all calls and queued work for the old video or quality. */
    override fun close() {
        closed = true
        readers.toList().forEach { it.close() }
        executor.shutdownNow()
    }

    private class UnsupportedRange(
        val url: String,
    ) : IOException("Server does not support ranges")

    private data class Block(
        val id: Long,
        val start: Long,
        val bytes: ByteArray,
        val total: Long,
    )

    private data class Pending(
        val id: Long,
        val future: Future<Block>,
    )

    private inner class RangeDataSource(
        private val track: MediaTrackSource,
        private val fallback: DataSource.Factory,
    ) : BaseDataSource(true) {
        private val calls = ConcurrentHashMap.newKeySet<Call>()
        private val pending = java.util.ArrayDeque<Pending>()
        private val ids = ConcurrentHashMap.newKeySet<Long>()

        @Volatile private var cancelled = false

        @Volatile private var generation = 0L

        @Volatile private var sequential: DataSource? = null

        @Volatile private var sequentialPermit = false
        private var spec: DataSpec? = null
        private var uri: Uri? = null
        private var block: Block? = null
        private var blockOffset = 0
        private var position = 0L
        private var endExclusive = 0L
        private var nextStart = 0L
        private var total: Long? = null
        private var started = false

        override fun open(dataSpec: DataSpec): Long {
            generation++
            cancelled = false
            checkOpen()
            readers.add(this)
            spec = dataSpec
            uri = dataSpec.uri
            position = dataSpec.position
            transferInitializing(dataSpec)
            try {
                val length =
                    if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || !config.enabled) {
                        startSequential(dataSpec.uri.toString())
                    } else {
                        val requestEnd =
                            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                                Long.MAX_VALUE
                            } else {
                                if (dataSpec.length > Long.MAX_VALUE - position) throw IOException("Range overflow")
                                position + dataSpec.length
                            }
                        endExclusive = requestEnd
                        val firstEnd = minOf(requestEnd - 1, position + CHUNK_SIZE - 1)
                        try {
                            val first = fetch(position, firstEnd, plan(position, firstEnd), generation)
                            total = first.total
                            endExclusive = minOf(requestEnd, first.total)
                            block = first
                            nextStart = position + first.bytes.size
                            fillWindow()
                            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else endExclusive - position
                        } catch (unsupported: UnsupportedRange) {
                            startSequential(unsupported.url)
                        }
                    }
                started = true
                transferStarted(dataSpec)
                return length
            } catch (error: Exception) {
                close()
                throw asIo(error)
            }
        }

        private fun plan(
            start: Long,
            end: Long,
        ): Long = monitor.plan(track.kind, start, end).also { ids.add(it) }

        // Advance the window only as Media3 consumes it, so paused playback cannot download the file.
        private fun fillWindow() {
            synchronized(pending) {
                while (!cancelled && pending.size < minOf(WINDOW_BLOCKS, limit - 1) && nextStart < endExclusive) {
                    val start = nextStart
                    val end = minOf(endExclusive - 1, start + CHUNK_SIZE - 1)
                    val id = plan(start, end)
                    nextStart = end + 1
                    val token = generation
                    pending.add(Pending(id, executor.submit(Callable { fetch(start, end, id, token) })))
                }
            }
        }

        private fun acquire() {
            try {
                while (!permits.tryAcquire(100, TimeUnit.MILLISECONDS)) checkOpen()
                try {
                    checkOpen()
                } catch (error: IOException) {
                    permits.release()
                    throw error
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Range request interrupted")
            }
        }

        private fun fetch(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
        ): Block {
            fun ensureCurrent() {
                checkOpen()
                if (token != generation) throw InterruptedIOException("Stale media range")
            }
            ensureCurrent()
            val requestSpec = spec
            val expectedTotal = total
            var lastError: IOException = IOException("No eligible CDN route")
            for (url in resolver.candidates(track).take(MAX_ATTEMPTS)) {
                ensureCurrent()
                acquire()
                val began = System.nanoTime()
                var call: Call? = null
                try {
                    val request = Request.Builder().url(url)
                    config.requestHeaders.forEach { (key, value) -> request.header(key, value) }
                    requestSpec?.httpRequestHeaders?.forEach { (key, value) -> request.header(key, value) }
                    request.header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
                    call = client.newCall(request.build())
                    call.timeout().timeout(15, TimeUnit.SECONDS)
                    calls.add(call)
                    ensureCurrent()
                    monitor.state(id, DownloadBlockState.Active)
                    monitor.progress(id, 0)
                    monitor.requestStarted()
                    try {
                        call.execute().use { response ->
                            if (response.code == 200) throw UnsupportedRange(url)
                            if (response.code == 416 && response.header("Content-Range") == "bytes */$start") {
                                monitor.discard(listOf(id))
                                return Block(id, start, ByteArray(0), start)
                            }
                            if (response.code != 206) throw IOException("Range HTTP ${response.code}")
                            if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
                                throw IOException("Encoded range response")
                            }
                            if (response.header("Content-Range")?.endsWith("/*") == true) throw UnsupportedRange(url)
                            val range = RangeResponse.parse(response.header("Content-Range"), start, end, expectedTotal)
                            val expected = (range.end - range.start + 1).toInt()
                            val body = response.body ?: throw IOException("Empty range body")
                            if (body.contentLength() != -1L &&
                                body.contentLength() != expected.toLong()
                            ) {
                                throw IOException("Range length mismatch")
                            }
                            val bytes = ByteArray(expected)
                            body.byteStream().use { input ->
                                var received = 0
                                while (received < expected) {
                                    ensureCurrent()
                                    val count = input.read(bytes, received, expected - received)
                                    if (count < 0) throw IOException("Truncated media range")
                                    received += count
                                    monitor.progress(id, received.toLong())
                                }
                                if (input.read() != -1) throw IOException("Overlong media range")
                            }
                            ensureCurrent()
                            monitor.recordBytes(track.kind, start, bytes)
                            monitor.state(id, DownloadBlockState.Complete)
                            resolver.success(url, bytes.size, System.nanoTime() - began)
                            return Block(id, start, bytes, range.total)
                        }
                    } finally {
                        monitor.requestFinished()
                    }
                } catch (unsupported: UnsupportedRange) {
                    monitor.discard(listOf(id))
                    throw unsupported
                } catch (error: IOException) {
                    ensureCurrent()
                    lastError = error
                    resolver.failure(url)
                    monitor.state(id, DownloadBlockState.Retrying)
                } finally {
                    call?.let { calls.remove(it) }
                    permits.release()
                }
            }
            monitor.state(id, DownloadBlockState.Failed)
            throw lastError
        }

        private fun startSequential(url: String): Long {
            cancelWindow()
            block = null
            acquire()
            sequentialPermit = true
            monitor.requestStarted()
            // Track the compatibility call too: DefaultDataSource cannot expose a pending
            // OkHttp open() call, which otherwise survives seeks until its network timeout.
            val token = generation
            val reader =
                if (config.enabled && spec?.httpMethod == DataSpec.HTTP_METHOD_GET) {
                    OkHttpDataSource
                        .Factory(
                            object : Call.Factory {
                                override fun newCall(request: Request): Call {
                                    val call = client.newCall(request)
                                    calls.add(call)
                                    if (cancelled || closed || generation != token) call.cancel()
                                    return call
                                }
                            },
                        ).setDefaultRequestProperties(config.requestHeaders)
                        .createDataSource()
                } else {
                    fallback.createDataSource()
                }
            sequential = reader
            checkOpen()
            val original = requireNotNull(spec)
            val remaining =
                if (original.length == C.LENGTH_UNSET.toLong()) {
                    C.LENGTH_UNSET.toLong()
                } else {
                    original.length - (position - original.position)
                }
            return reader.open(
                original
                    .buildUpon()
                    .setUri(url)
                    .setPosition(position)
                    .setLength(remaining)
                    .build(),
            )
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            checkOpen()
            sequential?.let { return readSequential(it, buffer, offset, length) }
            if (position >= endExclusive) return C.RESULT_END_OF_INPUT
            if (block == null || blockOffset >= requireNotNull(block).bytes.size) {
                block?.let { ids.remove(it.id) }
                block = null
                blockOffset = 0
                val next = synchronized(pending) { pending.poll() } ?: return C.RESULT_END_OF_INPUT
                try {
                    block = next.future.get()
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause else error
                    if (cause is UnsupportedRange) {
                        startSequential(cause.url)
                        return readSequential(requireNotNull(sequential), buffer, offset, length)
                    }
                    throw asIo(cause ?: error)
                }
                fillWindow()
            }
            val current = requireNotNull(block)
            val count = minOf(length, current.bytes.size - blockOffset)
            current.bytes.copyInto(buffer, offset, blockOffset, blockOffset + count)
            position += count
            blockOffset += count
            bytesTransferred(count)
            return count
        }

        private fun readSequential(
            reader: DataSource,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = reader.read(buffer, offset, length)
            if (count > 0) {
                monitor.recordBytes(track.kind, position, buffer.copyOfRange(offset, offset + count))
                position += count
                bytesTransferred(count)
            }
            return count
        }

        override fun getUri(): Uri? = sequential?.uri ?: uri

        override fun getResponseHeaders(): Map<String, List<String>> = sequential?.responseHeaders ?: emptyMap()

        private fun checkOpen() {
            if (closed ||
                cancelled ||
                Thread.currentThread().isInterrupted
            ) {
                throw InterruptedIOException("Playback download closed")
            }
        }

        private fun cancelWindow() {
            synchronized(pending) {
                pending.forEach { it.future.cancel(true) }
                pending.clear()
            }
            calls.forEach { it.cancel() }
            monitor.discard(ids.toList())
            ids.clear()
        }

        @Synchronized
        override fun close() {
            cancelled = true
            generation++
            cancelWindow()
            calls.clear()
            try {
                sequential?.close()
            } finally {
                sequential = null
                if (sequentialPermit) {
                    sequentialPermit = false
                    monitor.requestFinished()
                    permits.release()
                }
                block = null
                blockOffset = 0
                total = null
                uri = null
                readers.remove(this)
                if (started) {
                    started = false
                    transferEnded()
                }
            }
        }
    }

    private companion object {
        const val CHUNK_SIZE = 256 * 1024L
        const val WINDOW_BLOCKS = 4
        const val MAX_ATTEMPTS = 4

        fun asIo(error: Throwable): IOException {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return when (error) {
                is IOException -> error
                is InterruptedException, is CancellationException ->
                    InterruptedIOException(
                        "Playback download interrupted",
                    )
                else -> IOException("Playback range failure", error)
            }
        }
    }
}

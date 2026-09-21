package dev.frost819.newbv.app.network

import dev.frost819.newbv.player.download.DownloadTraceEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Small, process-death-resistant memory trace. Only already-redacted events from the opt-in trace
 * store are accepted. Two rotating JSONL files retain at most 2 MiB; a bounded worker queue keeps
 * storage off playback/UI threads. Construction neither starts a worker nor touches storage.
 */
class DownloadMemoryJournal(
    private val directory: File,
    private val fileLimitBytes: Int = 1_048_576,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(fileLimitBytes in 1024..1_048_576)
    }

    private val fileLock = Any()
    private val capacityAt = linkedMapOf<String, Long>()
    private val rejected = AtomicLong()
    private val worker =
        ThreadPoolExecutor(
            0,
            1,
            5,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(16),
            { task -> Thread(task, "download-memory-journal").apply { isDaemon = true } },
            { _, _ -> rejected.incrementAndGet() },
        )

    /** Number of dropped or failed writes in this process, including queue overflow. */
    val droppedWrites: Long get() = rejected.get()

    /** Enqueues a capped metadata copy; never waits for disk or a free queue slot. */
    @Synchronized
    fun record(event: DownloadTraceEvent) {
        if (event.type !in memoryTypes) return
        if (event.type == "session_capacity") {
            val now = nanoTime()
            val previous = capacityAt[event.session]
            if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(5)) return
            capacityAt[event.session] = now
            while (capacityAt.size > 32) capacityAt.remove(capacityAt.keys.first())
        }
        val bounded =
            event.copy(
                fields =
                    event.fields.entries
                        .take(128)
                        .associate { it.key.take(128) to it.value.take(256) },
            )
        worker.execute {
            runCatching { append(bounded) }.onFailure { rejected.incrementAndGet() }
        }
    }

    /** Reads retained complete JSONL records. Call on an I/O dispatcher; capture may be disabled. */
    fun read(): String =
        synchronized(fileLock) {
            listOf(File(directory, "previous.jsonl"), File(directory, "active.jsonl"))
                .joinToString("") { file ->
                    if (!file.isFile) {
                        ""
                    } else {
                        val bytes =
                            file.inputStream().use { input ->
                                val result = ByteArray(fileLimitBytes)
                                var count = 0
                                while (count < result.size) {
                                    val n = input.read(result, count, result.size - count)
                                    if (n < 0) break
                                    count += n
                                }
                                result.copyOf(count)
                            }
                        val complete = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
                        String(bytes, 0, complete, Charsets.UTF_8)
                    }
                }
        }

    private fun append(event: DownloadTraceEvent) {
        val line =
            (
                buildJsonObject {
                    put("sequence", event.sequence)
                    put("timeMs", event.timeMs)
                    put("session", event.session)
                    put("type", event.type)
                    put("fields", buildJsonObject { event.fields.forEach { (key, value) -> put(key, value) } })
                }.toString() + "\n"
            ).toByteArray(Charsets.UTF_8)
        if (line.size > fileLimitBytes) {
            rejected.incrementAndGet()
            return
        }
        synchronized(fileLock) {
            check(directory.isDirectory || directory.mkdirs())
            val active = File(directory, "active.jsonl")
            val previous = File(directory, "previous.jsonl")
            if (active.exists()) {
                // A process killed during append may leave a torn last record. Drop that suffix
                // before appending so the following process never joins two JSON objects together.
                RandomAccessFile(active, "rw").use { file ->
                    var end = file.length().coerceAtMost(fileLimitBytes.toLong())
                    while (end > 0) {
                        file.seek(end - 1)
                        if (file.read() == '\n'.code) break
                        end--
                    }
                    file.setLength(end)
                }
            }
            if (active.length() + line.size > fileLimitBytes) {
                check(!previous.exists() || previous.delete())
                check(active.renameTo(previous))
            }
            FileOutputStream(active, true).use { output ->
                output.write(line)
                output.fd.sync()
            }
        }
    }

    /** Stops accepting records and waits briefly for queued writes; never call from a UI callback. */
    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow()
    }

    private companion object {
        val memoryTypes = setOf("memory_sample", "memory_trim", "memory_exit_history", "session_capacity")
    }
}

package dev.frost819.newbv.app.network

import dev.frost819.newbv.player.download.DownloadTraceSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Saves bounded, redacted stall reports beside crash logs, without blocking playback on disk I/O. */
class BufferingJournal(
    private val directory: File,
) : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(
            0,
            1,
            5,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(1),
            { task -> Thread(task, "buffering-journal").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )

    /** Enqueues the latest bounded history; repeated saves atomically replace the same incident. */
    fun record(
        id: String,
        snapshot: DownloadTraceSnapshot,
    ) {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        worker.execute {
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                val target = File(directory, "logs_buffering_$id.log")
                val temporary = File(directory, ".buffering.tmp")
                FileOutputStream(temporary).use { output ->
                    output.bufferedWriter().use { writer ->
                        writer.appendLine("newBV buffering report — bounded recent history; not a crash")
                        snapshot.events.forEach { event ->
                            writer.appendLine(
                                buildJsonObject {
                                    put("sequence", event.sequence)
                                    put("timeMs", event.timeMs)
                                    put("session", event.session)
                                    put("type", event.type)
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            event.fields.forEach { (key, value) -> put(key, value) }
                                        },
                                    )
                                }.toString(),
                            )
                        }
                        writer.flush()
                        output.fd.sync()
                    }
                }
                check(temporary.renameTo(target))
                directory
                    .listFiles { it.name.startsWith("logs_buffering_") && it.extension == "log" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            }
        }
    }

    /** Flushes queued reports; call only from an I/O thread. */
    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow()
    }
}

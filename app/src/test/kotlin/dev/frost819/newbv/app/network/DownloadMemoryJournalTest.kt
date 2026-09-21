package dev.frost819.newbv.app.network

import dev.frost819.newbv.player.download.DownloadTraceEvent
import dev.frost819.newbv.player.download.DownloadTraceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadMemoryJournalTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `capture off never creates storage and only enabled redacted memory reaches disk`() {
        val destination = File(directory, "journal")
        val journal = DownloadMemoryJournal(destination)
        val store = DownloadTraceStore(memoryEventSink = journal::record)
        store.record("s", "memory_sample", mapOf("rss" to 123))
        assertFalse(destination.exists())
        store.setEnabled(true)
        store.record("s", "network_request", mapOf("bytes" to 9))
        store.record(
            "s",
            "memory_sample",
            mapOf(
                "rss" to 123,
                "token" to "hidden",
                "error" to "https://secret.example/x",
            ),
        )
        store.setEnabled(false)
        store.record("s", "memory_trim", emptyMap())
        journal.close()
        val records =
            journal
                .read()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
        assertEquals(1, records.size)
        assertEquals(
            "memory_sample",
            Json
                .parseToJsonElement(records.single())
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(records.single().contains("hidden"))
        assertFalse(records.single().contains("secret.example"))
        assertTrue(records.single().contains("redacted"))
    }

    @Test
    fun `flattened memory metadata retains late decoder fields within bounded field count`() {
        val journal = DownloadMemoryJournal(File(directory, "journal"))
        val store = DownloadTraceStore(memoryEventSink = journal::record)
        store.setEnabled(true)
        store.record(
            "s",
            "memory_sample",
            mapOf(
                "device" to (0 until 35).associate { "metric$it" to it },
                "process" to (0 until 30).associate { "metric$it" to it },
                "playback" to mapOf("width" to 3840, "height" to 2160, "decoder" to "hardware"),
            ),
        )
        journal.close()
        val fields = Json.parseToJsonElement(journal.read().trim()).jsonObject["fields"]!!.jsonObject
        assertEquals(68, fields.size)
        assertEquals("hardware", fields["playback.decoder"]?.jsonPrimitive?.content)
        assertEquals("3840", fields["playback.width"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rotation preserves recent samples across restart within total disk bound`() {
        val destination = File(directory, "journal")
        for (batch in 0..5) {
            DownloadMemoryJournal(destination, fileLimitBytes = 1024).use { journal ->
                for (index in 0..3) journal.record(event(batch * 4L + index, "memory_sample", "x".repeat(160)))
            }
        }
        DownloadMemoryJournal(destination, fileLimitBytes = 1024).use { journal ->
            val records =
                journal
                    .read()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
            assertTrue(records.isNotEmpty())
            records.forEach { Json.parseToJsonElement(it) }
            assertEquals(
                "23",
                Json
                    .parseToJsonElement(records.last())
                    .jsonObject["sequence"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertTrue(destination.listFiles().orEmpty().sumOf { it.length() } <= 2048)
            assertTrue(destination.listFiles().orEmpty().size <= 2)
        }
    }

    @Test
    fun `capacity samples throttle per session and next process repairs torn append`() {
        val destination = File(directory, "journal").apply { mkdirs() }
        File(destination, "active.jsonl").writeText("{\"old\":true}\n{\"torn\":")
        var now = 0L
        DownloadMemoryJournal(destination, nanoTime = { now }).use { journal ->
            journal.record(event(1, "session_capacity"))
            journal.record(event(2, "session_capacity"))
            journal.record(event(3, "session_capacity").copy(session = "other"))
            now = 5_000_000_000L
            journal.record(event(4, "session_capacity"))
        }
        val lines = File(destination, "active.jsonl").readLines()
        assertEquals(4, lines.size)
        lines.forEach { Json.parseToJsonElement(it) }
        assertFalse(lines.joinToString().contains("torn"))
    }

    private fun event(
        sequence: Long,
        type: String,
        value: String = "123",
    ) = DownloadTraceEvent(sequence, 1000 + sequence, "s", type, mapOf("rss" to value))
}

package dev.frost819.newbv.app.network

import dev.frost819.newbv.player.download.DownloadTraceEvent
import dev.frost819.newbv.player.download.DownloadTraceSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BufferingJournalTest {
    @TempDir lateinit var directory: File

    @Test
    fun `reports survive reopening and replace the same incident`() {
        fun save(
            id: String,
            state: String,
        ) {
            BufferingJournal(directory).use { journal ->
                journal.record(
                    id,
                    DownloadTraceSnapshot(
                        true,
                        1,
                        2,
                        listOf(
                            DownloadTraceEvent(1, 123, "session", "player_sample", mapOf("state" to state)),
                        ),
                    ),
                )
            }
        }
        save("incident", "buffering")
        save("incident", "ready")
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(File(directory, "logs_buffering_incident.log").readText().contains("ready"))
        repeat(7) { save("next_$it", "buffering") }
        assertEquals(5, directory.listFiles()!!.size)
    }
}

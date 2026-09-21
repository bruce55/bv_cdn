package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTraceStoreTest {
    @Test
    fun `buffering opt in saves redacted history without enabling live capture`() {
        var enabled = true
        val saved = mutableListOf<DownloadTraceSnapshot>()
        val store =
            DownloadTraceStore(
                bufferingEnabled = { enabled },
                bufferingSink = { _, snapshot -> saved.add(snapshot) },
            )

        fun sample(
            time: Long,
            buffering: Boolean,
        ) = store.record(
            "one",
            "player_sample",
            mapOf(
                "sampleElapsedMs" to time,
                "state" to if (buffering) 2 else 3,
                "playWhenReady" to true,
            ),
        )
        store.record("one", "dispatch", mapOf("url" to "https://private.example/token"))
        sample(1000, true)
        sample(4000, true)
        assertEquals(1, saved.size)
        assertEquals(
            "[redacted]",
            saved
                .first()
                .events
                .first()
                .fields["url"],
        )
        assertFalse(store.liveCaptureEnabled)
        sample(5000, false)
        assertEquals(2, saved.size)
        enabled = false
        sample(6000, true)
        sample(9000, true)
        assertEquals(2, saved.size)
        assertFalse(store.enabled)
    }

    @Test
    fun `capture is opt in and disabled capture preserves final history`() {
        val store = DownloadTraceStore()
        store.record("one", "ignored", emptyMap())
        assertTrue(store.snapshot().events.isEmpty())
        store.setEnabled(true)
        store.record("one", "dispatch", mapOf("host" to "cdn.example", "bytes" to 512))
        store.setEnabled(false)
        store.record("one", "ignored", emptyMap())
        assertFalse(store.snapshot().enabled)
        assertEquals(1, store.snapshot().events.size)
        assertEquals(
            "512",
            store
                .snapshot()
                .events
                .single()
                .fields["bytes"],
        )
    }

    @Test
    fun `ring cursor exposes evictions and only returns newer records`() {
        val store = DownloadTraceStore(maxEvents = 2)
        store.setEnabled(true)
        repeat(4) { store.record("one", "dispatch", mapOf("block" to it)) }
        val page = store.snapshot(after = 3)
        assertEquals(3L, page.oldestSequence)
        assertEquals(5L, page.nextSequence)
        assertEquals(listOf(4L), page.events.map { it.sequence })
        assertTrue(store.snapshot(after = 4).events.isEmpty())
    }

    @Test
    fun `credential fields URLs and arbitrary objects cannot leak into capture`() {
        val store = DownloadTraceStore()
        store.setEnabled(true)
        store.record(
            "one",
            "failure",
            mapOf(
                "url" to "https://cdn.example/video?secret=abc",
                "authorization" to "Bearer secret",
                "description" to "failed https://cdn.example/video?secret=abc",
                "exception" to IllegalStateException("private"),
                "host" to "cdn.example",
            ),
        )
        val text = store.snapshot().events.toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("https://"))
        assertTrue(text.contains("cdn.example"))
    }

    @Test
    fun `candidate speed evidence survives flattening while nested credentials are redacted`() {
        val store = DownloadTraceStore()
        store.setEnabled(true)
        store.record(
            "one",
            "cdn.ranking",
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "host" to "cdn.example",
                            "confidence" to 0.05,
                            "predictedMs" to 2000,
                            "token" to "hidden",
                        ),
                    ),
            ),
        )
        val fields =
            store
                .snapshot()
                .events
                .single()
                .fields
        assertEquals("0.05", fields["candidates.0.confidence"])
        assertEquals("2000", fields["candidates.0.predictedMs"])
        assertEquals("[redacted]", fields["candidates.0.token"])
    }

    @Test
    fun `byte budget bounds large event retention`() {
        val store = DownloadTraceStore(maxCharacters = 4096)
        store.setEnabled(true)
        repeat(100) { store.record("one", "sample", mapOf("detail" to "x".repeat(2000))) }
        assertTrue(store.snapshot().events.size in 1..3)
        assertEquals(101L, store.snapshot().nextSequence)
    }

    @Test
    fun `concurrent writers retain unique ordered cursor sequence`() {
        val store = DownloadTraceStore()
        store.setEnabled(true)
        val writers = List(8) { Thread { repeat(100) { store.record("one", "sample", emptyMap()) } } }
        writers.forEach(Thread::start)
        writers.forEach(Thread::join)
        assertEquals((1L..800L).toList(), store.snapshot().events.map { it.sequence })
    }
}

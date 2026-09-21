package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Choice traces expose the actual policy while excluding signed media identifiers. */
class CdnTraceTest {
    @Test
    fun `rescue explains choosing feasible busy route over penalized idle route`() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val resolver =
            CdnResolver(
                ParallelDownloadConfig(),
                trace = { event, fields -> events += event to fields },
                traceEnabled = { true },
                nowNanos = { 1_000_000_000L },
            )
        val busy = "https://fast.example/private-video?token=secret"
        val idle = "https://slow.example/private-video?token=secret"
        val track = MediaTrackSource("sensitive-id", DownloadTrack.Video, listOf(busy, idle))
        resolver.success(busy, 10000, 1_000_000_000)
        resolver.success(idle, 1000, 1_000_000_000)
        val miss = resolver.requestStarted(idle, 1000)
        resolver.rescued(miss)
        resolver.requestFinished(miss)
        resolver.requestStarted(busy, 1)
        assertEquals(busy, assertNotNull(resolver.reserveRescue(track, 1000, 2_000_000_000, emptyList())).url)
        val fields = events.last { it.first == "cdn.rescue_selection" }.second
        assertEquals("fastest_feasible_busy", fields["reason"])
        assertEquals(1, fields["idleCount"])
        assertEquals(1, fields["feasibleCount"])
        assertEquals("fast.example", fields["host"])
        @Suppress("UNCHECKED_CAST")
        val candidates = fields["candidates"] as List<Map<String, Any?>>
        assertEquals(0.75, candidates.first { it["host"] == "slow.example" }["confidence"])
        assertEquals("ranking_not_dispatch", events.first { it.first == "cdn.ranking" }.second["purpose"])
        listOf("private-video", "token", "secret", "sensitive-id", "https://").forEach {
            assertFalse(events.toString().contains(it))
        }
    }

    @Test
    fun `disabled trace does not publish and discovery ranking is throttled`() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var enabled = false
        var now = 1_000_000_000L
        val resolver =
            CdnResolver(
                ParallelDownloadConfig(),
                trace = { event, fields -> events += event to fields },
                traceEnabled = { enabled },
                nowNanos = { now },
            )
        val url = "https://one.example/media?token=hidden"
        val track = MediaTrackSource("video", DownloadTrack.Video, listOf(url))
        resolver.candidates(track)
        assertTrue(events.isEmpty())
        enabled = true
        repeat(20) { resolver.candidates(track) }
        assertEquals(1, events.size)
        now += 1_000_000_000L
        resolver.candidates(track)
        assertEquals(2, events.size)
        val assignment = resolver.requestStarted(url, 1000)
        resolver.rescued(assignment, buffering = true)
        resolver.success(url, 1000, 1_000_000_000, onTime = true)
        assertEquals(0.02, events.first { it.first == "cdn.penalty" }.second["after"])
        assertEquals(0.07, events.first { it.first == "cdn.recovery" }.second["after"])
    }
}

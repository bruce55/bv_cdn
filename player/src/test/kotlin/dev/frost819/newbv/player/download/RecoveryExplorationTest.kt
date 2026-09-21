package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Low-buffer experiments preserve route exclusion, sampling priorities and shared spacing. */
class RecoveryExplorationTest {
    private val first = "https://one.example/video?signature=private"
    private val second = "https://two.example/video?signature=private"
    private val third = "https://three.example/video?signature=private"
    private val track = MediaTrackSource("video", DownloadTrack.Video, listOf(first, second, third))

    @Test
    fun `startup sample remains exploratory and takes priority over stale meaningful route`() {
        var now = 1_000_000_000L
        val events = mutableListOf<Map<String, Any?>>()
        val resolver =
            CdnResolver(
                ParallelDownloadConfig(),
                trace = { type, fields -> if (type == "cdn.exploration_selection") events += fields },
                traceEnabled = { true },
                nowNanos = { now },
            )
        resolver.success(first, 524288, 1_000_000_000)
        now += 20_000_000_000L
        resolver.success(second, 65536, 100_000_000, startupProbe = true)
        val selected = assertNotNull(resolver.reserveRecoveryExploration(track, 524288, listOf(third)))
        assertEquals(second, selected.url)
        assertEquals(524288L, selected.bytes)
        assertEquals("low_buffer_unmeasured", events.single()["reason"])
        // The atomic reservation marks the host busy before another range can select it.
        now += 2_000_000_000L
        assertEquals(first, assertNotNull(resolver.reserveRecoveryExploration(track, 524288, listOf(third))).url)
        assertEquals("low_buffer_retest", events.last()["reason"])
    }

    @Test
    fun `tried hosts busy audio hosts and cooldown are excluded without consuming exploration spacing`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        val audio = resolver.requestStarted(second.replace("/video", "/audio"), 65536)
        resolver.failure(third)
        assertNull(resolver.reserveRecoveryExploration(track, 524288, listOf(first.replace("/video", "/audio"))))
        resolver.requestFinished(audio)
        assertEquals(second, assertNotNull(resolver.reserveRecoveryExploration(track, 524288, listOf(first))).url)
    }

    @Test
    fun `normal and recovery experiments share global and representation cooldowns`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val single = track.copy(urls = listOf(first))
        assertEquals(first, resolver.explorationCandidate(single, second))
        assertNull(resolver.reserveRecoveryExploration(track, 524288, listOf(first)))
        now += 2_000_000_000L
        assertNull(resolver.reserveRecoveryExploration(single, 524288, emptyList()))
        now += 8_000_000_000L
        val selected = assertNotNull(resolver.reserveRecoveryExploration(single, 524288, emptyList()))
        assertEquals(first, selected.url)
        resolver.requestFinished(selected)
        assertNull(resolver.explorationCandidate(single, second))
    }

    @Test
    fun `fresh healthy evidence is skipped and recovering evidence waits ten seconds`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val single = track.copy(urls = listOf(first))
        resolver.success(first, 524288, 1_000_000_000)
        assertNull(resolver.reserveRecoveryExploration(single, 524288, emptyList()))
        val assignment = resolver.requestStarted(first, 524288)
        resolver.rescued(assignment)
        resolver.requestFinished(assignment)
        now += 9_000_000_000L
        assertNull(resolver.reserveRecoveryExploration(single, 524288, emptyList()))
        now += 1_000_000_000L
        assertEquals(first, assertNotNull(resolver.reserveRecoveryExploration(single, 524288, emptyList())).url)
        val pinned = CdnResolver(ParallelDownloadConfig(mode = CdnMode.Pinned, pinnedHost = "one.example"))
        assertNull(pinned.reserveRecoveryExploration(single, 524288, emptyList()))
    }
}

package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected exploration must escape a successful-but-slow route without defeating pins or cooldowns. */
class CdnExplorationTest {
    @Test
    fun `rescue without a fair deadline opportunity preserves confidence`() {
        val resolver = CdnResolver(ParallelDownloadConfig())
        val url = "https://test.example/video"
        val assignment = resolver.requestStarted(url, 1048576)
        resolver.rescued(assignment, penalize = false)
        kotlin.test.assertTrue(assignment.rescued)
        kotlin.test.assertEquals(1.0, resolver.schedulingConfidence(url))
        resolver.rescued(assignment)
        kotlin.test.assertEquals(1.0, resolver.schedulingConfidence(url))
        resolver.requestFinished(assignment)
    }

    @Test
    fun `repeated rescue discounts scheduling but late success cannot restore confidence`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val url = "https://test.example/video"
        resolver.success(url, 1048576, 1_000_000_000)
        val before = requireNotNull(resolver.remainingTransferNanos(url, 1048576))
        repeat(3) {
            val assignment = resolver.requestStarted(url, 1048576)
            resolver.rescued(assignment)
            resolver.rescued(assignment)
            resolver.requestFinished(assignment)
            resolver.success(url, 1048576, 1_000_000_000)
        }
        kotlin.test.assertEquals(0.75 * 0.65 * 0.55, resolver.schedulingConfidence(url))
        kotlin.test.assertTrue(requireNotNull(resolver.remainingTransferNanos(url, 1048576)) > before * 2)
        resolver.success(url, 1048576, 1_000_000_000, onTime = true)
        kotlin.test.assertEquals(0.75 * 0.65 * 0.55 + 0.05, resolver.schedulingConfidence(url))
        kotlin.test.assertEquals(1.0, resolver.schedulingConfidence("https://test.example/audio"))
        repeat(30) { resolver.success(url, 1048576, 1_000_000_000, onTime = true) }
        kotlin.test.assertEquals(0.75 * 0.65 * 0.55 + 0.05, resolver.schedulingConfidence(url))
        repeat(30) {
            now += 10_000_000_000L
            resolver.success(url, 1048576, 1_000_000_000, onTime = true)
        }
        kotlin.test.assertEquals(1.0, resolver.schedulingConfidence(url))
    }

    @Test
    fun `buffering upgrades a miss once to near zero and still allows gradual recovery`() {
        val resolver = CdnResolver(ParallelDownloadConfig())
        val url = "https://test.example/video"
        val assignment = resolver.requestStarted(url, 524288)
        resolver.rescued(assignment)
        assertEquals(0.75, resolver.schedulingConfidence(url))
        resolver.rescued(assignment, buffering = true)
        assertEquals(0.02, resolver.schedulingConfidence(url))
        repeat(10) { resolver.rescued(assignment, buffering = true) }
        assertEquals(0.02, resolver.schedulingConfidence(url))
        resolver.requestFinished(assignment)
        val again = resolver.requestStarted(url, 524288)
        resolver.rescued(again, buffering = true)
        assertEquals(0.01, resolver.schedulingConfidence(url))
        resolver.requestFinished(again)
        resolver.success(url, 524288, 1_000_000_000, onTime = false)
        assertEquals(0.01, resolver.schedulingConfidence(url))
        resolver.success(url, 524288, 1_000_000_000, onTime = true)
        kotlin.test.assertEquals(0.06, resolver.schedulingConfidence(url), 0.000001)
        assertEquals(1.0, resolver.schedulingConfidence("https://test.example/audio"))
    }

    @Test
    fun `unfair buffering observations remain exempt`() {
        val resolver = CdnResolver(ParallelDownloadConfig())
        val url = "https://test.example/video"
        val assignment = resolver.requestStarted(url, 524288)
        resolver.rescued(assignment, penalize = false, buffering = true)
        assertEquals(1.0, resolver.schedulingConfidence(url))
    }

    private val first = "https://one.example/video"
    private val second = "https://two.example/video"
    private val third = "https://three.example/video"
    private val video = MediaTrackSource("v", DownloadTrack.Video, listOf(first, second, third))

    @Test
    fun `rescue readiness excludes tried and cooling hosts without reserving or tracing`() {
        var now = 1_000_000_000L
        val events = mutableListOf<String>()
        val resolver =
            CdnResolver(
                ParallelDownloadConfig(),
                trace = { event, _ -> events.add(event) },
                traceEnabled = { true },
                nowNanos = { now },
            )
        val routes = video.copy(urls = listOf(first, second))
        repeat(3) { assertTrue(resolver.hasRescueCandidate(routes, listOf(first))) }
        assertTrue(events.isEmpty())
        val assignment = assertNotNull(resolver.reserveRescue(routes, 512, null, listOf(first)))
        assertEquals(second, assignment.url)
        assertEquals(1, assignment.concurrentRequests)
        resolver.requestFinished(assignment)
        assertFalse(resolver.hasRescueCandidate(routes, listOf(first, second)))
        resolver.failure(second)
        assertFalse(resolver.hasRescueCandidate(routes, listOf(first)))
        assertNull(resolver.reserveRescue(routes, 512, null, listOf(first)))
        now += 60_000_000_000L
        assertTrue(resolver.hasRescueCandidate(routes, listOf(first)))
    }

    @Test
    fun `exploration readiness preserves spacing and rejects busy cooling and recently measured hosts`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val routes = video.copy(urls = listOf(first, second))
        repeat(3) { assertTrue(resolver.hasRecoveryExplorationCandidate(routes, listOf(first))) }
        val assignment = assertNotNull(resolver.reserveRecoveryExploration(routes, 512, listOf(first)))
        assertEquals(second, assignment.url)
        assertEquals(1, assignment.concurrentRequests)
        assertFalse(resolver.hasRecoveryExplorationCandidate(routes, listOf(first)))
        resolver.requestFinished(assignment)
        now += 11_000_000_000L
        assertTrue(resolver.hasRecoveryExplorationCandidate(routes, listOf(first)))
        val busy = resolver.requestStarted(second, 512)
        assertFalse(resolver.hasRecoveryExplorationCandidate(routes, listOf(first)))
        resolver.requestFinished(busy)
        resolver.failure(second)
        assertFalse(resolver.hasRecoveryExplorationCandidate(routes, listOf(first)))
        now += 60_000_000_000L
        resolver.success(second, 512 * 1024, 500_000_000)
        assertFalse(resolver.hasRecoveryExplorationCandidate(routes, listOf(first)))
        assertNull(resolver.reserveRecoveryExploration(routes, 512, listOf(first)))
    }

    @Test
    fun `fresh useful ordinary transfers suppress redundant exploration until stale`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        video.urls.forEach { resolver.success(it, 512 * 1024, 500_000_000) }
        assertNull(resolver.explorationCandidate(video, first))
        now += 14_000_000_000
        resolver.success(second, 512 * 1024, 500_000_000)
        now += 2_000_000_000
        assertEquals(third, resolver.explorationCandidate(video, first))
    }

    @Test
    fun `startup sample remains light while repeated small audio transfers become meaningful`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(second, 65536, 1_000_000_000, startupProbe = true)
        repeat(3) { resolver.success(third, 57000, 100_000_000) }
        assertEquals(second, resolver.explorationCandidate(video, first))
        now += 2_000_000_000
        assertNull(resolver.explorationCandidate(video, first))
    }

    @Test
    fun `light samples outrank stale results and busy hosts are skipped`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(second, 512 * 1024, 500_000_000)
        now += 16_000_000_000
        resolver.success(third, 65536, 100_000_000, startupProbe = true)
        val busy = resolver.requestStarted(third, 512 * 1024)
        assertEquals(second, resolver.explorationCandidate(video, first))
        resolver.requestFinished(busy)
        now += 2_000_000_000
        assertEquals(third, resolver.explorationCandidate(video, first))
    }

    @Test
    fun `recovery checks restore confidence only on timely nonstartup completion`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val routes = video.copy(urls = listOf(first, second))
        resolver.success(second, 512 * 1024, 500_000_000)
        val late = resolver.requestStarted(second, 512 * 1024)
        resolver.rescued(late)
        resolver.requestFinished(late)
        assertNull(resolver.explorationCandidate(routes, first))
        now += 10_000_000_000
        assertEquals(second, resolver.explorationCandidate(routes, first))
        resolver.success(second, 65536, 100_000_000, onTime = true, startupProbe = true)
        assertEquals(0.75, resolver.schedulingConfidence(second))
        resolver.success(second, 512 * 1024, 500_000_000, onTime = false)
        assertEquals(0.75, resolver.schedulingConfidence(second))
        resolver.success(second, 512 * 1024, 500_000_000, onTime = true)
        assertEquals(0.8, resolver.schedulingConfidence(second))
        now += 2_000_000_000
        assertNull(resolver.explorationCandidate(routes, first))
    }

    @Test
    fun `probes rotate with bounded frequency even when original always succeeds`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(first, 100, 1_000_000_000)
        assertEquals(first, resolver.candidates(video).first())
        assertEquals(second, resolver.explorationCandidate(video, first, reserve = false))
        assertEquals(second, resolver.explorationCandidate(video, first, reserve = false))
        assertEquals(second, resolver.explorationCandidate(video, first))
        assertNull(resolver.explorationCandidate(video, first))
        now += 2_000_000_000
        assertEquals(third, resolver.explorationCandidate(video, first))
        now += 2_000_000_000
        assertNull(resolver.explorationCandidate(video, first))
        now += 6_000_000_000
        assertEquals(second, resolver.explorationCandidate(video, first))
    }

    @Test
    fun `video measurements do not rank audio but host failures suppress both`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(second, 10000, 1_000_000)
        assertEquals(second, resolver.candidates(video).first())
        val audio =
            video.copy(
                id = "a",
                kind = DownloadTrack.Audio,
                urls = video.urls.map { it.replace("video", "audio") },
            )
        assertEquals(first.replace("video", "audio"), resolver.candidates(audio).first())
        resolver.failure(second)
        assertNotEquals(second, resolver.candidates(video).first())
        assertEquals(third, resolver.explorationCandidate(video, first))
        now += 20_000_000_000
        assertNotNull(resolver.explorationCandidate(video, first))
    }

    @Test
    fun `idle feasible alternatives take useful work without changing per block speed`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(first, 2000, 1_000_000_000)
        resolver.success(second, 1500, 1_000_000_000)
        assertEquals(first, resolver.candidates(video).first())
        val firstRequest = resolver.requestStarted(first, 2000)
        assertEquals(1, firstRequest.concurrentRequests)
        assertEquals(second, resolver.candidates(video, 2000, now + 2_000_000_000L).first())
        resolver.requestProgress(firstRequest, 1500)
        assertEquals(first, resolver.candidates(video, 2000).first())
        val secondRequest = resolver.requestStarted(second, 2000)
        assertEquals(first, resolver.candidates(video, 2000).first())
        resolver.requestFinished(firstRequest)
        resolver.requestFinished(secondRequest)
        assertEquals(1_000_000_000L, resolver.estimatedTransferNanos(first, 2000))
        assertNull(resolver.estimatedTransferNanos(first.replace("video", "audio"), 2000))
        now += 16_000_000_000
        assertEquals(1_000_000_000L, resolver.estimatedTransferNanos(first, 2000))
    }

    @Test
    fun `request concurrency does not inflate finalized block speed or aggregate remaining work`() {
        val resolver = CdnResolver(ParallelDownloadConfig())
        resolver.success(first, 1000, 1_000_000_000, concurrentRequests = 4)
        resolver.success(second, 900, 1_000_000_000)
        repeat(3) { resolver.requestStarted(first, 1000) }
        assertEquals(first, resolver.candidates(video, 1000).first())
        resolver.requestStarted(first, 1000)
        assertEquals(first, resolver.candidates(video, 1000).first())
        assertEquals(1_000_000_000L, resolver.estimatedTransferNanos(first, 1000))
    }

    @Test
    fun `deadline eligibility spreads work over capable idle routes and uses busy capable route before slow idle`() {
        val now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig(), Random(4)) { now }
        resolver.success(first, 1000, 1_000_000_000)
        resolver.success(second, 500, 1_000_000_000)
        resolver.success(third, 250, 1_000_000_000)
        val deadline = now + 5_000_000_000
        assertEquals(setOf(first, second), List(30) { resolver.candidates(video, 2000, deadline).first() }.toSet())
        resolver.requestStarted(first, 20000)
        assertEquals(second, resolver.candidates(video, 2000, deadline).first())
        resolver.requestStarted(second, 1)
        assertEquals(first, resolver.candidates(video, 2000, deadline).first())
        // No route can meet this deadline: use the fastest finalized per-block estimate.
        assertEquals(first, resolver.candidates(video, 2000, now + 1).first())
        resolver.requestStarted(third, 1)
        assertEquals(first, resolver.candidates(video, 2000, now + 1).first())
    }

    @Test
    fun `rescue prefers idle without deadline but globally fastest when deadline unreachable`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        resolver.success(first, 10000, 1_000_000_000)
        resolver.success(second, 2000, 1_000_000_000)
        resolver.success(third, 1000, 1_000_000_000)
        // Audio activity must count as host occupancy without sharing its speed measurement.
        resolver.requestStarted(first.replace("video", "audio"), 1)
        val rescue = assertNotNull(resolver.reserveRescue(video, 1000, null, emptyList()))
        assertEquals(second, rescue.url)
        val next = assertNotNull(resolver.reserveRescue(video, 1000, 1_000_000_001L, emptyList()))
        assertEquals(first, next.url)
        assertEquals(third, assertNotNull(resolver.reserveRescue(video, 1000, null, emptyList())).url)
    }

    @Test
    fun `rescue excludes hosts before selection and respects cooldown without fallback`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        listOf(first, second, third).forEach { resolver.success(it, 1000, 1_000_000_000) }
        resolver.failure(second)
        val exclusion = listOf(first.replace("video", "audio"))
        assertEquals(third, assertNotNull(resolver.reserveRescue(video, 1000, null, exclusion)).url)
        resolver.failure(third)
        assertNull(resolver.reserveRescue(video, 1000, null, exclusion))
    }

    @Test
    fun `rescue randomizes capable idle hosts and falls back to unknown only without measurements`() {
        val now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig(), Random(4)) { now }
        val unknown = assertNotNull(resolver.reserveRescue(video, 1000, null, listOf(first, second)))
        assertEquals(third, unknown.url)
        resolver.requestFinished(unknown)
        listOf(first, second).forEach { resolver.success(it, 1000, 1_000_000_000) }
        val selections =
            List(30) {
                val assignment = assertNotNull(resolver.reserveRescue(video, 1000, now + 5_000_000_000, emptyList()))
                resolver.requestFinished(assignment)
                assignment.url
            }
        assertEquals(setOf(first, second), selections.toSet())
    }

    @Test
    fun `simultaneous rescues reserve distinct idle alternatives`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        listOf(second, third).forEach { resolver.success(it, 1000, 1_000_000_000) }
        val start = java.util.concurrent.CountDownLatch(1)
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val results =
                List(2) {
                    executor.submit<String> {
                        start.await()
                        assertNotNull(resolver.reserveRescue(video, 1000, null, listOf(first))).url
                    }
                }
            start.countDown()
            assertEquals(setOf(second, third), results.map { it.get(2, java.util.concurrent.TimeUnit.SECONDS) }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `manual pin forbids protected exploration`() {
        val resolver = CdnResolver(ParallelDownloadConfig(mode = CdnMode.Pinned, pinnedHost = "one.example"))
        assertNull(resolver.explorationCandidate(video, first))
    }
}

package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Actual dispatch uses current reserved load, not a ranking prepared while waiting in an executor. */
class OrdinaryReservationTest {
    private val fast = "https://fast.example/video?signature=preserved"
    private val slow = "https://slow.example/video?signature=preserved"
    private val track = MediaTrackSource("video", DownloadTrack.Video, listOf(fast, slow))

    @Test
    fun `reservation forecast uses finalized block speed independently of assigned load and idle age`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        resolver.success(fast, 1000, 1_000_000_000)
        val first = resolver.requestStarted(fast, 1000)
        assertEquals(1_000_000_000L, first.estimatedTransferNanos)
        resolver.requestProgress(first, 500)
        val second = resolver.requestStarted(fast, 1000)
        assertEquals(1_000_000_000L, second.estimatedTransferNanos)
        // A later completion cannot rewrite the snapshot used for the second request's grace.
        resolver.requestFinished(first)
        assertEquals(1_000_000_000L, second.estimatedTransferNanos)
        now += 16_000_000_000L
        assertEquals(1_000_000_000L, resolver.requestStarted(fast, 1000).estimatedTransferNanos)
        assertNull(resolver.requestStarted(slow, 1000).estimatedTransferNanos)
    }

    @Test
    fun `ordinary and rescue prefer feasible busy and fastest fallback over infeasible idle`() {
        for (rescue in listOf(false, true)) {
            val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
            resolver.success(fast, 10000, 1_000_000_000)
            resolver.success(slow, 1000, 1_000_000_000)
            resolver.requestStarted(fast, 1)

            fun choose(deadline: Long) =
                if (rescue) {
                    resolver.reserveRescue(track, 2000, deadline, emptyList())
                } else {
                    resolver.reserveOrdinary(track, 2000, deadline, emptyList())
                }
            val feasibleBusy = assertNotNull(choose(2_000_000_000L))
            assertEquals(fast, feasibleBusy.url)
            resolver.requestFinished(feasibleBusy)
            val fastestFallback = assertNotNull(choose(1_000_000_001L))
            assertEquals(fast, fastestFallback.url)
        }
    }

    @Test
    fun `simultaneous ordinary requests atomically reserve different feasible idle routes`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        listOf(fast, slow).forEach { resolver.success(it, 1000, 1_000_000_000) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val selections =
                List(2) {
                    executor.submit<String> {
                        start.await()
                        assertNotNull(resolver.reserveOrdinary(track, 1000, 5_000_000_000L, emptyList())).url
                    }
                }
            start.countDown()
            assertEquals(setOf(fast, slow), selections.map { it.get(2, TimeUnit.SECONDS) }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ordinary cooldown fallback is demanded bounded and forbidden for rescue`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        resolver.failure(fast)
        assertEquals(slow, assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList())).url)
        resolver.failure(slow)
        assertNull(resolver.reserveOrdinary(track, 1000, null, listOf(fast)))
        val recovery = assertNotNull(resolver.reserveOrdinary(track, 1000, null, listOf(fast), allowRecovery = true))
        assertEquals(slow, recovery.url)
        assertNull(resolver.reserveOrdinary(track, 1000, null, emptyList(), allowRecovery = true))
        assertNull(resolver.reserveRescue(track, 1000, null, emptyList()))
        assertNull(resolver.reserveOrdinary(track, 1000, null, listOf(fast, slow)))
    }

    @Test
    fun `only one concurrent demanded recovery across audio and video is admitted`() {
        val resolver = CdnResolver(ParallelDownloadConfig()) { 1_000_000_000L }
        listOf(fast, slow).forEach(resolver::failure)
        val audio = track.copy(id = "audio", kind = DownloadTrack.Audio)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val selections =
                List(8) { index ->
                    executor.submit<CdnResolver.RouteAssignment?> {
                        start.await()
                        resolver.reserveOrdinary(if (index % 2 == 0) track else audio, 1000, null, emptyList(), true)
                    }
                }
            start.countDown()
            val admitted = selections.mapNotNull { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, admitted.size)
            // Cancellation is a release, not a failure and does not impose another cooldown.
            resolver.requestFinished(admitted.single())
            assertNotNull(resolver.reserveOrdinary(audio, 1000, null, emptyList(), true))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed recovery releases admission but backs off and success restores normal dispatch`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        listOf(fast, slow).forEach(resolver::failure)
        val recovery = assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList(), true))
        resolver.failure(recovery.url)
        resolver.requestFinished(recovery)
        assertNull(resolver.reserveOrdinary(track, 1000, null, emptyList(), true))
        now += 1_000_000_000L
        val second = assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList(), true))
        resolver.success(second.url, 1000, 100_000_000)
        resolver.requestFinished(second)
        repeat(
            4,
        ) { assertEquals(second.url, assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList())).url) }
    }

    @Test
    fun `expired cooldown resumes ordinary requests without needing recovery admission`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        listOf(fast, slow).forEach(resolver::failure)
        assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList(), true))
        now += 6_000_000_000L
        repeat(4) { assertNotNull(resolver.reserveOrdinary(track, 1000, null, emptyList())) }
    }
}

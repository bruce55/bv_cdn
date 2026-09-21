package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finalized block evidence follows media position and survives seeks without mixing old epochs. */
class BlockSpeedWindowTest {
    @Test
    fun `media distance weights block rates independently of completion order and block byte size`() {
        fun window(reverse: Boolean): BlockSpeedWindow {
            val result = BlockSpeedWindow()
            val records = listOf(Triple(1000, 1_000_000_000L, 0L), Triple(1000000, 1_000_000_000L, 15000L))
            (if (reverse) records.reversed() else records).forEach { (bytes, nanos, time) ->
                result.add(bytes, nanos, time)
            }
            return result
        }
        val expected = (0.5 * 1000.0 / 1_000_000_000L + 1000000.0 / 1_000_000_000L) / 1.5
        assertEquals(expected, assertNotNull(window(false).estimate()), 1e-12)
        assertEquals(expected, assertNotNull(window(true).estimate()), 1e-12)
        assertEquals(15000L, window(true).furthestMediaTimeMs)
    }

    @Test
    fun `media window drops far older late completions and caps retained samples`() {
        val window = BlockSpeedWindow()
        repeat(100) { window.add(1000, 1_000_000_000, 100_000) }
        assertEquals(64, window.sampleCount)
        window.add(1, 10_000_000_000, 39_999)
        assertEquals(64, window.sampleCount)
        assertEquals(100_000L, window.furthestMediaTimeMs)
        window.add(2000, 1_000_000_000, 160_001)
        assertEquals(1, window.sampleCount)
        assertEquals(0.000002, assertNotNull(window.estimate()), 1e-12)
    }

    @Test
    fun `seek retains fallback until credible evidence then permanently replaces it`() {
        val window = BlockSpeedWindow()
        window.add(1048576, 1_000_000_000, 300_000)
        val old = assertNotNull(window.estimate())
        window.beginSeek()
        assertNull(window.furthestMediaTimeMs)
        repeat(6) { window.add(100, 100_000, it.toLong()) }
        assertEquals(old, window.estimate())
        assertEquals(0.0, window.newEvidenceWeight())
        window.add(262144, 500_000_000, 10)
        assertTrue(window.newEvidenceWeight() in 0.25..0.3)
        val blended = assertNotNull(window.estimate())
        assertTrue(blended < old)
        window.add(1048576, 4_000_000_000, 100)
        assertEquals(1.0, window.newEvidenceWeight())
        window.add(1000, 1_000_000, 100_000)
        assertEquals(0.001, assertNotNull(window.estimate()), 1e-12)
        assertEquals(1.0, window.newEvidenceWeight())
    }

    @Test
    fun `unindexed bootstrap includes full latency but does not invent media position`() {
        val window = BlockSpeedWindow()
        window.add(1000, 2_000_000_000, null)
        assertEquals(0.0000005, assertNotNull(window.estimate()), 1e-12)
        assertNull(window.furthestMediaTimeMs)
        assertEquals(0, window.sampleCount)
        window.beginSeek()
        window.add(1000000, 1, null)
        assertEquals(0.0000005, assertNotNull(window.estimate()), 1e-12)
        assertNull(window.furthestMediaTimeMs)
    }

    @Test
    fun `resolver ignores late old epoch speed but preserves independent health recovery`() {
        var now = 1_000_000_000L
        val resolver = CdnResolver(ParallelDownloadConfig()) { now }
        val url = "https://cdn.example/video"
        resolver.success(url, 1048576, 1_000_000_000, mediaTimeMs = 100_000)
        val old = resolver.requestStarted(url, 1048576)
        resolver.failure(url)
        val epoch = resolver.beginSeek()
        assertEquals(epoch, resolver.currentEvidenceEpoch())
        assertTrue(epoch > old.evidenceEpoch)
        resolver.success(url, 1, 100_000_000_000, mediaTimeMs = 200_000, evidenceEpoch = old.evidenceEpoch)
        assertEquals(0L, resolver.cooldownRemainingMs(url))
        assertEquals(1_000_000_000.0, assertNotNull(resolver.estimatedTransferNanos(url, 1048576)).toDouble(), 1.0)
        now += 100_000_000_000L
        assertEquals(1_000_000_000.0, assertNotNull(resolver.estimatedTransferNanos(url, 1048576)).toDouble(), 1.0)
        resolver.success(url, 1048576, 4_000_000_000, mediaTimeMs = 1000, evidenceEpoch = epoch)
        assertEquals(4_000_000_000.0, assertNotNull(resolver.estimatedTransferNanos(url, 1048576)).toDouble(), 1.0)
        resolver.success(url, 1048576, 1, mediaTimeMs = 200_000, evidenceEpoch = old.evidenceEpoch)
        assertEquals(4_000_000_000.0, assertNotNull(resolver.estimatedTransferNanos(url, 1048576)).toDouble(), 1.0)
    }

    @Test
    fun `latency and concurrency are not stripped or multiplied in transfer prediction`() {
        val resolver = CdnResolver(ParallelDownloadConfig())
        val url = "https://cdn.example/video"
        resolver.success(
            url,
            1000,
            2_000_000_000,
            concurrentRequests = 8,
            latencyNanos = 1_000_000_000,
            mediaTimeMs = 0,
        )
        repeat(8) { resolver.requestStarted(url, 1000000) }
        assertEquals(2_000_000_000L, resolver.estimatedTransferNanos(url, 1000))
        assertEquals(1_000_000_000L, resolver.remainingTransferNanos(url, 500))
        assertNull(resolver.estimatedTransferNanos(url.replace("video", "audio"), 1000))
    }
}

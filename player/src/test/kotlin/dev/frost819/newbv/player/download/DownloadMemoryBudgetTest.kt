package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Memory bounds scale with concurrency while preserving protected recovery headroom. */
class DownloadMemoryBudgetTest {
    @Test
    fun `speculation leaves enough charged capacity for a full demanded range`() {
        for (heap in listOf(64L, 192L, 512L)) {
            for (workers in listOf(2, 8, 64)) {
                val budget = DownloadMemoryBudget(workers, 4 * MIB, heap * MIB)
                val reserve = 2 * budget.planningSpanLimitBytes
                while (budget.tryReservePayload(64 * 1024L, reserve)) { }
                assertTrue(budget.tryReservePayload(budget.planningSpanLimitBytes))
                assertTrue(budget.usage().first <= budget.sharedLimitBytes)
            }
        }
    }

    @Test
    fun `64 large blocks cannot consume heap beyond copy inclusive allowance`() {
        val budget = DownloadMemoryBudget(64, 4 * MIB, 512 * MIB)
        var admitted = 0
        repeat(64) { if (budget.tryReservePayload(4 * MIB)) admitted++ }
        assertEquals(12, admitted)
        assertEquals(96 * MIB, budget.usage().first)
        assertFalse(budget.tryReservePayload(1))
        budget.releasePayload(4 * MIB)
        assertTrue(budget.tryReservePayload(4 * MIB))
    }

    @Test
    fun `configured concurrency scales read ahead beyond one planning span`() {
        val ordinary = DownloadMemoryBudget(8, 512 * 1024, 1024 * MIB)
        val parallel = DownloadMemoryBudget(64, 512 * 1024, 1024 * MIB)
        assertEquals(32 * MIB, ordinary.payloadLimitBytes)
        assertEquals(16 * MIB, ordinary.rescueLimitBytes)
        assertEquals(128 * MIB, parallel.payloadLimitBytes)
        assertEquals(32 * MIB, parallel.rescueLimitBytes)
        assertEquals(8 * MIB, parallel.planningSpanLimitBytes)
        assertEquals(ordinary.payloadLimitBytes, DownloadMemoryBudget(8, 0, 1024 * MIB).payloadLimitBytes)
    }

    @Test
    fun `large blocks are bounded by both device heap and absolute ceilings`() {
        val lowMemory = DownloadMemoryBudget(64, 4 * MIB, 128 * MIB)
        assertEquals(16 * MIB, lowMemory.payloadLimitBytes)
        assertEquals(8 * MIB, lowMemory.rescueLimitBytes)
        assertTrue(lowMemory.tryReservePayload(4 * MIB))
        assertTrue(lowMemory.tryReserveRescue(4 * MIB))
        val highMemory = DownloadMemoryBudget(64, 4 * MIB, 4096 * MIB)
        assertEquals(128 * MIB, highMemory.payloadLimitBytes)
        assertEquals(64 * MIB, highMemory.rescueLimitBytes)
        val tinyHeap = DownloadMemoryBudget(8, 512 * 1024, 16 * MIB)
        assertEquals(2 * MIB, tinyHeap.payloadLimitBytes)
        assertEquals(MIB / 2, tinyHeap.planningSpanLimitBytes)
        assertEquals(MIB, tinyHeap.rescueLimitBytes)
    }

    @Test
    fun `automatic large pieces retain enough rescue capacity with few workers`() {
        val budget = DownloadMemoryBudget(4, 0, 256 * MIB)
        assertEquals(16 * MIB, budget.payloadLimitBytes)
        assertEquals(16 * MIB, budget.rescueLimitBytes)
        assertTrue(budget.tryReserveRescue(budget.planningSpanLimitBytes))
        budget.releaseRescue(budget.planningSpanLimitBytes)
        assertTrue(budget.tryReserveRescue(2 * MIB))
        val smallHeap = DownloadMemoryBudget(4, 0, 64 * MIB)
        assertEquals(2 * MIB, smallHeap.planningSpanLimitBytes)
        assertTrue(smallHeap.tryReserveRescue(smallHeap.planningSpanLimitBytes))
    }

    @Test
    fun `filled ordinary budget does not consume rescue capacity and release permits reuse`() {
        val budget = DownloadMemoryBudget(8, 512 * 1024, 256 * MIB)
        assertTrue(budget.tryReservePayload(budget.sharedLimitBytes / 2))
        assertFalse(budget.tryReservePayload(1))
        assertTrue(budget.tryReserveRescue(budget.rescueLimitBytes / 2))
        assertFalse(budget.tryReserveRescue(1))
        budget.releasePayload(MIB)
        assertTrue(budget.tryReservePayload(MIB))
        assertFalse(budget.tryReservePayload(1))
        budget.releaseRescue(MIB)
        assertTrue(budget.tryReserveRescue(MIB))
        assertFalse(budget.tryReserveRescue(1))
        budget.releasePayload(budget.sharedLimitBytes / 2)
        budget.releaseRescue(budget.rescueLimitBytes / 2)
        assertTrue(budget.tryReservePayload(budget.sharedLimitBytes / 2))
        assertTrue(budget.tryReserveRescue(budget.rescueLimitBytes / 2))
    }

    @Test
    fun `simultaneous readers cannot overbook the shared budget`() {
        val budget = DownloadMemoryBudget(8, 512 * 1024, 256 * MIB)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val reservations = executor.invokeAll(List(64) { Callable { budget.tryReservePayload(MIB) } })
            assertEquals(24, reservations.count { it.get() })
            val rescues = executor.invokeAll(List(64) { Callable { budget.tryReserveRescue(MIB) } })
            assertEquals(8, rescues.count { it.get() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `payload and cache ownership of the same array charge shared bytes once`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val body = ByteArray(4096)
        assertTrue(budget.tryReservePayload(body.size.toLong()))
        budget.attachPayload(body)
        assertTrue(budget.retainCached(body))
        assertTrue(budget.retainCached(body))
        assertEquals(8192L, budget.snapshot().sharedUsedBytes)
        assertEquals(4096L, budget.snapshot().cachedArrayBytes)
        assertEquals(0L, budget.snapshot().cachedOnlyBytes)
        budget.releasePayload(body.size.toLong(), body)
        assertEquals(4096L, budget.snapshot().sharedUsedBytes)
        assertEquals(4096L, budget.snapshot().cachedOnlyBytes)
        budget.releaseCached(body)
        assertEquals(4096L, budget.snapshot().sharedUsedBytes)
        budget.releaseCached(body)
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
        assertEquals(0L, budget.snapshot().cachedArrayBytes)
    }

    @Test
    fun `cached bytes keep consuming shared capacity until cache ownership releases`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val body = ByteArray((budget.sharedLimitBytes / 2).toInt())
        assertTrue(budget.tryReservePayload(body.size.toLong()))
        budget.attachPayload(body)
        assertTrue(budget.retainCached(body))
        assertFalse(budget.tryReservePayload(1))
        budget.releasePayload(body.size.toLong(), body)
        assertEquals(body.size.toLong(), budget.snapshot().sharedUsedBytes)
        assertFalse(budget.tryReservePayload(budget.sharedLimitBytes / 2))
        val remaining = (budget.sharedLimitBytes - body.size) / 2
        assertTrue(budget.tryReservePayload(remaining))
        assertFalse(budget.tryReservePayload(1))
        budget.releaseCached(body)
        assertTrue(budget.tryReservePayload(body.size / 2L))
    }

    @Test
    fun `cache only admission competes with ordinary reservations and obeys headroom`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val cached = ByteArray((budget.sharedLimitBytes - 8192).toInt())
        assertTrue(budget.retainCached(cached))
        assertFalse(budget.retainCached(ByteArray(8193)))
        assertFalse(budget.tryReservePayload(4096, demandHeadroom = 1))
        assertTrue(budget.tryReservePayload(2048, demandHeadroom = 4096))
        assertTrue(budget.tryReservePayload(2048))
        assertFalse(budget.tryReservePayload(1))
        assertEquals(budget.sharedLimitBytes, budget.snapshot().sharedUsedBytes)
        assertTrue(budget.tryReserveRescue(budget.rescueLimitBytes / 2))
    }

    @Test
    fun `multiple payload owners transfer to cached only after final ordinary release`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val body = ByteArray(4096)
        repeat(2) {
            assertTrue(budget.tryReservePayload(body.size.toLong()))
            budget.attachPayload(body)
        }
        assertTrue(budget.retainCached(body))
        budget.releasePayload(body.size.toLong(), body)
        assertEquals(8192L, budget.snapshot().sharedUsedBytes)
        assertEquals(0L, budget.snapshot().cachedOnlyBytes)
        budget.releasePayload(body.size.toLong(), body)
        assertEquals(4096L, budget.snapshot().sharedUsedBytes)
        budget.releaseCached(body)
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
    }

    @Test
    fun `cached owner can be dropped while payload ownership continues`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val body = ByteArray(4096)
        assertTrue(budget.retainCached(body))
        assertTrue(budget.tryReservePayload(body.size.toLong()))
        budget.attachPayload(body)
        assertEquals(8192L, budget.snapshot().sharedUsedBytes)
        budget.releaseCached(body)
        assertEquals(8192L, budget.snapshot().sharedUsedBytes)
        budget.releasePayload(body.size.toLong(), body)
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
    }

    @Test
    fun `rescues borrow ordinary capacity but never exceed total budget`() {
        val budget = DownloadMemoryBudget(8, 512 * 1024, 256 * MIB)
        val total = budget.sharedLimitBytes + budget.rescueLimitBytes
        assertTrue(budget.tryReserveRescue(total / 2))
        assertFalse(budget.tryReserveRescue(1))
        assertFalse(budget.tryReservePayload(1))
        assertEquals(total, budget.usage().second)
        budget.releaseRescue(total / 2)
        assertTrue(budget.tryReservePayload(budget.sharedLimitBytes / 2))
    }

    @Test
    fun `pending claim protects reclaimed space until converted or canceled`() {
        val budget = DownloadMemoryBudget(8, 512 * 1024, 256 * MIB)
        val ordinary = budget.sharedLimitBytes / 2
        assertTrue(budget.tryReservePayload(ordinary))
        assertTrue(budget.tryReserveRescue(budget.rescueLimitBytes / 2))
        budget.claimRecovery("next", MIB)
        budget.claimRecovery("next", MIB)
        assertEquals(2 * MIB, budget.recoveryShortfall(MIB, "next"))
        assertFalse(budget.canReserveRescue(MIB, "next"))
        // Cancellation is only intent. Capacity is unavailable until its owner really releases.
        assertFalse(budget.tryReserveRescue(MIB, "next"))
        budget.releasePayload(MIB)
        assertFalse(budget.tryReservePayload(1))
        assertFalse(budget.retainCached(ByteArray(1)))
        assertTrue(budget.canReserveRescue(MIB, "next"))
        assertTrue(budget.tryReserveRescue(MIB, "next"))
        assertEquals(0L, budget.capacityFields()["pendingRecoveryBytes"])
        budget.releaseRescue(MIB)
        budget.claimRecovery("canceled", MIB)
        assertFalse(budget.tryReservePayload(1))
        budget.releaseRecoveryClaim("canceled")
        assertTrue(budget.tryReservePayload(MIB))
    }

    @Test
    fun `recovery original borrows reserve without duplicate charge then transfers to cached ownership`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val ordinary = budget.sharedLimitBytes / 2
        assertTrue(budget.tryReservePayload(ordinary))
        val body = ByteArray((MIB / 2).toInt())
        budget.claimRecovery("head", body.size.toLong())
        assertTrue(budget.tryReserveRecoveryPayload("head", body.size.toLong()))
        assertEquals(ordinary * 2 + MIB, budget.usage().first)
        assertEquals(0L, budget.usage().second)
        budget.attachPayload(body)
        assertTrue(budget.retainCached(body))
        assertFalse(budget.tryReservePayload(1))
        budget.releasePayload(body.size.toLong(), body, "head")
        assertEquals(body.size.toLong(), budget.snapshot().cachedOnlyBytes)
        assertEquals(0L, budget.capacityFields()["recoveryPayloadBytes"])
        budget.releaseCached(body)
        assertEquals(ordinary * 2, budget.snapshot().sharedUsedBytes)
    }

    @Test
    fun `pending recoveries exceeding capacity do not mutually block the selected winner`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val total = budget.sharedLimitBytes + budget.rescueLimitBytes
        val rangeBytes = total / 2
        budget.claimRecovery("audio", rangeBytes)
        budget.claimRecovery("video", rangeBytes)
        assertFalse(budget.tryReservePayload(1))
        assertFalse(budget.retainCached(ByteArray(1)))
        assertTrue(budget.canReserveRescue(rangeBytes, "audio"))
        assertTrue(budget.canReserveRescue(rangeBytes, "video"))
        assertEquals(0L, budget.recoveryShortfall(rangeBytes, "video"))
        assertTrue(budget.tryReserveRescue(rangeBytes, "audio"))
        assertEquals(total, budget.usage().second)
        assertFalse(budget.tryReserveRescue(rangeBytes, "video"))
        assertEquals(total, budget.recoveryShortfall(rangeBytes, "video"))
        budget.releaseRescue(rangeBytes)
        assertFalse(budget.tryReservePayload(1))
        assertTrue(budget.tryReserveRecoveryPayload("video", rangeBytes))
        assertEquals(total, budget.usage().first)
        assertEquals(0L, budget.usage().second)
        assertEquals(0L, budget.capacityFields()["pendingRecoveryBytes"])
        budget.releasePayload(rangeBytes, recoveryKey = "video")
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
    }

    @Test
    fun `pressure never frees running recovery or charges shared array twice`() {
        val budget = DownloadMemoryBudget(8, 0, 16 * MIB)
        val body = ByteArray(4096)
        assertTrue(budget.tryReserveRecoveryPayload("head", body.size.toLong()))
        budget.attachPayload(body)
        budget.refreshMemory(DownloadMemorySnapshot(16 * MIB, 0, 0, 64 * MIB, MIB, true))
        assertFalse(budget.canReserveRescue(1))
        assertTrue(budget.retainCached(body))
        assertEquals(8192L, budget.snapshot().sharedUsedBytes)
        budget.releasePayload(body.size.toLong(), body, "head")
        assertEquals(4096L, budget.snapshot().sharedUsedBytes)
        budget.releaseCached(body)
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
    }

    private companion object {
        const val MIB = 1024 * 1024L
    }
}

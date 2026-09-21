package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveDownloadBudgetTest {
    private val mib = 1024 * 1024L

    private fun budget() =
        DownloadMemoryBudget(8, mib / 2, 512 * mib).apply {
            configureTracks(setOf(DownloadTrack.Video, DownloadTrack.Audio))
            observeBlock(DownloadTrack.Video, mib)
            observeBlock(DownloadTrack.Audio, mib / 8)
        }

    private fun roomy() = DownloadMemorySnapshot(64 * mib, 0, 2048 * mib, 4096 * mib, 64 * mib, false)

    @Test
    fun `actual shape sets reserve and charged heads are not reserved twice`() {
        val budget = budget()
        assertEquals(8 * mib, budget.rescueLimitBytes)
        assertEquals(2 * mib + mib / 4, budget.demandReserve(emptyMap()))
        assertEquals(mib / 4, budget.demandReserve(mapOf(DownloadTrack.Video to 2 * mib)))
        assertEquals(0L, budget.demandReserve(mapOf(DownloadTrack.Video to 2 * mib, DownloadTrack.Audio to mib / 4)))
        budget.observeBlock(DownloadTrack.Video, 4 * mib)
        assertEquals(8 * mib + mib / 4, budget.demandReserve(emptyMap()))
        assertTrue(budget.rescueLimitBytes > 8 * mib)
    }

    @Test
    fun `capacity grows on demand only with pressure evidence and stays under half heap`() {
        val budget = budget()
        val initial = budget.sharedLimitBytes
        assertTrue(budget.tryReservePayload(initial / 2))
        assertFalse(budget.tryReservePayload(mib))
        budget.refreshMemory(roomy())
        assertEquals(initial, budget.sharedLimitBytes)
        assertTrue(budget.tryReservePayload(mib))
        assertTrue(budget.sharedLimitBytes > initial)
        while (budget.tryReservePayload(mib)) { }
        assertTrue(budget.snapshot().sharedUsedBytes + budget.rescueLimitBytes + 128 * mib <= 256 * mib)
    }

    @Test
    fun `device pressure pauses allocations without releasing owned bytes and can recover`() {
        val budget = budget()
        assertTrue(budget.tryReservePayload(mib))
        val before = budget.snapshot().sharedUsedBytes
        budget.refreshMemory(roomy().copy(deviceLowMemory = true))
        assertEquals(before, budget.snapshot().sharedUsedBytes)
        assertFalse(budget.tryReservePayload(1))
        assertFalse(budget.tryReserveRescue(1))
        budget.refreshMemory(roomy())
        assertTrue(budget.tryReservePayload(mib))
    }

    @Test
    fun `shrinking rescue target still accounts for active duplicate reservations`() {
        val budget = budget()
        assertTrue(budget.tryReserveRescue(4 * mib))
        budget.observeBlock(DownloadTrack.Video, mib / 8)
        assertEquals(mib, budget.rescueLimitBytes)
        assertTrue(budget.tryReserveRescue(1))
        budget.releaseRescue(1)
        val occupied = budget.sharedLimitBytes
        budget.releaseRescue(4 * mib)
        assertEquals(occupied + 7 * mib, budget.sharedLimitBytes)
    }

    @Test
    fun `heap and device limits each independently prevent growth`() {
        for (sample in listOf(
            roomy().copy(heapUsedBytes = 500 * mib),
            roomy().copy(deviceAvailableBytes = 64 * mib),
        )) {
            val budget = budget()
            budget.refreshMemory(sample)
            assertFalse(budget.tryReservePayload(1))
        }
    }
}

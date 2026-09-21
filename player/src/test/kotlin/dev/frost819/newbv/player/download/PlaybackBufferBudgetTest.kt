package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackBufferBudgetTest {
    @Test
    fun `combined budgets leave at least half the heap at every worker and block setting`() {
        for (heapMiB in listOf(16, 64, 128, 192, 256, 512, 1024, 4096)) {
            val heap = heapMiB * 1024 * 1024L
            val budget = PlaybackBufferBudget(heap)
            for (threads in listOf(2, 8, 16, 64)) {
                for (block in listOf(0L, 64 * 1024L, 512 * 1024L, 4 * 1024 * 1024L)) {
                    val downloads = DownloadMemoryBudget(threads, block, heap)
                    val total =
                        budget.media3Bytes + budget.cacheBytes +
                            downloads.payloadLimitBytes + downloads.rescueLimitBytes
                    assertTrue(total <= heap / 2)
                    assertTrue(downloads.planningSpanLimitBytes <= downloads.rescueLimitBytes)
                }
            }
        }
        assertEquals(48 * 1024 * 1024, PlaybackBufferBudget(192 * 1024 * 1024L).media3Bytes)
        assertEquals(128 * 1024 * 1024, PlaybackBufferBudget(512 * 1024 * 1024L).media3Bytes)
    }
}

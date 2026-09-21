package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Byte accounting distinguishes unique pending data from duplicate or discarded HTTP body bytes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTransferStatsTest {
    @Test
    fun `duplicate attempts and cache rereads do not inflate unique usage with diagnostics disabled`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.routeProgress("one", 100)
            monitor.routeProgress("two", 100)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(200, 100, 0, 100))
            monitor.close()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats())
        }

    @Test
    fun `resident and in flight unions become used without becoming overhead`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) {
                mapOf(DownloadTrack.Video to listOf(0L..99L))
            }
            monitor.setInFlightSampler { mapOf(DownloadTrack.Video to listOf(50L..149L, 50L..99L)) }
            monitor.routeProgress("one", 200)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(200, 0, 150, 50))
            monitor.consumedBytes(DownloadTrack.Video, 0, 75)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(200, 75, 75, 50))
            monitor.close()
        }

    @Test
    fun `eviction and cancelled partial requests move pending bytes to overhead`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) {
                mapOf(DownloadTrack.Video to listOf(0L..99L))
            }
            monitor.setInFlightSampler { mapOf(DownloadTrack.Video to listOf(100L..149L)) }
            monitor.routeProgress("one", 150)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(150, 0, 150, 0))
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) { emptyMap() }
            monitor.setInFlightSampler { emptyMap() }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(150, 0, 0, 150))
            monitor.close()
        }

    @Test
    fun `audio and video use independent byte address spaces`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.routeProgress("one", 200)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.setInFlightSampler { mapOf(DownloadTrack.Audio to listOf(0L..99L)) }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(200, 100, 100, 0))
            monitor.consumedBytes(DownloadTrack.Audio, 0, 100)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.transferStats).isEqualTo(DownloadTransferStats(200, 200, 0, 0))
            monitor.close()
        }

    @Test
    fun `ledger coalesces sequential overlapping reads and preserves holes`() {
        val ledger = DownloadByteLedger()
        repeat(1024) { ledger.add(it * 4096L, (it + 1) * 4096L - 1) }
        ledger.add(0, 4095)
        ledger.add(5L * 1024 * 1024, 6L * 1024 * 1024 - 1)
        assertThat(ledger.totalBytes()).isEqualTo(5L * 1024 * 1024)
        assertThat(ledger.unconsumedBytes(listOf(0L until 6L * 1024 * 1024))).isEqualTo(1024L * 1024)
    }

    @Test
    fun `ledger endpoints and byte counters saturate without wrapping negative`() {
        val ledger = DownloadByteLedger()
        ledger.add(0, Long.MAX_VALUE)
        ledger.add(Long.MAX_VALUE, Long.MAX_VALUE)
        assertThat(ledger.totalBytes()).isEqualTo(Long.MAX_VALUE)
        assertThat(ledger.unconsumedBytes(listOf(0L..Long.MAX_VALUE))).isEqualTo(0)
        assertThat(saturatedByteSum(Long.MAX_VALUE - 5, 10)).isEqualTo(Long.MAX_VALUE)
    }
}

package dev.frost819.newbv.player.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Observed event totals remain independent of file-range deduplication and player residency. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTransferMeasurementsTest {
    @Test
    fun `presentation uses exact provenance instead of the file offset residual`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val ledger = ExactTransferLedger()
            monitor.setExactTransferSampler(ledger::snapshot)
            val source = ledger.newSource()
            source.received(100)
            val body = source.view(0, 100)
            source.finish()
            body.delivered(0, 50)
            monitor.routeProgress("cdn", 100)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(DownloadTransferStats(100, 50, 50, 0, exact = true), monitor.snapshots.value.transferStats)
            body.close()
            monitor.clearPlayerBuffer()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(DownloadTransferStats(100, 50, 0, 50, exact = true), monitor.snapshots.value.transferStats)
            monitor.close()
        }

    @Test
    fun `cache replay and redownload are visible without treating player release as discarded traffic`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.routeProgress("cdn", 100)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.clearPlayerBuffer()
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.routeProgress("cdn", 100)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val measured = monitor.snapshots.value.transferMeasurements
            assertEquals(200L, measured.downloadedBodyBytes)
            assertEquals(300L, measured.deliveredToPlayerBytes)
            assertEquals(100L, measured.uniqueDeliveredBytes)
            assertEquals(200L, measured.fields()["repeatDeliveryBytes"])
            assertEquals(0L, measured.confirmedTransportDiscardedBytes)
            monitor.close()
            assertEquals(DownloadTransferMeasurements(), monitor.snapshots.value.transferMeasurements)
        }

    @Test
    fun `resident and inflight ranges are separate observations with a deduplicated combined count`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.routeProgress("cdn", 220)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) {
                mapOf(DownloadTrack.Video to listOf(0L..99L, 0L..99L))
            }
            monitor.setInFlightSampler {
                mapOf(DownloadTrack.Video to listOf(50L..149L), DownloadTrack.Audio to listOf(0L..19L))
            }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val measured = monitor.snapshots.value.transferMeasurements
            assertEquals(100L, measured.residentUniqueBytes)
            assertEquals(120L, measured.inFlightUniqueBytes)
            assertEquals(170L, measured.retainedUniqueBytes)
            assertEquals(0L, measured.confirmedTransportDiscardedBytes)
            monitor.close()
        }

    @Test
    fun `only observed discard events increase discard counter including unclassified transport losses`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            monitor.routeProgress("cdn", 200)
            monitor.discardedTransfer(DownloadOverheadKind.Other, 30)
            monitor.discardedTransfer(DownloadOverheadKind.Other, -1)
            monitor.clearPlayerBuffer()
            monitor.setAvailabilitySampler(setOf(DownloadTrack.Video)) { emptyMap() }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(30L, monitor.snapshots.value.transferMeasurements.confirmedTransportDiscardedBytes)
            // The old residual is not evidence that all 200 bytes were actually thrown away.
            assertEquals(200L, monitor.snapshots.value.transferStats.overheadBytes)
            monitor.close()
        }
}

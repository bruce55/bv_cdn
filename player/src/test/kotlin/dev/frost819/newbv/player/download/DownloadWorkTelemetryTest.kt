package dev.frost819.newbv.player.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWorkTelemetryTest {
    @Test
    fun `active slots stay stable while freed slots are reused and capacity is bounded`() {
        val telemetry = DownloadWorkTelemetry(2)
        telemetry.started(1, "one.example", DownloadTrack.Video, DownloadWorkKind.Ordinary)
        telemetry.started(2, "two.example", DownloadTrack.Audio, DownloadWorkKind.Probe)
        telemetry.started(3, "three.example", DownloadTrack.Video, DownloadWorkKind.Rescue)
        assertEquals(listOf(1L, 2L), telemetry.workers().map { it.attemptId })
        telemetry.finished(1)
        assertEquals(1, telemetry.workers().single().slot)
        telemetry.started(3, "three.example", DownloadTrack.Video, DownloadWorkKind.Rescue)
        assertEquals(listOf(3L, 2L), telemetry.workers().map { it.attemptId })
        telemetry.started(2, "duplicate.example", DownloadTrack.Video, DownloadWorkKind.Ordinary)
        assertEquals("two.example", telemetry.workers().last().host)
        telemetry.clear()
        assertTrue(telemetry.workers().isEmpty())
    }

    @Test
    fun `work kinds do not become overhead until discarded bytes are explicitly confirmed`() {
        val telemetry = DownloadWorkTelemetry(8)
        telemetry.started(1, "one.example", DownloadTrack.Video, DownloadWorkKind.Probe)
        telemetry.finished(1)
        assertEquals(listOf(DownloadOverhead(DownloadOverheadKind.Other, 100)), telemetry.overhead(100))
        telemetry.discarded(DownloadOverheadKind.Probe, 30)
        telemetry.discarded(DownloadOverheadKind.Rescue, 20)
        assertEquals(
            listOf(
                DownloadOverhead(DownloadOverheadKind.Probe, 30),
                DownloadOverhead(DownloadOverheadKind.Rescue, 20),
                DownloadOverhead(DownloadOverheadKind.Other, 50),
            ),
            telemetry.overhead(100),
        )
        assertEquals(10L, telemetry.overhead(10).sumOf { it.bytes })
        assertTrue(telemetry.overhead(0).isEmpty())
        assertEquals(100L, telemetry.overhead(100).sumOf { it.bytes })
    }

    @Test
    fun `monitor publishes slots wait reason and exact waste decomposition without double counting receipt`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(diagnosticsEnabled = true), backgroundScope)
            monitor.workerStarted(7, "one.example", DownloadTrack.Video, DownloadWorkKind.Probe)
            monitor.routeProgress("one.example", 200)
            monitor.consumedBytes(DownloadTrack.Video, 0, 100)
            monitor.discardedTransfer(DownloadOverheadKind.Probe, 40)
            monitor.schedulerWait("memory_watermark")
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val snapshot = monitor.snapshots.value
            assertEquals(200L, snapshot.transferStats.downloadedBytes)
            assertEquals(100L, snapshot.transferStats.overheadBytes)
            assertEquals(
                listOf(
                    DownloadOverhead(DownloadOverheadKind.Probe, 40),
                    DownloadOverhead(DownloadOverheadKind.Other, 60),
                ),
                snapshot.transferStats.overheadBreakdown,
            )
            assertEquals(7L, snapshot.workers.single().attemptId)
            assertEquals("memory_watermark", snapshot.waitReason)
            monitor.workerStarted(8, "two.example", DownloadTrack.Audio, DownloadWorkKind.Ordinary)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(null, monitor.snapshots.value.waitReason)
            monitor.workerFinished(8)
            monitor.workerFinished(7)
            monitor.schedulerWait(null)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertTrue(
                monitor.snapshots.value.workers
                    .isEmpty(),
            )
            assertEquals(null, monitor.snapshots.value.waitReason)
            monitor.close()
        }

    @Test
    fun `seeking cannot trigger rebuffer penalties until ready again and never resets prior readiness`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            assertTrue(monitor.isAwaitingReadiness())
            monitor.updatePlayback(0, 1f, true, false, true)
            assertFalse(monitor.hasStartedPlayback())
            assertFalse(monitor.isRebuffering())
            monitor.updatePlayback(0, 1f, true, true, false)
            assertTrue(monitor.hasStartedPlayback())
            assertFalse(monitor.isAwaitingReadiness())
            monitor.beginSeek(50000)
            assertTrue(monitor.isAwaitingReadiness())
            monitor.updatePlayback(50000, 1f, true, false, true)
            assertTrue(monitor.hasStartedPlayback())
            assertFalse(monitor.isRebuffering())
            monitor.updatePlayback(50000, 1f, false, false, true)
            assertTrue(monitor.isAwaitingReadiness())
            assertFalse(monitor.isRebuffering())
            monitor.updatePlayback(50000, 1f, true, false, true)
            assertFalse(monitor.isRebuffering())
            monitor.updatePlayback(50000, 1f, true, false, false, isReady = true)
            assertFalse(monitor.isAwaitingReadiness())
            monitor.updatePlayback(51000, 1f, true, false, true)
            assertTrue(monitor.isRebuffering())
            monitor.close()
        }

    @Test
    fun `paused fresh seek still requires initial readiness`() {
        val scheduling = PlaybackSchedulingTracker()
        assertTrue(scheduling.awaitingReadiness)
        scheduling.beginSeek()
        scheduling.update(false, false, true, false)
        assertEquals(PlaybackSchedulingState.Paused, scheduling.state)
        assertFalse(scheduling.everReady)
        assertTrue(scheduling.awaitingReadiness)
        scheduling.update(true, false, true, false)
        assertEquals(PlaybackSchedulingState.Seeking, scheduling.state)
        scheduling.update(false, false, false, true)
        assertTrue(scheduling.everReady)
        assertFalse(scheduling.awaitingReadiness)
        assertEquals(PlaybackSchedulingState.Paused, scheduling.state)
    }
}

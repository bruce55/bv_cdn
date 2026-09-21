package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Ensures telemetry is index-derived and old asynchronous updates cannot reappear after close. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadMonitorTest {
    @Test
    fun `startup status shows validated counts and hides when ready even while paused`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(diagnosticsEnabled = true), backgroundScope)
            monitor.startupProbeStatus(5, 3, true)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.videoProbes).isEqualTo(5)
            assertThat(monitor.snapshots.value.audioProbes).isEqualTo(3)
            assertThat(monitor.snapshots.value.startupProbing).isTrue()
            monitor.updatePlayback(0, 1f, false, false, false, isReady = true)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.startupProbing).isFalse()
            assertThat(monitor.snapshots.value.audioProbes).isEqualTo(3)
            monitor.close()
        }

    @Test
    fun `monitor preserves configured 64 request ceiling across updates and closure`() =
        runTest {
            val monitor =
                DownloadMonitor(
                    ParallelDownloadConfig(maxRequests = 64, diagnosticsEnabled = true),
                    backgroundScope,
                )
            assertThat(monitor.snapshots.value.maxRequests).isEqualTo(64)
            monitor.requestStarted()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.maxRequests).isEqualTo(64)
            monitor.close()
            assertThat(monitor.snapshots.value.maxRequests).isEqualTo(64)
        }

    @Test
    fun `diagnostics work without blocks and cancellation does not poison host health`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(diagnosticsEnabled = true), backgroundScope)
            monitor.routeCandidates(listOf("unused.example", "used.example"))
            monitor.routeStarted("used.example")
            monitor.routeFinished("used.example", 1024, 10, null, cancelled = true)
            monitor.routeStarted("used.example")
            monitor.routeFinished("used.example", 0, 20, "HTTP 403", 6000)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val snapshot = monitor.snapshots.value
            assertThat(snapshot.blocks).isEmpty()
            val unused = snapshot.routes.first { it.host == "unused.example" }
            assertThat(unused.successes + unused.failures).isEqualTo(0)
            val used = snapshot.routes.first { it.host == "used.example" }
            assertThat(used.attempts).isEqualTo(2)
            assertThat(used.activeRequests).isEqualTo(0)
            assertThat(used.successes).isEqualTo(0)
            assertThat(used.failures).isEqualTo(1)
            assertThat(used.lastFailure).isEqualTo("HTTP 403")
            assertThat(used.cooldownRemainingMs).isGreaterThan(0)
            monitor.close()
            assertThat(monitor.snapshots.value.routes).isEmpty()
        }

    @Test
    fun `late measurement completion is a success and a deadline miss not a failure`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(diagnosticsEnabled = true), backgroundScope)
            monitor.routeStarted("late.example")
            monitor.routeDeadlineMiss("late.example")
            monitor.routeRescueProvided("late.example")
            monitor.routeFinished("late.example", 1024, 1000, null)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val route =
                monitor.snapshots.value.routes
                    .single()
            assertThat(route.successes).isEqualTo(1)
            assertThat(route.deadlineMisses).isEqualTo(1)
            assertThat(route.rescuesProvided).isEqualTo(1)
            assertThat(route.failures).isEqualTo(0)
            assertThat(route.bytesPerSecond).isEqualTo(1024)
            monitor.close()
        }

    @Test
    fun `rescued exploration retains outcome and urgency freezes on pause`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val index = ByteBuffer.allocate(44)
            index
                .putInt(44)
                .putInt(0x73696478)
                .putInt(0)
                .putInt(1)
                .putInt(1000)
            index
                .putInt(10000)
                .putInt(0)
                .putShort(0)
                .putShort(1)
            index.putInt(100).putInt(2000).putInt(0)
            monitor.recordBytes(DownloadTrack.Video, 0, index.array())
            val id = monitor.plan(DownloadTrack.Video, 44, 143)
            monitor.exploration(id, ExplorationState.Testing)
            monitor.updatePlayback(6000, 1f, true, true, false)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val before =
                monitor.snapshots.value.blocks
                    .single()
                    .deadlineUrgency
            assertThat(before).isGreaterThan(0f)
            assertThat(before).isLessThan(1f)
            monitor.updatePlayback(6000, 1f, false, false, false)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(
                monitor.snapshots.value.blocks
                    .single()
                    .deadlineUrgency,
            ).isEqualTo(before)
            monitor.rescue(id)
            monitor.state(id, DownloadBlockState.Complete)
            monitor.explorationFinished(id, succeeded = true, cancelled = false)
            monitor.updatePlayback(11000, 1f, true, true, false)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val completed =
                monitor.snapshots.value.blocks
                    .single()
            assertThat(completed.rescued).isTrue()
            assertThat(completed.exploration).isEqualTo(ExplorationState.Succeeded)
            assertThat(completed.deadlineUrgency).isEqualTo(0f)
            monitor.close()
        }

    @Test
    fun `indexed blocks publish together and closure prevents stale request state`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(visualizationEnabled = true), backgroundScope)
            val id = monitor.plan(DownloadTrack.Video, 44, 93)
            monitor.requestStarted()
            monitor.state(id, DownloadBlockState.Active)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertThat(
                monitor.snapshots.value.blocks
                    .single()
                    .startTimeMs,
            ).isNull()
            val index = ByteBuffer.allocate(44)
            index
                .putInt(44)
                .putInt(0x73696478)
                .putInt(0)
                .putInt(1)
                .putInt(1000)
            index
                .putInt(0)
                .putInt(0)
                .putShort(0)
                .putShort(1)
            index.putInt(100).putInt(2000).putInt(0)
            monitor.recordBytes(DownloadTrack.Video, 0, index.array())
            monitor.progress(id, 25)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val block =
                monitor.snapshots.value.blocks
                    .single()
            assertThat(block.startTimeMs).isEqualTo(0L)
            assertThat(block.endTimeMs).isEqualTo(1000L)
            // The request covers 50 bytes, but metadata describes one 100-byte segment.
            val segment =
                monitor.snapshots.value.segments
                    .single()
            assertThat(segment.averageBytes).isEqualTo(100.0)
            assertThat(segment.count).isEqualTo(1)
            assertThat(monitor.snapshots.value.activeRequests).isEqualTo(1)
            monitor.close()
            monitor.requestStarted()
            monitor.progress(id, 50)
            advanceTimeBy(100)
            runCurrent()
            assertThat(monitor.snapshots.value.blocks).isEmpty()
            assertThat(monitor.snapshots.value.activeRequests).isEqualTo(0)
        }
}

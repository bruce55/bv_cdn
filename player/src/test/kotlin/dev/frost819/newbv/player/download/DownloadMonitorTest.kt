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

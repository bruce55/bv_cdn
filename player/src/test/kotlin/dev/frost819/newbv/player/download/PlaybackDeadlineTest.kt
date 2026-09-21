package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Checks scheduling clocks across speed changes, stalls, pauses, and hidden overlays. */
class PlaybackDeadlineTest {
    @Test
    fun `playing deadline is anchored to sample and respects speed`() {
        val clock = PlaybackDeadline(1000, 2f, true, true, false, 10_000_000_000)
        assertThat(clock.forSegment(5000, 11_000_000_000)).isEqualTo(11_250_000_000L)
        assertThat(clock.forSegment(5000, 12_000_000_000)).isEqualTo(11_250_000_000L)
        assertThat(clock.copy(positionMs = 5000).forSegment(5000)).isEqualTo(9_250_000_000L)
    }

    @Test
    fun `buffering does not extrapolate playback and pause has no deadline`() {
        val clock = PlaybackDeadline(1000, 1f, true, false, true, 10_000_000_000)
        assertThat(clock.forSegment(1000, 20_000_000_000)).isEqualTo(19_250_000_000L)
        assertThat(clock.forSegment(5000, 20_000_000_000)).isEqualTo(23_250_000_000L)
        assertThat(clock.copy(playWhenReady = false).forSegment(1000)).isNull()
        assertThat(clock.copy(isBuffering = false).forSegment(1000)).isNull()
        assertThat(clock.copy(speed = Float.NaN).forSegment(1000)).isNull()
    }

    @Test
    fun `hidden overlays still index conservative segment deadlines and close clears clock`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(enabled = true), backgroundScope)
            assertThat(monitor.isPlaybackPaused()).isFalse()
            monitor.updatePlayback(0, 1f, true, true, false, 10_000_000_000)
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 144)).isNull()
            val index = ByteBuffer.allocate(56)
            index
                .putInt(56)
                .putInt(0x73696478)
                .putInt(0)
                .putInt(1)
                .putInt(1000)
            index
                .putInt(0)
                .putInt(0)
                .putShort(0)
                .putShort(2)
            index.putInt(100).putInt(2000).putInt(0)
            index.putInt(100).putInt(2000).putInt(0)
            monitor.recordBytes(DownloadTrack.Video, 0, index.array())
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 55)).isNull()
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 56)).isEqualTo(56L..155L)
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 155)).isEqualTo(56L..155L)
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 156)).isEqualTo(156L..255L)
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 256)).isNull()
            assertThat(monitor.segmentBounds(DownloadTrack.Audio, 56)).isNull()
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 150)).isEqualTo(9_250_000_000L)
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 156)).isEqualTo(11_250_000_000L)
            assertThat(monitor.schedulingTimeMs(DownloadTrack.Video, 156)).isEqualTo(2000L)
            assertThat(monitor.deadlineNanos(DownloadTrack.Audio, 156)).isNull()
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 256)).isNull()
            assertThat(monitor.snapshots.value.blocks).isEmpty()
            monitor.updatePlayback(0, 1f, false, false, false)
            assertThat(monitor.isPlaybackPaused()).isTrue()
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 156)).isNull()
            monitor.close()
            assertThat(monitor.segmentBounds(DownloadTrack.Video, 56)).isNull()
            monitor.updatePlayback(0, 1f, true, true, false)
            assertThat(monitor.deadlineNanos(DownloadTrack.Video, 156)).isNull()
        }
}

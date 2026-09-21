package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Experiment budgets use actual indexed segment sizes and playback speed. */
class DownloadSchedulingMonitorTest {
    @Test
    fun `exploration duration does not grow with distant playback deadline`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val video = index(1000 to 2000, 3000 to 3000, 2000 to 5000)
            monitor.recordBytes(DownloadTrack.Video, 0, video)
            monitor.updatePlayback(0, 1f, true, true, false, sampledAtNanos = 0)
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, video.size + 4000L, video.size + 4999L))
                .isEqualTo(2_500_000_000L)
            monitor.updatePlayback(10000, 1f, true, false, true, sampledAtNanos = 0)
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, video.size + 4000L, video.size + 4999L))
                .isEqualTo(2_500_000_000L)
            monitor.close()
        }

    @Test
    fun `duration fallback weights every overlapped segment and playback speed`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val video = index(1000 to 2000, 3000 to 3000, 2000 to 5000)
            monitor.recordBytes(DownloadTrack.Video, 0, video)
            monitor.updatePlayback(10000, 2f, true, false, true, sampledAtNanos = 0)
            // 500 bytes of segment one = 1 second; 1500 of segment two = 1.5 seconds, at 2x.
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, video.size + 500L, video.size + 2499L))
                .isEqualTo(1_250_000_000L)
            // Equal-sized blocks in these variable-bitrate segments have different media durations.
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, video.size.toLong(), video.size + 499L))
                .isEqualTo(500_000_000L)
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, video.size + 1000L, video.size + 1499L))
                .isEqualTo(250_000_000L)
            monitor.close()
        }

    @Test
    fun `exploration budget rejects unavailable or partially mapped ranges`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, 0, 100)).isNull()
            val video = index(1000 to 2000)
            monitor.recordBytes(DownloadTrack.Video, 0, video)
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, 0, 100)).isNull()
            assertThat(
                monitor.explorationBudgetNanos(DownloadTrack.Video, video.size.toLong(), video.size + 1000L),
            ).isNull()
            assertThat(monitor.explorationBudgetNanos(DownloadTrack.Video, 100, 90)).isNull()
            monitor.close()
        }

    private fun index(vararg segments: Pair<Int, Int>): ByteArray {
        val size = 32 + 12 * segments.size
        return ByteBuffer
            .allocate(size)
            .apply {
                putInt(size)
                    .putInt(0x73696478)
                    .putInt(0)
                    .putInt(1)
                    .putInt(1000)
                putInt(0).putInt(0).putShort(0).putShort(segments.size.toShort())
                segments.forEach { (bytes, durationMs) -> putInt(bytes).putInt(durationMs).putInt(0) }
            }.array()
    }
}

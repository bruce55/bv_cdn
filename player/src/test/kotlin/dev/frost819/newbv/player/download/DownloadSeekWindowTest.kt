package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Retention hints follow indexed media segments without guessing exact decoder seek positions. */
class DownloadSeekWindowTest {
    @Test
    fun `target includes preceding segment and clamps at indexed end`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(100 to 2000, 900 to 3000, 500 to 1000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 1000, 300)).isEqualTo(base..base + 299)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 2000, 300)).isEqualTo(base..base + 299)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 5500, 2000)).isEqualTo(base + 100..base + 1499)
            assertThat(monitor.seekWindow(DownloadTrack.Video, Long.MAX_VALUE, 2000)).isEqualTo(base + 100..base + 1499)
            monitor.close()
        }

    @Test
    fun `oversized preceding segment is dropped so target start remains in bounded window`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(100 to 2000, 900 to 3000, 500 to 1000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 5500, 900)).isEqualTo(base + 1000..base + 1499)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 5500, 901)).isEqualTo(base + 100..base + 1000)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 5500, 1)).isEqualTo(base + 1000..base + 1000)
            monitor.close()
        }

    @Test
    fun `unknown metadata invalid input and closed monitor have no seek window`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 0, 1000)).isNull()
            monitor.recordBytes(DownloadTrack.Video, 0, index(100 to 2000))
            assertThat(monitor.seekWindow(DownloadTrack.Audio, 0, 1000)).isNull()
            assertThat(monitor.seekWindow(DownloadTrack.Video, -1, 1000)).isNull()
            assertThat(monitor.seekWindow(DownloadTrack.Video, 0, 0)).isNull()
            assertThat(monitor.seekWindow(DownloadTrack.Video, 0, -1)).isNull()
            monitor.close()
            assertThat(monitor.seekWindow(DownloadTrack.Video, 0, 1000)).isNull()
        }

    @Test
    fun `large indexed offsets and unlimited requested budget do not overflow`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val offset = Long.MAX_VALUE - 1000
            val index = index(100 to 2000, 100 to 2000, firstOffset = offset)
            val base = index.size + offset
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 2500, Long.MAX_VALUE)).isEqualTo(base..base + 199)
            assertThat(monitor.seekWindow(DownloadTrack.Video, 2500, 50)).isEqualTo(base + 100..base + 149)
            monitor.close()
        }

    private fun index(
        vararg segments: Pair<Int, Int>,
        firstOffset: Long = 0,
    ): ByteArray {
        val size = 40 + segments.size * 12
        return ByteBuffer
            .allocate(size)
            .apply {
                putInt(size)
                    .putInt(0x73696478)
                    .putInt(0x01000000)
                    .putInt(1)
                    .putInt(1000)
                putLong(0).putLong(firstOffset).putShort(0).putShort(segments.size.toShort())
                segments.forEach { (bytes, duration) -> putInt(bytes).putInt(duration).putInt(0) }
            }.array()
    }
}

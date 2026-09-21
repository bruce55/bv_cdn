package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Dispatch follows the farthest validated future block, independently of playable gaps. */
class DownloadReadinessTest {
    @Test
    fun `future completion advances dispatch horizon across a gap`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(100 to 2000, 900 to 3000, 500 to 1000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            val videoEpoch = monitor.beginRead(DownloadTrack.Video, 0)
            monitor.rangeReady(DownloadTrack.Video, 0, base, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(0L)
            monitor.rangeReady(DownloadTrack.Video, base + 100, base + 1500, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            monitor.rangeReady(DownloadTrack.Video, base, base + 50, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            monitor.rangeReady(DownloadTrack.Video, base + 50, base + 100, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            monitor.close()
        }

    @Test
    fun `partial VBR blocks advance proportionally within each actual segment`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(100 to 2000, 900 to 3000, 500 to 1000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            val videoEpoch = monitor.beginRead(DownloadTrack.Video, base)
            monitor.rangeReady(DownloadTrack.Video, base, base + 50, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(1000L)
            monitor.rangeReady(DownloadTrack.Video, base + 50, base + 100, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(2000L)
            monitor.rangeReady(DownloadTrack.Video, base + 100, base + 550, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(3500L)
            monitor.rangeReady(DownloadTrack.Video, base + 550, base + 1000, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(5000L)
            monitor.close()
        }

    @Test
    fun `overlapping and duplicate completions cannot regress or inflate the horizon`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(1000 to 4000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            val videoEpoch = monitor.beginRead(DownloadTrack.Video, base)
            monitor.rangeReady(DownloadTrack.Video, base + 400, base + 700, videoEpoch)
            monitor.rangeReady(DownloadTrack.Video, base + 600, base + 900, videoEpoch)
            monitor.rangeReady(DownloadTrack.Video, base + 300, base + 450, videoEpoch)
            monitor.rangeReady(DownloadTrack.Video, base + 300, base + 450, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(3600L)
            monitor.rangeReady(DownloadTrack.Video, base, base + 350, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(3600L)
            monitor.close()
        }

    @Test
    fun `seek resets horizon and ignores stale epoch completions`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val index = index(100 to 2000, 900 to 3000, 500 to 1000)
            val base = index.size.toLong()
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            var videoEpoch = monitor.beginRead(DownloadTrack.Video, base)
            monitor.rangeReady(DownloadTrack.Video, base, base + 1500, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            val obsoleteEpoch = videoEpoch
            videoEpoch = monitor.beginRead(DownloadTrack.Video, base + 550)
            monitor.rangeReady(DownloadTrack.Video, base, base + 1500, obsoleteEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(3500L)
            monitor.rangeReady(DownloadTrack.Video, base + 1000, base + 1500, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            monitor.rangeReady(DownloadTrack.Video, base + 550, base + 1000, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            videoEpoch = monitor.beginRead(DownloadTrack.Video, base)
            monitor.rangeReady(DownloadTrack.Video, base, base + 100, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(2000L)
            monitor.close()
        }

    @Test
    fun `audio and video priority switches as future blocks complete despite gaps`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            val video = index(1000 to 2000, 1000 to 4000)
            val audio = index(10 to 5000, 10 to 1000)
            monitor.recordBytes(DownloadTrack.Video, 0, video)
            monitor.recordBytes(DownloadTrack.Audio, 0, audio)
            val videoEpoch = monitor.beginRead(DownloadTrack.Video, video.size.toLong())
            val audioEpoch = monitor.beginRead(DownloadTrack.Audio, audio.size.toLong())
            monitor.rangeReady(DownloadTrack.Video, video.size.toLong(), video.size + 1000L, videoEpoch)
            monitor.rangeReady(DownloadTrack.Audio, audio.size + 5L, audio.size + 10L, audioEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(2000L)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Audio)).isEqualTo(5000L)
            assertThat(monitor.laggingTrack()).isEqualTo(DownloadTrack.Video)
            monitor.rangeReady(DownloadTrack.Video, video.size + 1500L, video.size + 2000L, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(6000L)
            assertThat(monitor.laggingTrack()).isEqualTo(DownloadTrack.Audio)
            monitor.rangeReady(DownloadTrack.Audio, audio.size + 10L, audio.size + 20L, audioEpoch)
            assertThat(monitor.laggingTrack()).isNull()
            monitor.close()
        }

    @Test
    fun `missing metadata or read epoch gives no horizon and close clears state`() =
        runTest {
            val monitor = DownloadMonitor(ParallelDownloadConfig(), backgroundScope)
            var videoEpoch = -1L
            monitor.rangeReady(DownloadTrack.Video, 0, 100, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isNull()
            assertThat(monitor.laggingTrack()).isNull()
            videoEpoch = monitor.beginRead(DownloadTrack.Video, 0)
            monitor.rangeReady(DownloadTrack.Video, 0, 100, videoEpoch)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isNull()
            assertThat(monitor.laggingTrack()).isNull()
            val index = index(100 to 2000)
            monitor.recordBytes(DownloadTrack.Video, 0, index)
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isEqualTo(1120L)
            monitor.close()
            assertThat(monitor.schedulingHorizonTimeMs(DownloadTrack.Video)).isNull()
            assertThat(monitor.laggingTrack()).isNull()
        }

    private fun index(vararg segments: Pair<Int, Int>): ByteArray {
        val size = 32 + segments.size * 12
        return ByteBuffer
            .allocate(size)
            .apply {
                putInt(size)
                    .putInt(0x73696478)
                    .putInt(0)
                    .putInt(1)
                    .putInt(1000)
                putInt(0).putInt(0).putShort(0).putShort(segments.size.toShort())
                segments.forEach { (bytes, duration) -> putInt(bytes).putInt(duration).putInt(0) }
            }.array()
    }
}

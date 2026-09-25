package dev.frost819.newbv.player.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BufferedRangesTest {
    @Test
    fun `audio running ahead cannot fill video gaps`() {
        val available =
            listOf(
                DownloadAvailableRange(DownloadTrack.Video, 0, 1000),
                DownloadAvailableRange(DownloadTrack.Video, 2000, 3000),
                DownloadAvailableRange(DownloadTrack.Audio, 0, 5000),
            )
        assertThat(commonBufferedRanges(available, DownloadTrack.entries.toSet()))
            .containsExactly(
                DownloadBufferedRange(0, 1000),
                DownloadBufferedRange(2000, 3000),
            ).inOrder()
    }

    @Test
    fun `missing required track stays empty while video-only content needs no audio`() {
        val available = listOf(DownloadAvailableRange(DownloadTrack.Video, 1000, 2000))
        assertThat(commonBufferedRanges(available, DownloadTrack.entries.toSet())).isEmpty()
        assertThat(commonBufferedRanges(available, setOf(DownloadTrack.Video)))
            .containsExactly(DownloadBufferedRange(1000, 2000))
    }

    @Test
    fun `overlapping track intervals intersect and touching endpoints add no range`() {
        val available =
            listOf(
                DownloadAvailableRange(DownloadTrack.Video, 0, 1000),
                DownloadAvailableRange(DownloadTrack.Video, 2000, 4000),
                DownloadAvailableRange(DownloadTrack.Audio, 1000, 2500),
                DownloadAvailableRange(DownloadTrack.Audio, 3000, 5000),
            )
        assertThat(commonBufferedRanges(available, DownloadTrack.entries.toSet()))
            .containsExactly(
                DownloadBufferedRange(2000, 2500),
                DownloadBufferedRange(3000, 4000),
            ).inOrder()
    }
}

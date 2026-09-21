package dev.frost819.newbv.app.ui.component.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Checks shared-pixel coverage and priority without relying on Android rendering. */
class DownloadLaneRasterTest {
    @Test
    fun `adjacent translucent spans have no boundary seam`() {
        val color = 0x8064D8FF.toInt()
        val pixels =
            rasterizeLaneRow(
                2,
                listOf(LaneRasterSpan(0.0, 0.3, color, 10), LaneRasterSpan(0.3, 2.0, color, 10)),
            )
        assertThat(pixels.toList()).containsExactly(color, color).inOrder()
    }

    @Test
    fun `narrow block contributes fractional coverage without widening`() {
        val pixels = rasterizeLaneRow(2, listOf(LaneRasterSpan(0.75, 1.25, 0xFFFF0000.toInt(), 10)))
        assertThat(pixels.toList()).containsExactly(0x40FF0000, 0x40FF0000).inOrder()
    }

    @Test
    fun `availability wins over overlapping stale download and duplicate history`() {
        val green = 0xFF81C995.toInt()
        val pixels =
            rasterizeLaneRow(
                2,
                listOf(
                    LaneRasterSpan(0.0, 2.0, green, 100),
                    LaneRasterSpan(0.0, 2.0, green, 100),
                    LaneRasterSpan(0.5, 1.5, 0xFFFF0000.toInt(), 20),
                ),
            )
        assertThat(pixels.toList()).containsExactly(green, green).inOrder()
    }

    @Test
    fun `adjacent different colors share a pixel by coverage instead of painting over each other`() {
        val pixels =
            rasterizeLaneRow(
                1,
                listOf(
                    LaneRasterSpan(0.0, 0.5, 0xFFFF0000.toInt(), 10),
                    LaneRasterSpan(0.5, 1.0, 0xFF0000FF.toInt(), 10),
                ),
            )
        assertThat(pixels.single()).isEqualTo(0xFF800080.toInt())
    }
}

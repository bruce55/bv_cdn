package dev.frost819.newbv.app.ui.component.player

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A continuous horizontal span, resolved by priority before physical-pixel coverage is integrated. */
internal data class LaneRasterSpan(
    val start: Double,
    val end: Double,
    val argb: Int,
    val priority: Int,
)

/**
 * Rasterizes one shared physical-pixel scanline without rounding individual block boundaries.
 *
 * Overlapping spans select one winner, so duplicate/stale blocks never accumulate opacity.
 * Adjacent spans contribute premultiplied fractional coverage to their shared boundary pixel.
 * Memory is proportional to viewport width and span count, never media duration or byte count.
 */
internal fun rasterizeLaneRow(
    width: Int,
    spans: List<LaneRasterSpan>,
): IntArray {
    if (width <= 0) return IntArray(0)

    data class Edge(
        val x: Double,
        val index: Int,
        val entering: Boolean,
    )
    val edges = ArrayList<Edge>(spans.size * 2)
    spans.forEachIndexed { index, span ->
        val left = span.start.coerceIn(0.0, width.toDouble())
        val right = span.end.coerceIn(0.0, width.toDouble())
        if (left.isFinite() && right.isFinite() && right > left) {
            edges.add(Edge(left, index, true))
            edges.add(Edge(right, index, false))
        }
    }
    edges.sortBy { it.x }
    val active = BooleanArray(spans.size)
    val winners = PriorityQueue<Int>(compareByDescending<Int> { spans[it].priority }.thenByDescending { it })
    val coverage = FloatArray(width * 4)
    var previous = 0.0
    var edgeIndex = 0
    while (edgeIndex < edges.size) {
        val x = edges[edgeIndex].x
        while (winners.isNotEmpty() && !active[winners.peek()]) winners.poll()
        if (x > previous && winners.isNotEmpty()) {
            val color = spans[winners.peek()].argb
            val alpha = (color ushr 24) / 255f
            val red = ((color ushr 16) and 255) * alpha
            val green = ((color ushr 8) and 255) * alpha
            val blue = (color and 255) * alpha
            for (pixel in floor(previous).toInt() until ceil(x).toInt().coerceAtMost(width)) {
                val weight = (minOf(x, pixel + 1.0) - maxOf(previous, pixel.toDouble())).toFloat()
                val offset = pixel * 4
                coverage[offset] += alpha * weight
                coverage[offset + 1] += red * weight
                coverage[offset + 2] += green * weight
                coverage[offset + 3] += blue * weight
            }
        }
        while (edgeIndex < edges.size && edges[edgeIndex].x == x) {
            val edge = edges[edgeIndex++]
            active[edge.index] = edge.entering
            if (edge.entering) winners.add(edge.index)
        }
        previous = x
    }
    return IntArray(width) { pixel ->
        val offset = pixel * 4
        val alpha = coverage[offset].coerceIn(0f, 1f)
        if (alpha <= 0f) {
            0
        } else {
            val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val r = (coverage[offset + 1] / alpha).roundToInt().coerceIn(0, 255)
            val g = (coverage[offset + 2] / alpha).roundToInt().coerceIn(0, 255)
            val b = (coverage[offset + 3] / alpha).roundToInt().coerceIn(0, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

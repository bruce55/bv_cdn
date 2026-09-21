package dev.frost819.newbv.app.ui.component.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.frost819.newbv.player.download.DownloadBlock
import dev.frost819.newbv.player.download.DownloadBlockState
import dev.frost819.newbv.player.download.DownloadSnapshot
import dev.frost819.newbv.player.download.DownloadTrack
import dev.frost819.newbv.player.download.ExplorationState
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws locally available ranges and a shared physical-pixel grid for pending media bytes.
 *
 * Four video rows and two audio rows retain fractional coverage even for subpixel blocks.
 * The bitmap is rebuilt only for a new snapshot or geometry; player-position redraws blit it.
 * Event underlines remain separate from the authoritative green availability fill.
 * [middleGap] reserves space between the lanes for the playback progress line.
 */
@Composable
internal fun DownloadBlockLanes(
    snapshot: DownloadSnapshot,
    duration: Long,
    displayScale: Float = 1f,
    middleGap: Dp = 0.dp,
) {
    if (duration <= 0 || (snapshot.blocks.isEmpty() && snapshot.availableRanges.isEmpty())) return
    val videoIcon = rememberVectorPainter(Icons.Rounded.Videocam)
    val audioIcon = rememberVectorPainter(Icons.Rounded.MusicNote)
    val videoColor = Color(0xFF64D8FF)
    val audioColor = Color(0xFFCEB7FF)
    val rasterModifier =
        remember(snapshot, duration, displayScale, middleGap, videoIcon, audioIcon) {
            Modifier.drawWithCache {
                val width = ceil(size.width).toInt().coerceAtLeast(1)
                val height = ceil(size.height).toInt().coerceAtLeast(1)
                val pixels = IntArray(width * height)
                val unit = (1.dp * displayScale).toPx()
                val gap = middleGap.toPx()
                for (track in DownloadTrack.entries) {
                    val audio = track == DownloadTrack.Audio
                    val rows = if (audio) 2 else 4
                    val top = if (audio) 17f * unit + gap else 0f
                    val laneHeight = (if (audio) 4f else 12f) * unit
                    val color = if (audio) audioColor else videoColor
                    val spans = Array(rows + 3) { mutableListOf<LaneRasterSpan>() }
                    for (row in 0 until rows) {
                        spans[row].add(LaneRasterSpan(0.0, size.width.toDouble(), 0x09FFFFFF, 0))
                    }
                    for (block in snapshot.blocks) {
                        if (block.track != track) continue
                        val start = block.startTimeMs ?: continue
                        val end = block.endTimeMs ?: continue
                        if (end <= start) continue
                        val left = (start.toDouble() / duration).coerceIn(0.0, 1.0) * size.width
                        val right = (end.toDouble() / duration).coerceIn(0.0, 1.0) * size.width
                        addBlockSpans(spans, rows, block, left, right, color)
                    }
                    for (range in snapshot.availableRanges) {
                        if (range.track != track || range.endTimeMs <= range.startTimeMs) continue
                        val left = (range.startTimeMs.toDouble() / duration).coerceIn(0.0, 1.0) * size.width
                        val right = (range.endTimeMs.toDouble() / duration).coerceIn(0.0, 1.0) * size.width
                        for (row in 0 until rows) {
                            spans[row].add(LaneRasterSpan(left, right, 0x6681C995, 100))
                        }
                    }
                    spans.forEachIndexed { row, rowSpans ->
                        val scanline = rasterizeLaneRow(width, rowSpans)
                        val from =
                            if (row < rows) {
                                top + laneHeight * row / rows
                            } else {
                                top + laneHeight + (row - rows) * unit
                            }
                        val until =
                            if (row < rows) {
                                top + laneHeight * (row + 1) / rows
                            } else {
                                top + laneHeight + (row - rows + 1) * unit
                            }
                        // Round shared row edges, never block edges: adjacent rows cannot leave seams.
                        val firstY = from.roundToInt().coerceIn(0, height)
                        val lastY = until.roundToInt().coerceIn(firstY, height)
                        for (y in firstY until lastY) scanline.copyInto(pixels, y * width)
                    }
                }
                val image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
                onDrawBehind {
                    drawImage(image)
                    translate(left = -18f * unit) {
                        with(videoIcon) {
                            draw(Size(12f * unit, 12f * unit), colorFilter = ColorFilter.tint(videoColor))
                        }
                    }
                    translate(left = -16f * unit, top = 15f * unit + gap) {
                        with(audioIcon) {
                            draw(Size(8f * unit, 8f * unit), colorFilter = ColorFilter.tint(audioColor))
                        }
                    }
                }
            }
        }
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp * displayScale + middleGap)
            .semantics {
                contentDescription =
                    "下载分块：上方视频，下方音频；浅色待下载，网格亮色已接收，绿色本地可用；下划线：黄色探测中，青绿探测结束，橙色补救或重试，紫红异常，灰色取消；背景渐红表示临近截止时间"
            }.then(rasterModifier),
    )
}

private fun addBlockSpans(
    spans: Array<MutableList<LaneRasterSpan>>,
    rows: Int,
    block: DownloadBlock,
    left: Double,
    right: Double,
    color: Color,
) {
    if (right <= left) return
    if (block.state == DownloadBlockState.Planned ||
        block.state == DownloadBlockState.Active ||
        block.state == DownloadBlockState.Retrying
    ) {
        val background =
            Color.Red
                .copy(alpha = 0.45f * block.deadlineUrgency.coerceIn(0f, 1f))
                .compositeOver(color.copy(alpha = 0.12f))
        val received = color.copy(alpha = 0.58f).compositeOver(background).toArgb()
        val fraction =
            (block.receivedBytes.toDouble() / (block.endByte - block.startByte + 1).coerceAtLeast(1))
                .coerceIn(0.0, 1.0)
        for (row in 0 until rows) {
            spans[row].add(LaneRasterSpan(left, right, background.toArgb(), 10))
            val rowFraction = (fraction * rows - row).coerceIn(0.0, 1.0)
            if (rowFraction > 0.0) {
                spans[row].add(LaneRasterSpan(left, left + (right - left) * rowFraction, received, 20))
            }
        }
    }
    val exploration =
        when (block.exploration) {
            ExplorationState.Testing -> 0xFFFFD166.toInt()
            ExplorationState.Succeeded -> 0xFF40E0D0.toInt()
            ExplorationState.Failed -> 0xFF40E0D0.toInt()
            ExplorationState.Cancelled -> 0xFF9AA0A6.toInt()
            ExplorationState.None -> null
        }
    if (exploration != null) spans[rows].add(LaneRasterSpan(left, right, exploration, 10))
    if (block.rescued || block.state == DownloadBlockState.Retrying) {
        spans[rows + 1].add(LaneRasterSpan(left, right, 0xFFFF9F43.toInt(), 10))
    }
    if (block.hadFailure ||
        block.state == DownloadBlockState.Failed ||
        block.exploration == ExplorationState.Failed
    ) {
        spans[rows + 2].add(LaneRasterSpan(left, right, 0xFFF06CFF.toInt(), 10))
    }
}

/** Shows active HTTP requests (including audio/retries) beside the currently visible progress bar. */
@Composable
internal fun DownloadRequestStatus(
    snapshot: DownloadSnapshot,
    modifier: Modifier = Modifier,
) {
    val hasTimeline =
        snapshot.blocks.any {
            val start = it.startTimeMs
            val end = it.endTimeMs
            start != null && end != null && end > start
        }
    val hint =
        if (snapshot.startupProbing) {
            " · 探测 视频 ${snapshot.videoProbes}/${snapshot.maxRequests} · 音频 ${snapshot.audioProbes}/${snapshot.maxRequests}"
        } else {
            ""
        }
    Row(
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasTimeline) {
            BlockStatusKey("探测", Color(0xFFFFD166), secondColor = Color(0xFF40E0D0))
            BlockStatusKey("补救", Color(0xFFFF9F43))
            BlockStatusKey("异常", Color(0xFFF06CFF))
            BlockStatusKey("取消", Color(0xFF9AA0A6))
            BlockStatusKey("临近截止", Color.Red, underline = false)
        }
        Text(
            text = "Downloads: ${snapshot.activeRequests}/${snapshot.maxRequests}$hint",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun BlockStatusKey(
    label: String,
    color: Color,
    underline: Boolean = true,
    secondColor: Color? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (secondColor != null) {
            Row {
                Box(Modifier.size(width = 5.dp, height = 2.dp).background(color))
                Box(Modifier.size(width = 5.dp, height = 2.dp).background(secondColor))
            }
        } else {
            Box(
                (if (underline) Modifier.size(width = 10.dp, height = 2.dp) else Modifier.size(6.dp)).background(color),
            )
        }
        Text(label, color = if (secondColor != null) Color.White else color, fontSize = 11.sp)
    }
}

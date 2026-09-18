package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.player.download.DownloadBlockState
import dev.frost819.newbv.player.download.DownloadSnapshot
import dev.frost819.newbv.player.download.DownloadTrack

/** Renders video and audio lanes using media-index times, never a file-size/duration estimate. */
@Composable
internal fun DownloadBlockLanes(
    snapshot: DownloadSnapshot,
    duration: Long,
) {
    val mapped =
        snapshot.blocks.filter {
            val start = it.startTimeMs
            val end = it.endTimeMs
            start != null && end != null && end > start
        }
    if (duration <= 0 || mapped.isEmpty()) return
    Canvas(
        Modifier.fillMaxWidth().height(13.dp).semantics {
            contentDescription = "下载分块：上方视频，下方音频；空框待下载，蓝色下载中，绿色已下载，橙色重试"
        },
    ) {
        for (block in mapped) {
            val start = block.startTimeMs ?: continue
            val end = block.endTimeMs ?: continue
            val left = (start.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            val right = (end.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            if (right <= left) continue
            val audio = block.track == DownloadTrack.Audio
            val top = if (audio) 8.dp.toPx() else 1.dp.toPx()
            val height = if (audio) 3.dp.toPx() else 5.dp.toPx()
            val width = (right - left - 1.dp.toPx()).coerceAtLeast(1f).coerceAtMost(right - left)
            val color =
                when (block.state) {
                    DownloadBlockState.Planned -> Color.White.copy(alpha = 0.55f)
                    DownloadBlockState.Active -> Color(0xFF64D8FF)
                    DownloadBlockState.Complete -> Color(0xFF81C995)
                    DownloadBlockState.Retrying -> Color(0xFFFFC15A)
                    DownloadBlockState.Failed -> Color(0xFFFF7979)
                }
            val origin = Offset(left, top)
            val extent = Size(width, height)
            drawRect(color, origin, extent, style = Stroke(width = 1.dp.toPx()))
            val fraction =
                if (block.state == DownloadBlockState.Complete) {
                    1f
                } else {
                    // Partial fill is byte progress *within* the indexed media segment.
                    (block.receivedBytes.toDouble() / (block.endByte - block.startByte + 1).coerceAtLeast(1))
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                }
            if (fraction > 0f) drawRect(color, origin, Size(width * fraction, height))
        }
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
    val hint = if (hasTimeline) " · 分段估算" else " · 暂无分块时间索引"
    Text(
        text = "Downloads: ${snapshot.activeRequests}/${snapshot.maxRequests}$hint",
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}

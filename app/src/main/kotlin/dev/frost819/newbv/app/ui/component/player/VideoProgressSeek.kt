package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import dev.frost819.newbv.core.theme.BVTheme
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.player.download.DownloadBufferedRange
import dev.frost819.newbv.player.download.DownloadSnapshot

/**
 * 视频进度条。
 *
 * 使用 Canvas 绘制三层进度线：背景轨道、缓冲进度、播放进度。
 * 三段轨道用「色相 + 明度」双重区分，避免混在一起：
 * 已播放用品牌色（primary），已缓冲用较亮的中性灰，未缓冲用很暗的中性灰。
 * 播放器固定运行在深色主题下，均能保证黑底上的可见性。
 * 支持两种显示模式：
 * - **常显模式**（[isPersistentSeek] = true）：2dp 细线，仅并行下载时显示合并缓冲区间，
 *   用于播放器底部始终可见的进度条。
 * - **交互模式**（[isPersistentSeek] = false）：8dp 粗线，显示缓冲进度，
 *   用于控制器信息栏中的可交互 seek bar。
 *
 * @param modifier 修饰符
 * @param duration 视频总时长（毫秒）
 * @param position 当前播放位置（毫秒）
 * @param bufferedPercentage 缓冲百分比（0-100）
 * @param isPersistentSeek 是否为常显模式
 * @param downloadSnapshot Optional index-mapped download lanes; null hides the visualization.
 * @param bufferedRanges Combined cache/player intervals for the standard bar; null uses the player percentage.
 */
@Composable
fun VideoProgressSeek(
    modifier: Modifier = Modifier,
    duration: Long,
    position: Long,
    bufferedPercentage: Int,
    isPersistentSeek: Boolean,
    downloadSnapshot: DownloadSnapshot? = null,
    bufferedRanges: List<DownloadBufferedRange>? = null,
) {
    val trackWidthDp = if (isPersistentSeek) 2.dp else 8.dp

    val displayScale = if (downloadSnapshot == null) 1f else downloadProgressScale(Prefs.downloadProgressSize)
    // Persistent bars have no enclosing controller padding. Preserve the screen-safe
    // margin outside the icon gutter at every user-selected display scale.
    val margin = if (downloadSnapshot != null && isPersistentSeek) 24.dp + 20.dp * displayScale else 0.dp
    val frameModifier = modifier.fillMaxWidth().padding(horizontal = margin)
    if (downloadSnapshot != null) {
        Box(frameModifier.height(24.dp * displayScale + trackWidthDp)) {
            DownloadBlockLanes(
                snapshot = downloadSnapshot,
                duration = duration,
                displayScale = displayScale,
                middleGap = trackWidthDp,
            )
            PlaybackProgressLine(
                duration = duration,
                position = position,
                bufferedPercentage = bufferedPercentage,
                isPersistentSeek = isPersistentSeek,
                showBuffered = false,
                modifier = Modifier.padding(top = 15.dp * displayScale),
            )
        }
    } else {
        PlaybackProgressLine(
            duration = duration,
            position = position,
            bufferedPercentage = bufferedPercentage,
            isPersistentSeek = isPersistentSeek,
            showBuffered = !isPersistentSeek || bufferedRanges != null,
            bufferedRanges = bufferedRanges,
            modifier = frameModifier,
        )
    }
}

/** Shared lane scale keeps controller icon gutters aligned with the actual rendering. */
internal fun downloadProgressScale(size: String): Float =
    when (size) {
        "compact" -> 1f
        "large" -> 2f
        "extra_large" -> 3f
        else -> 1.5f
    }

@Composable
private fun PlaybackProgressLine(
    duration: Long,
    position: Long,
    bufferedPercentage: Int,
    isPersistentSeek: Boolean,
    showBuffered: Boolean,
    bufferedRanges: List<DownloadBufferedRange>? = null,
    modifier: Modifier = Modifier,
) {
    val trackWidthDp = if (isPersistentSeek) 2.dp else 8.dp
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val bufferedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(trackWidthDp)
                .clip(RoundedCornerShape(50)),
    ) {
        val trackWidthPx = trackWidthDp.toPx()

        // 背景轨道
        drawLine(
            color = inactiveTrackColor,
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round,
        )

        // 缓冲进度（仅交互模式显示）
        // The download lanes show actual local intervals; a zero-to-buffered overlay
        // would falsely fill holes and discarded history after seeking.
        if (showBuffered && bufferedRanges != null && duration > 0) {
            for (range in bufferedRanges) {
                val left = (range.startTimeMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                val right = (range.endTimeMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                if (right <= left) continue
                drawLine(
                    color = bufferedTrackColor,
                    start = Offset(left, center.y),
                    end = Offset(right, center.y),
                    strokeWidth = trackWidthPx,
                    cap = StrokeCap.Butt,
                )
            }
        } else if (showBuffered && bufferedPercentage > 0 && bufferedRanges == null) {
            drawLine(
                color = bufferedTrackColor,
                start = Offset(trackWidthPx / 2, center.y),
                end = Offset(size.width * bufferedPercentage / 100, center.y),
                strokeWidth = trackWidthPx,
                cap = StrokeCap.Round,
            )
        }

        // 播放进度
        if (duration > 0) {
            val progressRatio = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            drawLine(
                color = activeTrackColor,
                start = Offset(trackWidthPx / 2, center.y),
                end = Offset(size.width * progressRatio, center.y),
                strokeWidth = trackWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

// region Previews

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekPersistentPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekNormalPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = false,
        )
    }
}

// endregion

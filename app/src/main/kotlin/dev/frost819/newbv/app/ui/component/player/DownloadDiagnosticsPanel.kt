package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.frost819.newbv.player.download.DownloadSnapshot
import dev.frost819.newbv.player.download.DownloadTrack
import java.util.Locale

internal val downloadRouteColors = listOf(0xFF64D8FF, 0xFFFFD166, 0xFFCEB7FF, 0xFF40E0D0, 0xFFFF9F43, 0xFFFF7979)

/** Compact host measurements with the same route colors as the live charts. */
@Composable
internal fun DownloadDiagnosticsPanel(
    snapshot: DownloadSnapshot,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 350.dp,
) {
    val routes = snapshot.routes.sortedByDescending { it.attempts }.take(6)
    Column(
        modifier
            .width(panelWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CDN", color = Color.White, fontSize = 16.sp)
            Text("${snapshot.activeRequests}/${snapshot.maxRequests}", color = Color(0xFF64D8FF), fontSize = 16.sp)
            Text("分段（分块）", color = Color.LightGray, fontSize = 12.sp)
            if (snapshot.routes.size > routes.size) Text("+${snapshot.routes.size - routes.size}", color = Color.Gray)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (track in DownloadTrack.entries) {
                val segments = snapshot.segments.firstOrNull { it.track == track }
                val blocks = snapshot.blocks.filter { it.track == track && it.startTimeMs != null }
                val segmentSize = segments?.let { downloadSize(it.averageBytes.toLong()) } ?: "—"
                val blockSize =
                    if (blocks.isEmpty()) {
                        "—"
                    } else {
                        downloadSize(
                            blocks.map { (it.endByte - it.startByte + 1).toDouble() }.average().toLong(),
                        )
                    }
                val video = track == DownloadTrack.Video
                val color = if (video) Color(0xFF64D8FF) else Color(0xFFCEB7FF)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (video) Icons.Rounded.Videocam else Icons.Rounded.MusicNote,
                        if (video) "视频平均分段（分块）" else "音频平均分段（分块）",
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "$segmentSize ($blockSize)",
                        color = color,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        val transfer = snapshot.transferStats
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("下载 ${downloadSize(transfer.downloadedBytes)}", color = Color.LightGray, fontSize = 11.sp)
            Text(
                "${if (transfer.exact) "交付" else "去重交付"} ${downloadSize(transfer.usedBytes)}",
                color = Color(0xFF81C995),
                fontSize = 11.sp,
            )
            Text("待用 ${downloadSize(transfer.pendingBytes)}", color = Color(0xFF64D8FF), fontSize = 11.sp)
            Text(
                "${if (transfer.exact) "开销" else "开销≈"}${downloadSize(transfer.overheadBytes)}",
                color = Color(0xFFFF9F43),
                fontSize = 11.sp,
            )
        }
        routes.forEach { route ->
            val color = downloadRouteColor(snapshot, route.host)
            val status =
                when {
                    route.cooldownRemainingMs > 0 -> DiagnosticSymbol.Clock
                    route.activeRequests > 0 -> DiagnosticSymbol.Download
                    route.successes > 0 -> DiagnosticSymbol.Success
                    route.failures > 0 -> DiagnosticSymbol.Failure
                    route.attempts > 0 -> DiagnosticSymbol.Waiting
                    else -> DiagnosticSymbol.Unknown
                }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Text(
                        route.host,
                        color = color,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        DiagnosticIcon(status, color)
                        when {
                            route.cooldownRemainingMs > 0 ->
                                Text("${(route.cooldownRemainingMs + 999) / 1000}s", color = color, fontSize = 12.sp)
                            route.activeRequests > 0 -> Text("${route.activeRequests}", color = color, fontSize = 12.sp)
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "实测 ${downloadRate(
                                    route.bytesPerSecond,
                                )}，调度信心 ${(route.schedulingConfidence * 100).toInt()}%，" +
                                "成功 ${route.successes}，需救援 ${route.deadlineMisses}，施救 ${route.rescuesProvided}，" +
                                "失败 ${route.failures}，取消 ${route.cancellations}"
                        },
                ) {
                    Text(downloadRate(route.bytesPerSecond), color = Color.White, fontSize = 13.sp)
                    Text(
                        "×${String.format(Locale.ROOT, "%.2f", route.schedulingConfidence)}",
                        color = Color(0xFFFFD166),
                        fontSize = 12.sp,
                    )
                    DiagnosticCounter(route.successes, DiagnosticSymbol.Success, Color(0xFF81C995))
                    DiagnosticCounter(route.deadlineMisses, DiagnosticSymbol.Clock, Color(0xFFFF9F43))
                    DiagnosticCounter(route.rescuesProvided, DiagnosticSymbol.Rescue, Color(0xFF40E0D0))
                    DiagnosticCounter(route.failures, DiagnosticSymbol.Failure, Color(0xFFF06CFF))
                }
            }
        }
    }
}

private enum class DiagnosticSymbol(
    val description: String,
) {
    Success("成功"),
    Failure("失败"),
    Clock("超时需救援"),
    Rescue("成功施救"),
    Download("下载中"),
    Waiting("等待"),
    Unknown("未测试"),
}

@Composable
private fun DiagnosticCounter(
    count: Int,
    symbol: DiagnosticSymbol,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = "${symbol.description} $count" },
    ) {
        DiagnosticIcon(symbol, color)
        Text("$count", color = color, fontSize = 12.sp)
    }
}

@Composable
private fun DiagnosticIcon(
    symbol: DiagnosticSymbol,
    color: Color,
) {
    Canvas(Modifier.size(16.dp).semantics { contentDescription = symbol.description }) {
        val unit = size.width / 16f
        val stroke = 1.6f * unit

        fun line(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ) = drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke, StrokeCap.Round)
        when (symbol) {
            DiagnosticSymbol.Success -> {
                line(2f, 8f, 6f, 12f)
                line(6f, 12f, 14f, 4f)
            }
            DiagnosticSymbol.Failure -> {
                line(3f, 3f, 13f, 13f)
                line(3f, 13f, 13f, 3f)
            }
            DiagnosticSymbol.Rescue -> {
                // A medical cross, drawn with the same stroke as the other status symbols.
                val cross =
                    Path().apply {
                        moveTo(6 * unit, 2 * unit)
                        lineTo(10 * unit, 2 * unit)
                        lineTo(10 * unit, 6 * unit)
                        lineTo(14 * unit, 6 * unit)
                        lineTo(14 * unit, 10 * unit)
                        lineTo(10 * unit, 10 * unit)
                        lineTo(10 * unit, 14 * unit)
                        lineTo(6 * unit, 14 * unit)
                        lineTo(6 * unit, 10 * unit)
                        lineTo(2 * unit, 10 * unit)
                        lineTo(2 * unit, 6 * unit)
                        lineTo(6 * unit, 6 * unit)
                        close()
                    }
                drawPath(cross, color, style = Stroke(stroke))
            }
            DiagnosticSymbol.Clock -> {
                drawArc(
                    color,
                    15f,
                    265f,
                    false,
                    Offset(unit, 3 * unit),
                    Size(12 * unit, 12 * unit),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(7f, 6f, 7f, 9f)
                line(7f, 9f, 5f, 11f)
                line(12f, 1.5f, 12f, 5f)
                drawCircle(color, 0.8f * unit, Offset(12 * unit, 7.5f * unit))
            }
            DiagnosticSymbol.Download -> {
                line(8f, 2f, 8f, 12f)
                line(4f, 8f, 8f, 12f)
                line(8f, 12f, 12f, 8f)
                line(3f, 15f, 13f, 15f)
            }
            DiagnosticSymbol.Waiting -> {
                line(5f, 3f, 5f, 13f)
                line(11f, 3f, 11f, 13f)
            }
            DiagnosticSymbol.Unknown -> {
                drawCircle(color, 6 * unit, Offset(8 * unit, 8 * unit), style = Stroke(stroke))
                drawCircle(color, unit, Offset(8 * unit, 8 * unit))
            }
        }
    }
}

/** Stable host colors independent of the changing usage ranking. */
internal fun downloadRouteColor(
    snapshot: DownloadSnapshot,
    host: String,
): Color {
    val index = snapshot.routes.indexOfFirst { it.host == host }.coerceAtLeast(0)
    return downloadRouteColors.getOrNull(index)?.let { Color(it) }
        ?: Color.hsv((190f + index * 137.508f) % 360f, 0.52f, 0.95f)
}

private fun downloadRate(bytesPerSecond: Long): String =
    when {
        bytesPerSecond <= 0 -> "—"
        bytesPerSecond < 1_048_576 -> "${bytesPerSecond / 1024} KiB/s"
        else -> String.format(Locale.ROOT, "%.1f MiB/s", bytesPerSecond / 1_048_576.0)
    }

private fun downloadSize(bytes: Long): String =
    when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1_048_576 -> "${bytes / 1024}K"
        bytes < 1_073_741_824 -> String.format(Locale.ROOT, "%.1fM", bytes / 1_048_576.0)
        else -> String.format(Locale.ROOT, "%.1fG", bytes / 1_073_741_824.0)
    }

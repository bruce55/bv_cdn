package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.frost819.newbv.player.download.DownloadOverheadKind
import dev.frost819.newbv.player.download.DownloadSample
import dev.frost819.newbv.player.download.DownloadSnapshot
import dev.frost819.newbv.player.download.DownloadTrack
import dev.frost819.newbv.player.download.DownloadWorkKind
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** Bounded wall-clock charts; no fabricated interpolation across missing host measurements. */
@Composable
internal fun DownloadDebugChart(
    snapshot: DownloadSnapshot,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 350.dp,
) {
    val samples = snapshot.history
    val hosts =
        snapshot.routes
            .sortedByDescending { it.attempts }
            .take(6)
            .map { it.host }
    val colors = hosts.map { downloadRouteColor(snapshot, it) }
    Column(
        modifier
            .width(panelWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("下载", color = Color.White, fontSize = 16.sp)
        val traffic = hosts.map { host -> samples.map { it.hosts[host]?.trafficBytesPerSecond?.div(1_048_576) } }
        val totalTraffic =
            samples.map { sample ->
                sample.hosts.values
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { it.trafficBytesPerSecond / 1_048_576 }
            }
        CurvePlot(
            "流量 · MiB/s",
            samples,
            traffic + listOf(totalTraffic),
            colors + Color.White,
            summaries = listOf(CurveSummary("", totalTraffic, Color.White)),
            events = true,
            prominentSeries = traffic.size,
        )
        val measured =
            hosts.map { host ->
                samples.map {
                    it.hosts[host]
                        ?.measuredBytesPerSecond
                        ?.takeIf { rate -> rate > 0 }
                        ?.div(1_048_576)
                }
            }
        val effective =
            hosts.map { host ->
                samples.map { sample ->
                    sample.hosts[host]
                        ?.takeIf { it.measuredBytesPerSecond > 0 }
                        ?.effectiveBytesPerSecond
                        ?.div(1_048_576)
                }
            }
        // Average each host once, across every measured route, rather than adding raw and penalized rates.
        val meanMeasured =
            samples.map { sample ->
                availableMean(
                    sample.hosts.values.map {
                        it.measuredBytesPerSecond
                            .takeIf { rate ->
                                rate > 0
                            }?.div(1_048_576)
                    },
                )
            }
        val meanEffective =
            samples.map { sample ->
                availableMean(
                    sample.hosts.values.map {
                        it.effectiveBytesPerSecond.takeIf { _ -> it.measuredBytesPerSecond > 0 }?.div(1_048_576)
                    },
                )
            }
        CurvePlot(
            "测速 · MiB/s",
            samples,
            measured + effective,
            colors + colors,
            summaries =
                listOf(
                    CurveSummary("实", meanMeasured, Color.White),
                    CurveSummary("效", meanEffective, Color(0xFFFFD166)),
                ),
            dashedFrom = hosts.size,
        )
        val buffer = samples.map { it.bufferSeconds }
        CurvePlot(
            "缓冲 · s",
            samples,
            listOf(buffer),
            listOf(Color(0xFF81C995)),
            summaries = listOf(CurveSummary("", buffer, Color(0xFF81C995))),
        )
        val concurrency = samples.map { it.activeRequests.toDouble() }
        CurvePlot(
            "并发",
            samples,
            listOf(concurrency),
            listOf(Color.White),
            summaries = listOf(CurveSummary("", concurrency, Color.White)),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OverheadDonut(snapshot, Modifier.width(104.dp))
            WorkerGrid(snapshot, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (kind in listOf(
                DownloadOverheadKind.Probe,
                DownloadOverheadKind.Rescue,
                DownloadOverheadKind.Failure,
                DownloadOverheadKind.Cancelled,
                DownloadOverheadKind.Other,
            )) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).background(overheadColor(kind), RoundedCornerShape(50)))
                    Text(overheadName(kind), color = Color.LightGray, fontSize = 10.sp)
                }
            }
        }
        if (samples.size < 2) Text("等待采样…", color = Color.White, fontSize = 10.sp)
    }
}

private data class CurveSummary(
    val label: String,
    val values: List<Double?>,
    val color: Color,
)

// Visible-window sample mean. Missing measurements are excluded, while measured zero remains meaningful.
private fun availableMean(values: List<Double?>): Double? =
    values
        .filterNotNull()
        .filter { it.isFinite() }
        .takeIf { it.isNotEmpty() }
        ?.average()

private fun chartNumber(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, if (it < 1 && it != 0.0) "%.2f" else "%.1f", it) }
        ?: "—"

@Composable
private fun CurvePlot(
    title: String,
    samples: List<DownloadSample>,
    series: List<List<Double?>>,
    colors: List<Color>,
    summaries: List<CurveSummary>,
    dashedFrom: Int = Int.MAX_VALUE,
    events: Boolean = false,
    prominentSeries: Int? = null,
) {
    val dashedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) }
    val maximum =
        series
            .flatten()
            .filterNotNull()
            .maxOrNull()
            ?.coerceAtLeast(1.0) ?: 1.0
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
        summaries.forEach { summary ->
            val current = chartNumber(summary.values.lastOrNull())
            val mean = chartNumber(availableMean(summary.values))
            Text(
                "${summary.label} $current Ø$mean".trimStart(),
                color = summary.color,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.semantics {
                        contentDescription = "${summary.label} 当前 $current，窗口平均 $mean"
                    },
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(29.dp)) {
        Canvas(Modifier.fillMaxWidth().height(29.dp)) {
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, size.height), Offset(size.width, size.height))
            if (samples.size < 2) return@Canvas
            val first = samples.first().elapsedMs
            val span = (samples.last().elapsedMs - first).coerceAtLeast(1)

            fun x(index: Int): Float = ((samples[index].elapsedMs - first).toDouble() / span * size.width).toFloat()

            fun y(value: Double): Float = size.height * (1 - (value / maximum).coerceIn(0.0, 1.0)).toFloat()
            series.forEachIndexed { seriesIndex, values ->
                for (index in 1 until values.size) {
                    val previous = values[index - 1] ?: continue
                    val current = values[index] ?: continue
                    drawLine(
                        colors[seriesIndex].copy(
                            alpha =
                                when {
                                    seriesIndex == prominentSeries -> 1f
                                    prominentSeries != null -> 0.32f
                                    seriesIndex >= dashedFrom -> 1f
                                    else -> 0.65f
                                },
                        ),
                        Offset(x(index - 1), y(previous)),
                        Offset(x(index), y(current)),
                        strokeWidth = (if (seriesIndex == prominentSeries) 2.dp else 1.3.dp).toPx(),
                        pathEffect =
                            if (seriesIndex >=
                                dashedFrom
                            ) {
                                dashedEffect
                            } else {
                                null
                            },
                    )
                }
            }
            if (events) {
                for (index in 1 until samples.size) {
                    val before = samples[index - 1]
                    val current = samples[index]
                    val color =
                        when {
                            current.failures > before.failures -> Color(0xFFF06CFF)
                            current.rescues > before.rescues -> Color(0xFFFF9F43)
                            current.explorations > before.explorations -> Color(0xFFFFD166)
                            else -> continue
                        }
                    drawLine(color, Offset(x(index), 0f), Offset(x(index), size.height), 1.dp.toPx())
                }
            }
        }
        Text(
            "0–${chartNumber(maximum)}",
            modifier = Modifier.align(Alignment.TopEnd),
            color = Color.Gray,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun OverheadDonut(
    snapshot: DownloadSnapshot,
    modifier: Modifier = Modifier,
) {
    val stats = snapshot.transferStats
    val portions = stats.overheadBreakdown.filter { it.bytes > 0 }
    val total = stats.overheadBytes.coerceAtLeast(0)
    val description =
        buildString {
            append("${if (stats.exact) "下载开销" else "估算下载开销"} ${overheadSize(total)}")
            portions.forEach { append("，${overheadName(it.kind)} ${overheadSize(it.bytes)}") }
        }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (stats.exact) "开销" else "估算开销", color = Color.LightGray, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().height(104.dp).semantics { contentDescription = description }) {
            Canvas(Modifier.matchParentSize()) {
                val diameter = minOf(size.width, size.height) - 14.dp.toPx()
                val ring = 9.dp.toPx()
                val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                drawArc(
                    Color.White.copy(alpha = 0.12f),
                    -90f,
                    360f,
                    false,
                    origin,
                    Size(diameter, diameter),
                    style = Stroke(ring),
                )
                if (total <= 0) return@Canvas
                var angle = -90f
                var remaining = total
                for (portion in portions) {
                    val bytes = portion.bytes.coerceAtMost(remaining)
                    val sweep = (bytes.toDouble() / total * 360).toFloat()
                    drawArc(
                        overheadColor(portion.kind),
                        angle,
                        sweep,
                        false,
                        origin,
                        Size(diameter, diameter),
                        style = Stroke(ring),
                    )
                    angle += sweep
                    remaining -= bytes
                    if (remaining == 0L) break
                }
                // Unattributed overhead remains neutral; request purpose alone does not imply waste.
                if (remaining > 0) {
                    drawArc(
                        overheadColor(DownloadOverheadKind.Other),
                        angle,
                        (remaining.toDouble() / total * 360).toFloat(),
                        false,
                        origin,
                        Size(diameter, diameter),
                        style = Stroke(ring),
                    )
                }
            }
            Text(
                overheadSize(total),
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun WorkerGrid(
    snapshot: DownloadSnapshot,
    modifier: Modifier = Modifier,
) {
    val count = snapshot.maxRequests.coerceIn(2, 64)
    val workers = snapshot.workers.filter { it.slot in 0 until count }.associateBy { it.slot }
    val videoIcon = rememberVectorPainter(Icons.Rounded.Videocam)
    val audioIcon = rememberVectorPainter(Icons.Rounded.MusicNote)
    val description =
        buildString {
            append("下载线程 ${workers.size}/$count，空闲 ${count - workers.size}")
            workers.toSortedMap().forEach { (slot, worker) ->
                val track = if (worker.track == DownloadTrack.Audio) "音频" else "视频"
                val purpose =
                    when (worker.kind) {
                        DownloadWorkKind.Ordinary -> "下载"
                        DownloadWorkKind.Probe -> "探测"
                        DownloadWorkKind.Exploration -> "探测"
                        DownloadWorkKind.Rescue -> "补救"
                    }
                append("；${slot + 1} $track $purpose ${worker.host}")
            }
        }
    Column(modifier) {
        val waiting = if (workers.size < count) " · ${workerWaitLabel(snapshot.waitReason)}" else ""
        Text("线程 ${workers.size}/$count$waiting", color = Color.LightGray, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(104.dp).semantics { contentDescription = description }) {
            val gap = 3.dp.toPx()
            val columns =
                ceil(sqrt(count * (size.width / size.height).coerceAtLeast(1f))).toInt().coerceIn(2, count)
            val rows = (count + columns - 1) / columns
            val cell =
                minOf(
                    (size.width - gap * (columns - 1)) / columns,
                    (size.height - gap * (rows - 1)) / rows,
                    24.dp.toPx(),
                )
            if (cell <= 0) return@Canvas
            val originY = (size.height - rows * cell - (rows - 1) * gap) / 2f
            val iconSize = minOf(12.dp.toPx(), cell * 0.62f)
            for (slot in 0 until count) {
                val worker = workers[slot]
                val left = slot % columns * (cell + gap)
                val top = originY + slot / columns * (cell + gap)
                val color = worker?.let { downloadRouteColor(snapshot, it.host) } ?: Color(0xFF555A60)
                drawRoundRect(
                    color.copy(alpha = if (worker == null) 0.38f else 0.75f),
                    Offset(left, top),
                    Size(cell, cell),
                    CornerRadius(2.dp.toPx()),
                )
                if (worker == null) continue
                val purposeColor =
                    when (worker.kind) {
                        DownloadWorkKind.Ordinary -> null
                        DownloadWorkKind.Probe, DownloadWorkKind.Exploration -> Color(0xFFFFD166)
                        DownloadWorkKind.Rescue -> Color(0xFFFF9F43)
                    }
                if (purposeColor != null) {
                    val stripe = minOf(2.dp.toPx(), cell * 0.18f)
                    drawRect(purposeColor, Offset(left, top + cell - stripe), Size(cell, stripe))
                }
                translate(left + (cell - iconSize) / 2f, top + (cell - iconSize) / 2f) {
                    with(if (worker.track == DownloadTrack.Audio) audioIcon else videoIcon) {
                        draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(Color.White))
                    }
                }
            }
        }
    }
}

private fun overheadColor(kind: DownloadOverheadKind): Color =
    when (kind) {
        DownloadOverheadKind.Probe -> Color(0xFFFFD166)
        DownloadOverheadKind.Exploration -> Color(0xFFFFD166)
        DownloadOverheadKind.Rescue -> Color(0xFFFF9F43)
        DownloadOverheadKind.Failure -> Color(0xFFF06CFF)
        DownloadOverheadKind.Cancelled -> Color(0xFF9AA0A6)
        DownloadOverheadKind.Other -> Color(0xFF637080)
    }

private fun overheadName(kind: DownloadOverheadKind): String =
    when (kind) {
        DownloadOverheadKind.Probe -> "探测"
        DownloadOverheadKind.Exploration -> "探测"
        DownloadOverheadKind.Rescue -> "补救"
        DownloadOverheadKind.Failure -> "异常"
        DownloadOverheadKind.Cancelled -> "取消"
        DownloadOverheadKind.Other -> "其他"
    }

private fun overheadSize(bytes: Long): String =
    when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1_048_576 -> "${bytes / 1024}K"
        bytes < 1_073_741_824 -> String.format(Locale.ROOT, "%.1fM", bytes / 1_048_576.0)
        else -> String.format(Locale.ROOT, "%.1fG", bytes / 1_073_741_824.0)
    }

private fun workerWaitLabel(reason: String?): String =
    when (reason) {
        "payload_budget", "shared_byte_watermark", "cache_budget" -> "缓存满"
        "shared_byte_headroom" -> "保留余量"
        "cdn_recovery_wait", "no_eligible_cdn", "cdn_unavailable" -> "等待CDN"
        "coordinator_queue", "coordinator_queue_full", "head_queue_full", "ordinary_slots", "global_slots" -> "队列满"
        "no_reader_work", "all_ranges_dispatched", null -> "无请求"
        else -> "等待"
    }

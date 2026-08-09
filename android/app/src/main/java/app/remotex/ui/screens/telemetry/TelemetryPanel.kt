package app.remotex.ui.screens.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.LocalPalette
import app.remotex.ui.theme.Ok
import kotlin.math.roundToInt

/**
 * Host telemetry, mirroring the web sidebar: CPU / RAM / GPU(s) /
 * network cards, each with a live sparkline and now/peak/floor tiles.
 *
 * The polling already happens in RemotexViewModel (startTelemetryPoll →
 * state.hostTelemetry); this is purely the view over that state.
 */
@Composable
fun TelemetryPanel(
    hostLabel: String,
    snapshot: HostTelemetrySnapshot?,
    modifier: Modifier = Modifier,
) {
    val data = snapshot?.data
    val history = snapshot?.history
    val liveMs = snapshot?.lastUpdateMs ?: 0L
    val live = liveMs > 0 && System.currentTimeMillis() - liveMs < 12_000

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SYSTEM TELEMETRY",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        hostLabel,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                Dot(if (live) Ok else InkDim)
                Spacer(Modifier.height(0.dp))
                Text(
                    if (live) " live · 3s" else " stale",
                    color = if (live) Ok else InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }

        if (data == null) {
            item {
                Text(
                    "waiting for the host to report…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
            return@LazyColumn
        }

        data.cpu?.let { cpu ->
            item {
                MetricCard(
                    title = "CPU",
                    value = "${fmt(cpu.percent)}%",
                    sub = listOfNotNull(
                        cpu.cores?.let { "$it cores" },
                        cpu.tempC?.let { "${fmt(it)}°C package" },
                    ).joinToString(" · "),
                    series = history?.cpu ?: emptyList(),
                    max = 100f,
                    color = LocalPalette.current.accentDeep,
                    unit = "%",
                )
            }
        }
        data.memory?.let { mem ->
            item {
                MetricCard(
                    title = "RAM",
                    value = "${fmt(mem.percent)}%",
                    sub = "${gb(mem.usedBytes)} / ${gb(mem.totalBytes)}",
                    series = history?.mem ?: emptyList(),
                    max = 100f,
                    color = LocalPalette.current.accentDeep,
                    unit = "%",
                )
            }
        }
        val gpus = if (data.gpus.isNotEmpty()) data.gpus else listOfNotNull(data.gpu)
        itemsIndexedCompat(gpus) { index, gpu ->
            MetricCard(
                title = if (gpus.size > 1) "GPU ${index + 1}" else "GPU",
                value = "${fmt(gpu.percent)}%",
                sub = listOfNotNull(
                    gpu.name,
                    gpu.memUsedMb?.let { used ->
                        gpu.memTotalMb?.let { total ->
                            "${(used / 1024).roundToInt()} GB / ${(total / 1024).roundToInt()} GB VRAM"
                        }
                    },
                ).joinToString(" · "),
                // Only the first GPU has client-side history (the ring
                // buffer tracks data.gpu); others show current value only.
                series = if (index == 0) history?.gpu ?: emptyList() else emptyList(),
                max = 100f,
                color = Ok,
                unit = "%",
            )
        }
        data.network?.let { net ->
            item {
                MetricCard(
                    title = "NETWORK",
                    value = "↑ ${bps(net.upBps)}",
                    sub = "↓ ${bps(net.downBps)} · 3s transfer rate",
                    series = history?.down ?: emptyList(),
                    max = null,
                    color = Amber,
                    unit = "",
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Fact("UPTIME", uptime(data.uptimeS))
                Fact("LOAD AVG", data.loadAvg?.take(3)?.joinToString(" ") { fmt(it) } ?: "—")
            }
        }
    }
}

// LazyListScope.itemsIndexed needs the foundation import; keep the call
// site readable and avoid pulling the whole items() overload set in.
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    list: List<app.remotex.model.GpuTelemetry>,
    crossinline row: @Composable (Int, app.remotex.model.GpuTelemetry) -> Unit,
) {
    list.forEachIndexed { index, gpu ->
        item(key = "gpu-$index") { row(index, gpu) }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    sub: String,
    series: List<Float>,
    max: Float?,
    color: Color,
    unit: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalPalette.current.panel, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(title, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (sub.isNotBlank()) {
            Text(sub, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        if (series.size > 1) {
            Spacer(Modifier.height(8.dp))
            Sparkline(series, max, color, Modifier.fillMaxWidth().height(56.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("NOW", "${fmt(series.last().toDouble())}$unit", Modifier.weight(1f))
                Tile("PEAK", "${fmt(series.max().toDouble())}$unit", Modifier.weight(1f))
                Tile("FLOOR", "${fmt(series.min().toDouble())}$unit", Modifier.weight(1f))
            }
        }
    }
}

/**
 * Same autoscale rule as the web Sparkline: the axis zooms to ~1.3× the
 * observed peak (floored at 8% of full scale) so a 3% CPU line has shape
 * instead of hugging the bottom of a fixed 0-100 plot.
 */
@Composable
private fun Sparkline(series: List<Float>, max: Float?, color: Color, modifier: Modifier) {
    val grid = Line
    Canvas(modifier) {
        val dataMax = series.max()
        val cap = when {
            max != null -> maxOf(minOf(max, dataMax * 1.3f), max * 0.08f, 0.001f)
            else -> maxOf(dataMax * 1.15f, 1f)
        }
        val h = size.height
        val w = size.width
        val padY = 4f
        // grid
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
        }
        val stepX = if (series.size > 1) w / (series.size - 1) else w
        val path = Path()
        val fill = Path()
        series.forEachIndexed { i, raw ->
            val v = raw.coerceIn(0f, cap)
            val x = stepX * i
            val y = h - padY - (v / cap) * (h - padY * 2)
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()
        drawPath(fill, color.copy(alpha = 0.16f))
        drawPath(path, color, style = Stroke(width = 2f))
        // endpoint marker
        val lastV = series.last().coerceIn(0f, cap)
        drawCircle(
            color,
            radius = 3.5f,
            center = androidx.compose.ui.geometry.Offset(w, h - padY - (lastV / cap) * (h - padY * 2)),
        )
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(LocalPalette.current.panel2, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(value, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(value, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .height(8.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(4.dp),
    ) {
        Spacer(Modifier.height(8.dp))
    }
}

private fun fmt(v: Double?): String {
    val d = v ?: return "—"
    return if (d >= 10) d.roundToInt().toString() else String.format("%.1f", d)
}

private fun gb(bytes: Long?): String {
    val b = bytes ?: return "—"
    return String.format("%.1f GB", b / 1_073_741_824.0)
}

private fun bps(v: Long?): String {
    val b = (v ?: 0L).toDouble()
    return when {
        b >= 1_000_000 -> String.format("%.1f Mbps", b / 1_000_000)
        b >= 1_000 -> String.format("%.0f kbps", b / 1_000)
        else -> "${b.roundToInt()} bps"
    }
}

private fun uptime(seconds: Long?): String {
    val s = seconds ?: return "—"
    val days = s / 86_400
    val hours = (s % 86_400) / 3_600
    return if (days > 0) "${days}d ${hours}h" else "${hours}h"
}

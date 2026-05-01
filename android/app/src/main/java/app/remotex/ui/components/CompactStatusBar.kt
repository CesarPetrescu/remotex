package app.remotex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.GpuTelemetry
import app.remotex.model.Host
import app.remotex.model.HostTelemetryData
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

/**
 * Live host status card. Shown at the top of the threads/session
 * screens. Two rows:
 *   1. identity — hostname + os_user + online dot, sized for legibility
 *   2. metric chips — CPU / RAM / GPU(s) / uptime / temp
 * Hidden chips never render (zero-GPU box has no GPU section), so the
 * card always shows exactly the data we have.
 */
@Composable
fun CompactStatusBar(
    host: Host?,
    data: HostTelemetryData?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IdentityRow(host = host, uptimeS = data?.uptimeS)
            MetricsRow(data = data)
        }
    }
}

@Composable
private fun IdentityRow(host: Host?, uptimeS: Long?) {
    val online = host?.online == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (online) Ok else InkDim),
        )
        Text(
            host?.nickname ?: "no host",
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        host?.osUser?.takeIf { it.isNotBlank() }?.let {
            Text(
                "@$it",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Box(Modifier.weight(1f))
        // A6: bumped to 11sp + colored chip border so it's actually
        // legible from arm's length instead of an afterthought.
        Surface(
            color = (if (online) Ok else InkDim).copy(alpha = 0.10f),
            border = BorderStroke(1.dp, (if (online) Ok else InkDim).copy(alpha = 0.55f)),
            shape = RectangleShape,
        ) {
            Text(
                text = if (online) "ONLINE" else "OFFLINE",
                color = if (online) Ok else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        uptimeS?.takeIf { it > 0L }?.let {
            Text(
                "up ${uptimeShort(it)}",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MetricsRow(data: HostTelemetryData?) {
    if (data == null) {
        Text(
            text = "no telemetry yet",
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        return
    }
    LazyRow(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        data.cpu?.percent?.let {
            item { MetricCell("CPU", pct(it), Amber, sub = data.cpu.cores?.let { c -> "${c}c" }) }
        }
        data.memory?.percent?.let {
            item {
                val sub = if (data.memory.totalBytes != null) {
                    val totGb = data.memory.totalBytes / 1024.0 / 1024.0 / 1024.0
                    "${"%.0f".format(totGb)}GB"
                } else null
                MetricCell("RAM", pct(it), Color(0xFF60A5FA), sub = sub)
            }
        }
        data.gpus.takeIf { it.isNotEmpty() }?.forEachIndexed { idx, g ->
            item { GpuCell(idx = idx, total = data.gpus.size, gpu = g) }
        }
        data.cpu?.tempC?.let {
            item { MetricCell("TEMP", "${it.toInt()}°", Warn, sub = null) }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, accent: Color, sub: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(accent))
            Spacer(Modifier.width(4.dp))
            Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        Spacer(Modifier.size(2.dp))
        Text(value, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        if (sub != null) {
            Text(sub, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

@Composable
private fun GpuCell(idx: Int, total: Int, gpu: GpuTelemetry) {
    val short = gpuShortName(gpu.name) ?: "GPU"
    val label = (if (total > 1) "${short}#${idx + 1}" else short).uppercase()
    val value = gpu.percent?.let { pct(it) }
        ?: gpu.memTotalMb?.let { tot ->
            val used = gpu.memUsedMb ?: 0.0
            "${(used / tot * 100).toInt()}%"
        }
        ?: "—"
    val sub = gpu.memTotalMb?.let { "${"%.0f".format(it / 1024.0)}GB" }
    MetricCell(label = label, value = value, accent = Ok, sub = sub)
}

private fun pct(v: Double): String =
    if (v >= 10.0) "${v.toInt()}%" else "${"%.1f".format(v)}%"

private fun uptimeShort(s: Long): String {
    val d = s / 86_400; val h = (s % 86_400) / 3_600; val m = (s % 3_600) / 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}

private fun gpuShortName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val tokens = name.split(' ').filter { it.isNotBlank() }
    val model = tokens.firstOrNull { it.length in 3..6 && it.any(Char::isDigit) }
    return model ?: tokens.lastOrNull()
}

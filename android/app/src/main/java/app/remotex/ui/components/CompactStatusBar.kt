package app.remotex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * One-line live host status used at the top of the threads/session
 * screens. Renders only the chips we actually have data for — no big
 * panel, no charts. CPU/RAM/uptime always; one GPU chip per attached
 * GPU (zero chips if none, N chips if many).
 */
@Composable
fun CompactStatusBar(
    host: Host?,
    data: HostTelemetryData?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (host?.online == true) Ok else InkDim)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        host?.nickname ?: "no host",
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            data?.cpu?.percent?.let {
                item { StatusChip(label = "CPU", value = pct(it), accent = Amber) }
            }
            data?.memory?.percent?.let {
                item { StatusChip(label = "RAM", value = pct(it), accent = Color(0xFF60A5FA)) }
            }
            data?.gpus?.takeIf { it.isNotEmpty() }?.forEachIndexed { idx, g ->
                item { GpuChip(idx = idx, total = data.gpus.size, gpu = g) }
            }
            data?.uptimeS?.takeIf { it > 0L }?.let {
                item { StatusChip(label = "UP", value = uptimeShort(it), accent = InkDim) }
            }
            data?.cpu?.tempC?.let {
                item { StatusChip(label = "TEMP", value = "${it.toInt()}°", accent = Warn) }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(accent))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun GpuChip(idx: Int, total: Int, gpu: GpuTelemetry) {
    val short = gpuShortName(gpu.name) ?: "GPU"
    // When there are multiple GPUs, prefix with index so users can
    // tell them apart. Single-GPU box just shows the short name.
    val label = if (total > 1) "${short}#${idx + 1}" else short
    val value = gpu.percent?.let { pct(it) }
        ?: gpu.memTotalMb?.let { tot ->
            val used = gpu.memUsedMb ?: 0.0
            "${(used / tot * 100).toInt()}% mem"
        }
        ?: "—"
    StatusChip(label = label.uppercase(), value = value, accent = Ok)
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

/** Trim "NVIDIA GeForce RTX 4060 Laptop GPU" → "4060". */
private fun gpuShortName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val tokens = name.split(' ').filter { it.isNotBlank() }
    // Prefer the model number token (RTX 4060, A100, etc.). Fall back to the last
    // meaningful token if the heuristic finds nothing.
    val model = tokens.firstOrNull { it.length in 3..6 && it.any(Char::isDigit) }
    return model ?: tokens.lastOrNull()
}

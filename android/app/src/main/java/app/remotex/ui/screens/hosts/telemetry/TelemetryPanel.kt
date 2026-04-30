package app.remotex.ui.screens.hosts.telemetry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.Host
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok

@Composable
fun TelemetryPanel(snapshot: HostTelemetrySnapshot?, selectedHost: Host?) {
    val d = snapshot?.data
    val live = snapshot != null &&
        (System.currentTimeMillis() - snapshot.lastUpdateMs) < 10_000
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SYSTEM TELEMETRY",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Text(
                        selectedHost?.nickname ?: "no host selected",
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TelemetryPill(
                        label = if (live) "LIVE" else if (selectedHost?.online == true) "STALE" else "OFFLINE",
                        value = "3s",
                        accent = if (live) Ok else InkDim,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryCard(
                    label = "CPU",
                    main = if (d?.cpu?.percent != null) "${formatPct(d.cpu.percent)}%" else "—",
                    sub = d?.cpu?.cores?.let { "$it cores" } ?: "",
                    note = d?.cpu?.tempC?.let { "${it.toInt()}°C package" } ?: "processor load",
                    points = snapshot?.history?.cpu ?: emptyList(),
                    max = 100f,
                    color = Amber,
                    modifier = Modifier.weight(1f),
                    stats = summarizeSeries(snapshot?.history?.cpu ?: emptyList(), ::formatPctLabel),
                )
                TelemetryCard(
                    label = "RAM",
                    main = if (d?.memory?.percent != null) "${formatPct(d.memory.percent)}%" else "—",
                    sub = d?.memory?.let { fmtMemoryShort(it.usedBytes, it.totalBytes) } ?: "",
                    note = if (d?.memory != null) "resident working set" else "memory pressure",
                    points = snapshot?.history?.mem ?: emptyList(),
                    max = 100f,
                    color = Color(0xFF60A5FA),
                    modifier = Modifier.weight(1f),
                    stats = summarizeSeries(snapshot?.history?.mem ?: emptyList(), ::formatPctLabel),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryCard(
                    label = "GPU",
                    main = when {
                        d?.gpu?.percent != null -> "${formatPct(d.gpu.percent)}%"
                        d?.gpu != null -> "—"
                        else -> "n/a"
                    },
                    sub = d?.gpu?.name?.take(24) ?: (if (d?.gpu != null) "" else "no GPU"),
                    note = when {
                        d?.gpu?.memTotalMb != null ->
                            "${fmtMegabytes(d.gpu.memUsedMb)} / ${fmtMegabytes(d.gpu.memTotalMb)} VRAM"
                        d?.gpu != null -> "accelerator state"
                        else -> "accelerator unavailable"
                    },
                    points = snapshot?.history?.gpu ?: emptyList(),
                    max = 100f,
                    color = Ok,
                    modifier = Modifier.weight(1f),
                    stats = summarizeSeries(snapshot?.history?.gpu ?: emptyList(), ::formatPctLabel),
                )
                TelemetryCard(
                    label = "NETWORK",
                    main = "",
                    mainRow = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TelemetryInlineLegend("UP", fmtBps(d?.network?.upBps), Color(0xFFF472B6))
                            TelemetryInlineLegend("DOWN", fmtBps(d?.network?.downBps), Color(0xFFA78BFA))
                        }
                    },
                    sub = "",
                    note = "3 second rolling transfer window",
                    points = snapshot?.history?.down ?: emptyList(),
                    secondaryPoints = snapshot?.history?.up ?: emptyList(),
                    max = null,
                    color = Color(0xFFA78BFA),
                    modifier = Modifier.weight(1f),
                    stats = summarizeNetwork(
                        snapshot?.history?.up ?: emptyList(),
                        snapshot?.history?.down ?: emptyList(),
                    ),
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TelemetryStat("Uptime", fmtUptime(d?.uptimeS))
                TelemetryStat(
                    "Load",
                    d?.loadAvg?.joinToString(" ") { String.format("%.2f", it) } ?: "—",
                )
                TelemetryStat(
                    "Temp",
                    d?.cpu?.tempC?.let { "${it.toInt()}°C" } ?: "—",
                )
            }
        }
    }
}

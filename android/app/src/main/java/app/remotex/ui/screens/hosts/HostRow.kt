package app.remotex.ui.screens.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.Host
import app.remotex.model.HostTelemetryData
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok

@Composable
internal fun HostRow(
    host: Host,
    data: HostTelemetryData?,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, if (host.online) Amber.copy(alpha = 0.6f) else Line),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = host.online, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (host.online) Ok else InkDim))
                Spacer(Modifier.width(8.dp))
                Text(
                    host.nickname,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                host.osUser?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "@$it",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f))
                if (host.online) {
                    Text(
                        "open →",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                summarize(host, data),
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun summarize(host: Host, data: HostTelemetryData?): String {
    val parts = mutableListOf<String>()
    parts += if (host.online) "online" else "offline"
    if (data != null) {
        data.cpu?.percent?.let { parts += "${pct(it)} cpu" }
        data.memory?.percent?.let { parts += "${pct(it)} ram" }
        when (val n = data.gpus.size) {
            0 -> {} // skip GPU section entirely
            1 -> data.gpus[0].percent?.let { parts += "${pct(it)} gpu" }
            else -> parts += "$n gpus"
        }
        data.uptimeS?.takeIf { it > 0L }?.let { parts += "up ${uptime(it)}" }
    } else if (host.online) {
        parts += "no telemetry yet"
    }
    return parts.joinToString(" · ")
}

private fun pct(v: Double): String =
    if (v >= 10.0) "${v.toInt()}%" else "${"%.1f".format(v)}%"

private fun uptime(s: Long): String {
    val d = s / 86_400; val h = (s % 86_400) / 3_600; val m = (s % 3_600) / 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}

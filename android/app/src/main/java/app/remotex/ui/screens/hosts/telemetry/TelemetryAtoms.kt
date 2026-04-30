package app.remotex.ui.screens.hosts.telemetry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

@Composable
internal fun TelemetryStat(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Text(
            value,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun TelemetryPill(label: String, value: String, accent: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(label, color = accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(value, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

@Composable
internal fun TelemetryInlineLegend(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            "$label $value",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

@Composable
internal fun TelemetryMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0x0FFFFFFF),
        border = BorderStroke(1.dp, InkDim.copy(alpha = 0.12f)),
        shape = RectangleShape,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 7.sp)
            Text(
                value,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

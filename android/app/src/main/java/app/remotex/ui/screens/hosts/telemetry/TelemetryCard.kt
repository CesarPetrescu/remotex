package app.remotex.ui.screens.hosts.telemetry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.remotex.ui.theme.Line

@Composable
internal fun TelemetryCard(
    label: String,
    main: String,
    sub: String,
    note: String,
    points: List<Float>,
    max: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    secondaryPoints: List<Float> = emptyList(),
    mainRow: (@Composable () -> Unit)? = null,
    stats: List<Pair<String, String>> = emptyList(),
) {
    Surface(
        color = Color(0xFF121A2C).copy(alpha = 0.92f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color)
                )
            }
            if (mainRow != null) {
                mainRow()
            } else {
                Text(
                    main,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                )
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                note,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TelemetryTrendChart(
                primary = points,
                secondary = secondaryPoints,
                primaryColor = color,
                secondaryColor = Color(0xFFF472B6),
                max = max,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            )
            if (stats.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.take(3).forEach { (statLabel, statValue) ->
                        TelemetryMiniStat(statLabel, statValue, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

package app.remotex.ui.screens.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.SearchStage
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

/**
 * Vertical list of search-pipeline stages. Renders the same shape as
 * the web client: status icon · name · trailing time/count/error.
 * Hidden when there are no stages.
 */
@Composable
internal fun SearchStagesPanel(stages: List<SearchStage>) {
    if (stages.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "PIPELINE",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            stages.forEach { stage -> StageRow(stage) }
        }
    }
}

@Composable
private fun StageRow(stage: SearchStage) {
    val (color, icon) = stageVisuals(stage.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StageIcon(icon, color, stage.status == "running")
        Spacer(Modifier.width(8.dp))
        Text(
            stage.name,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        // Trailing trailing summary: "420ms · 24" when known, error otherwise.
        val trailing = buildString {
            stage.elapsedMs?.let { append(if (it < 1000) "${it}ms" else "${"%.1f".format(it / 1000.0)}s") }
            stage.count?.let {
                if (isNotEmpty()) append(" · ")
                append("$it")
            }
            stage.error?.let {
                if (isNotEmpty()) append(" · ")
                append(it.take(40))
            }
        }
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                color = if (stage.status == "error") Warn else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun StageIcon(symbol: String, color: Color, spinning: Boolean) {
    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "stage-spinner")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "stage-spinner-angle",
        )
        angle
    } else {
        0f
    }
    Box(
        Modifier
            .size(14.dp)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = if (spinning) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
        )
    }
}

private fun stageVisuals(status: String): Pair<Color, String> = when (status) {
    "done" -> Ok to "✓"
    "running" -> Amber to "↻"
    "error" -> Warn to "✗"
    "skipped" -> InkDim to "—"
    else -> InkDim to "▸"  // pending
}

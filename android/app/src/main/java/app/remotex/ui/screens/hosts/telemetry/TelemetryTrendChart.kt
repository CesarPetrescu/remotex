package app.remotex.ui.screens.hosts.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.remotex.ui.theme.InkDim

@Composable
internal fun TelemetryTrendChart(
    primary: List<Float>,
    secondary: List<Float> = emptyList(),
    primaryColor: Color,
    secondaryColor: Color,
    max: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padX = 6.dp.toPx()
        val padY = 5.dp.toPx()
        val plotW = w - padX * 2
        val plotH = h - padY * 2
        val safePrimary = if (primary.isEmpty()) listOf(0f, 0f) else primary
        val safeSecondary = if (secondary.isEmpty()) emptyList() else secondary
        val cap = max ?: listOf(
            safePrimary.maxOrNull() ?: 0f,
            safeSecondary.maxOrNull() ?: 0f,
            1f,
        ).max()

        repeat(3) { index ->
            val y = padY + plotH * (index / 2f)
            drawLine(
                color = InkDim.copy(alpha = 0.14f),
                start = Offset(padX, y),
                end = Offset(w - padX, y),
                strokeWidth = 1f,
            )
        }
        repeat(4) { index ->
            val x = padX + plotW * (index / 3f)
            drawLine(
                color = InkDim.copy(alpha = 0.1f),
                start = Offset(x, padY),
                end = Offset(x, h - padY),
                strokeWidth = 1f,
            )
        }

        fun path(samples: List<Float>): Pair<Path, Offset>? {
            if (samples.size < 2) return null
            val step = plotW / (samples.size - 1).toFloat()
            val p = Path()
            var last = Offset(padX, h - padY)
            samples.forEachIndexed { i, v ->
                val x = padX + i * step
                val clamped = v.coerceIn(0f, cap)
                val y = h - padY - (clamped / cap) * plotH
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                last = Offset(x, y)
            }
            return p to last
        }

        fun areaPath(samples: List<Float>): Path? {
            if (samples.size < 2) return null
            val step = plotW / (samples.size - 1).toFloat()
            val p = Path()
            p.moveTo(padX, h - padY)
            samples.forEachIndexed { i, v ->
                val x = padX + i * step
                val clamped = v.coerceIn(0f, cap)
                val y = h - padY - (clamped / cap) * plotH
                p.lineTo(x, y)
            }
            p.lineTo(padX + plotW, h - padY)
            p.close()
            return p
        }

        areaPath(safeSecondary)?.let {
            drawPath(
                path = it,
                brush = Brush.verticalGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.18f), secondaryColor.copy(alpha = 0.02f)),
                ),
                style = Fill,
            )
        }
        areaPath(safePrimary)?.let {
            drawPath(
                path = it,
                brush = Brush.verticalGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.28f), primaryColor.copy(alpha = 0.03f)),
                ),
                style = Fill,
            )
        }
        path(safeSecondary)?.let { (p, last) ->
            drawPath(p, secondaryColor, style = Stroke(width = 2f))
            drawCircle(secondaryColor, radius = 3f, center = last)
        }
        path(safePrimary)?.let { (p, last) ->
            drawPath(p, primaryColor, style = Stroke(width = 2.2f))
            drawCircle(primaryColor, radius = 3.4f, center = last)
        }
        drawRect(
            color = InkDim.copy(alpha = 0.14f),
            topLeft = Offset(padX, padY),
            size = Size(plotW, plotH),
            style = Stroke(width = 1f),
        )
    }
}

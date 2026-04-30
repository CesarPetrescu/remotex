package app.remotex.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ok

@Composable
fun WireMeshBackdrop(
    modifier: Modifier = Modifier,
    lineAlpha: Float = 0.22f,
    diagAlpha: Float = 0.16f,
) {
    Canvas(modifier = modifier) {
        val grid = 40.dp.toPx()
        val diagStep = grid * 3f
        val baseLine = Amber.copy(alpha = lineAlpha)
        val diagLine = Ok.copy(alpha = diagAlpha)

        var y = 0f
        while (y <= size.height) {
            drawLine(baseLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += grid
        }
        var x = 0f
        while (x <= size.width) {
            drawLine(baseLine.copy(alpha = lineAlpha * 0.78f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += grid
        }
        var offset = -size.height
        while (offset <= size.width) {
            drawLine(
                diagLine,
                Offset(offset, size.height),
                Offset(offset + size.height, 0f),
                strokeWidth = 1f,
            )
            offset += diagStep
        }
    }
}

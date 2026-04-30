package app.remotex.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ok

@Composable
fun WireMeshBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxDim = maxOf(w, h)

        drawBackdropOrb(
            centerX = w * 0.12f,
            centerY = -h * 0.10f,
            radius = maxDim * 0.55f,
            color = Amber,
            alpha = 0.10f,
        )
        drawBackdropOrb(
            centerX = w * 0.95f,
            centerY = h * 1.10f,
            radius = maxDim * 0.55f,
            color = Ok,
            alpha = 0.08f,
        )
        drawBackdropOrb(
            centerX = w * 0.50f,
            centerY = h * 0.50f,
            radius = maxDim * 0.40f,
            color = AccentDeep,
            alpha = 0.04f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdropOrb(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: androidx.compose.ui.graphics.Color,
    alpha: Float,
) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = Offset(centerX, centerY),
            radius = radius,
        ),
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
    )
}

package app.remotex.ui.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.Screen
import app.remotex.ui.Status
import app.remotex.ui.UiState
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

/**
 * A4: distinguish "no host selected" from "ready" from "connecting".
 * The previous "idle" label read as broken — users assumed the app
 * was disconnected when really nothing had been opened yet.
 */
@Composable
fun StatusBadge(state: UiState) {
    val (color, text, pulse) = labelFor(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = color, pulse = pulse)
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color, pulse: Boolean) {
    if (!pulse) {
        Box(Modifier.size(8.dp).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "status-pulse-alpha",
    )
    Box(Modifier.size(8.dp).background(color).alpha(alpha))
}

private fun labelFor(state: UiState): Triple<androidx.compose.ui.graphics.Color, String, Boolean> = when (state.status) {
    Status.Connected -> Triple(Ok, "connected", false)
    Status.Opening, Status.Connecting -> Triple(Amber, "connecting…", true)
    Status.Disconnected -> Triple(Warn, "disconnected", false)
    Status.Error -> Triple(Warn, "error", false)
    Status.Idle -> when {
        // No host picked yet → fresh app, point them at the host list.
        state.selectedHostId.isNullOrBlank() && state.screen == Screen.Hosts ->
            Triple(InkDim, "select a host", false)
        state.selectedHostId.isNullOrBlank() ->
            Triple(InkDim, "no host", false)
        // Host selected, no live session yet → ready to start one.
        state.session == null -> Triple(InkDim, "ready", false)
        else -> Triple(InkDim, "idle", false)
    }
}

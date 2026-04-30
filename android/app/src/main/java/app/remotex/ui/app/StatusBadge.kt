package app.remotex.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.Status
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

@Composable
fun StatusBadge(status: Status) {
    val (color, text) = when (status) {
        Status.Connected -> Ok to "connected"
        Status.Connecting, Status.Opening -> Amber to "connecting"
        Status.Idle -> InkDim to "idle"
        Status.Disconnected -> Warn to "disconnected"
        Status.Error -> Warn to "error"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

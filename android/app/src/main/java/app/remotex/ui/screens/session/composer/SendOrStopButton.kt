package app.remotex.ui.screens.session.composer

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Warn

@Composable
internal fun SendOrStopButton(
    pending: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = when {
            pending -> Warn
            canSend -> Amber
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RectangleShape,
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(
            onClick = { if (pending) onStop() else if (canSend) onSend() },
            enabled = pending || canSend,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = if (pending) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                contentDescription = if (pending) "Stop" else "Send",
                tint = Color.Black,
            )
        }
    }
}

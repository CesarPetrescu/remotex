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

/**
 * Send / steer / stop.
 *
 *  - idle: amber up-arrow, fires turn-start.
 *  - turn running: red stop square, fires turn-interrupt.
 *  - turn running *and* text typed: amber up-arrow again — the message is
 *    steered into the live turn (codex turn/steer) instead of interrupting
 *    and retyping. Matches the web client.
 */
@Composable
internal fun SendOrStopButton(
    pending: Boolean,
    canSend: Boolean,
    canSteer: Boolean = false,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSteer: () -> Unit = {},
) {
    val steering = pending && canSteer
    Surface(
        color = when {
            steering -> Amber
            pending -> Warn
            canSend -> Amber
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RectangleShape,
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(
            onClick = {
                when {
                    steering -> onSteer()
                    pending -> onStop()
                    canSend -> onSend()
                }
            },
            enabled = steering || pending || canSend,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = if (pending && !steering) {
                    Icons.Filled.Stop
                } else {
                    Icons.Filled.ArrowUpward
                },
                contentDescription = when {
                    steering -> "Steer"
                    pending -> "Stop"
                    else -> "Send"
                },
                tint = Color.Black,
            )
        }
    }
}

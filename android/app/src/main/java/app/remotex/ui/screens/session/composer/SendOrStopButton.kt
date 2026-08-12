package app.remotex.ui.screens.session.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Warn

/** Send while idle; steer/queue plus a permanently reachable stop while active. */
@Composable
internal fun SendOrStopButton(
    pending: Boolean,
    canSend: Boolean,
    canSteer: Boolean = false,
    canQueue: Boolean = false,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSteer: () -> Unit = {},
    onQueue: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row {
        if (!pending) {
            ActionButton(
                enabled = canSend,
                color = if (canSend) Amber else MaterialTheme.colorScheme.surfaceVariant,
                onClick = onSend,
                description = "Send",
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Color.Black)
            }
        } else {
            if (canSteer) {
                ActionButton(
                    enabled = true,
                    color = Amber,
                    onClick = onSteer,
                    description = "Steer current turn",
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Color.Black)
                }
                if (canQueue) {
                    Box {
                        ActionButton(
                            enabled = true,
                            color = Amber,
                            onClick = { menuOpen = true },
                            description = "Other send options",
                        ) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.Black)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    androidx.compose.foundation.layout.Column {
                                        Text(
                                            "Queue as next turn",
                                            color = Ink,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                        )
                                        Text(
                                            "runs when this turn finishes",
                                            color = InkDim,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                        )
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onQueue()
                                },
                            )
                        }
                    }
                }
            }
            ActionButton(
                enabled = true,
                color = Warn,
                onClick = onStop,
                description = "Stop",
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.Black)
            }
        }
    }
}

@Composable
private fun ActionButton(
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    description: String,
    size: Int = 40,
    content: @Composable () -> Unit,
) {
    Surface(
        color = color,
        shape = RectangleShape,
        modifier = Modifier
            .size(size.dp)
            .semantics { contentDescription = description },
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(size.dp),
        ) {
            content()
        }
    }
}

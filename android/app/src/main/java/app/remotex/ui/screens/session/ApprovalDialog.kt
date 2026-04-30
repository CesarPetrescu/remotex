package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.ApprovalPrompt
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

@Composable
fun ApprovalDialog(
    prompt: ApprovalPrompt,
    onDecision: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision("cancel") },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        title = {
            Text(
                if (prompt.kind == "command") "COMMAND APPROVAL" else "FILE CHANGE APPROVAL",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                prompt.reason?.let {
                    Text(
                        it,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                prompt.command?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Line),
                    ) {
                        Text(
                            it,
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                prompt.cwd?.let {
                    Text(
                        "cwd: $it",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if ("acceptForSession" in prompt.decisions) {
                    TextButton(onClick = { onDecision("acceptForSession") }) {
                        Text(
                            "always",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
                TextButton(onClick = { onDecision("accept") }) {
                    Text(
                        "accept",
                        color = Ok,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision("decline") }) {
                Text(
                    "decline",
                    color = Warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        },
    )
}

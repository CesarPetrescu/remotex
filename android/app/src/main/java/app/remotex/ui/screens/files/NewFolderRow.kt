package app.remotex.ui.screens.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

@Composable
internal fun NewFolderRow(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, AccentDeep),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "▤",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(AccentDeep),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (name.isEmpty()) {
                        Text(
                            "folder name",
                            color = InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                },
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentDeep,
                    contentColor = Color.Black,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
                shape = RectangleShape,
            ) {
                Text("Create", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

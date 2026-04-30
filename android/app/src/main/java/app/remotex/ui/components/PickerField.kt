package app.remotex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
fun PickerField(
    label: String,
    selected: String,
    options: List<String>,
    displayFor: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        modifier = modifier.clickable { expanded = true },
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                displayFor(selected),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            displayFor(opt),
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

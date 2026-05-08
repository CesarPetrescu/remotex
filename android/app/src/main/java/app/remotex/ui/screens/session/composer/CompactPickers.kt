package app.remotex.ui.screens.session.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.MODEL_OPTIONS
import app.remotex.ui.ModelOption
import app.remotex.ui.PermissionsMode
import app.remotex.ui.effortsFor
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Warn

@Composable
internal fun CompactModelPicker(
    selected: String,
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val list = if (options.isNotEmpty()) options else MODEL_OPTIONS
    val current = list.firstOrNull { it.id == selected } ?: list.first()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "◈",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                compactModelLabel(current),
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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
            list.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(opt.label, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Text(opt.hint, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    },
                    onClick = { onSelect(opt.id); expanded = false },
                )
            }
        }
    }
}

@Composable
internal fun CompactPermissionsPicker(
    selected: PermissionsMode,
    onSelect: (PermissionsMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, if (selected == PermissionsMode.Full) Warn else Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(permissionSymbol(selected), color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                compactPermissionLabel(selected),
                color = if (selected == PermissionsMode.Full) Warn else Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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
            PermissionsMode.entries.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                opt.label,
                                color = if (opt == PermissionsMode.Full) Warn else Ink,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                            Text(
                                opt.hint,
                                color = InkDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@Composable
internal fun CompactEffortPicker(
    model: String,
    selected: String,
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val efforts = effortsFor(model, options)
    val effectiveSelected = if (selected in efforts) selected else ""
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "↯",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                compactEffortLabel(effectiveSelected),
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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
            efforts.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (opt.isEmpty()) "default" else opt,
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

private fun compactModelLabel(option: ModelOption): String {
    if (option.id.isBlank()) return "auto"
    return option.label
        .removePrefix("gpt-")
        .replace(" · ", " ")
        .replace("codex spark", "spark")
        .replace("codex mini", "mini")
        .replace("codex max", "max")
        .replace("codex", "code")
}

private fun compactEffortLabel(effort: String): String =
    if (effort.isEmpty()) "auto" else effort

private fun compactPermissionLabel(mode: PermissionsMode): String =
    mode.label.lowercase()

private fun permissionSymbol(mode: PermissionsMode): String =
    if (mode == PermissionsMode.Full) "!" else "▣"

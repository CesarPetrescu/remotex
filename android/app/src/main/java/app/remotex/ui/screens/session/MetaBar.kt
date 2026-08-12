package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.PermissionsMode
import app.remotex.ui.UiState
import app.remotex.ui.screens.session.composer.CompactEffortPicker
import app.remotex.ui.screens.session.composer.CompactModelPicker
import app.remotex.ui.screens.session.composer.CompactPermissionsPicker
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun MetaBar(
    state: UiState,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
    onOpenFiles: () -> Unit,
    onUpload: () -> Unit,
) {
    val info = state.session
    val text = when {
        info == null -> "no session"
        else -> info.cwd ?: "/"
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val compact = maxWidth < 600.dp
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text,
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!compact) {
                        // Wide layouts group every session knob here, next to
                        // the cwd — the app bar deliberately shows none of
                        // them on the session screen.
                        CompactModelPicker(
                            selected = state.model,
                            options = state.modelOptions,
                            onSelect = onModelChange,
                            modifier = Modifier.widthIn(max = 170.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        CompactEffortPicker(
                            model = state.model,
                            selected = state.effort,
                            options = state.modelOptions,
                            onSelect = onEffortChange,
                        )
                        Spacer(Modifier.width(6.dp))
                        CompactPermissionsPicker(
                            selected = state.permissions,
                            onSelect = onPermissionsChange,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    MetaButton("▤", "Browse workspace files", InkDim, onOpenFiles)
                    Spacer(Modifier.width(6.dp))
                    MetaButton("+", "Upload workspace file", AccentDeep, onUpload)
                }
                if (compact) {
                    LazyRow(
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item {
                            CompactModelPicker(
                                selected = state.model,
                                options = state.modelOptions,
                                onSelect = onModelChange,
                            )
                        }
                        item {
                            CompactEffortPicker(
                                model = state.model,
                                selected = state.effort,
                                options = state.modelOptions,
                                onSelect = onEffortChange,
                            )
                        }
                        item {
                            CompactPermissionsPicker(
                                selected = state.permissions,
                                onSelect = onPermissionsChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaButton(
    label: String,
    accessibilityLabel: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = 36.dp, minHeight = 32.dp)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Text(
            label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

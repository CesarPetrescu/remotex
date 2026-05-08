package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.PermissionsMode
import app.remotex.ui.UiState
import app.remotex.ui.screens.session.composer.CompactPermissionsPicker
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun MetaBar(
    state: UiState,
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
            CompactPermissionsPicker(
                selected = state.permissions,
                onSelect = onPermissionsChange,
            )
            Spacer(Modifier.width(6.dp))
            MetaButton("▤", InkDim, onOpenFiles)
            Spacer(Modifier.width(6.dp))
            MetaButton("+", AccentDeep, onUpload)
        }
    }
}

@Composable
private fun MetaButton(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        onClick = onClick,
    ) {
        Text(
            label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

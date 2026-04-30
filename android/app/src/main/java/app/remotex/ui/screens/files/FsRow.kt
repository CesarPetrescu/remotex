package app.remotex.ui.screens.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.FsEntry
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun FsRow(entry: FsEntry, onOpenDir: () -> Unit) {
    val dir = entry.isDirectory
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = dir, onClick = onOpenDir),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (dir) "▸" else " ",
                color = if (dir) Amber else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.fileName,
                color = if (dir) Ink else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

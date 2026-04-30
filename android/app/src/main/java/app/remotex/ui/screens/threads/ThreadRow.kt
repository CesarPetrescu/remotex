package app.remotex.ui.screens.threads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.ThreadInfo
import app.remotex.ui.components.relativeAge
import app.remotex.ui.components.shortenCwd
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun ThreadRow(thread: ThreadInfo, onClick: () -> Unit) {
    val hasSpecificTitle = !thread.title.isNullOrBlank() && !thread.titleIsGeneric
    val title = if (hasSpecificTitle) thread.title!! else thread.preview.ifBlank { "(no preview)" }
    val description = if (hasSpecificTitle) (thread.description?.takeIf { it.isNotBlank() } ?: thread.preview.takeIf { it.isNotBlank() }) else null
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    relativeAge(thread.updatedAt ?: thread.createdAt ?: 0L),
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "· ${thread.id.take(8)}…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                thread.cwd?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· ${shortenCwd(it)}",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

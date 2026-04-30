package app.remotex.ui.screens.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.UiState
import app.remotex.ui.theme.InkDim

@Composable
internal fun MetaBar(state: UiState) {
    val info = state.session
    val text = when {
        info == null -> "no session"
        else -> buildString {
            append("session ${info.sessionId.take(12)}… on ${info.hostId.take(12)}…")
            info.model?.let { append(" · $it") }
            info.cwd?.let { append(" · $it") }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

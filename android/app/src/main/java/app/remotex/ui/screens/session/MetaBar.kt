package app.remotex.ui.screens.session

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.UiState
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
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
    val haveTokens = state.tokensInput > 0L || state.tokensOutput > 0L
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (haveTokens) {
                Spacer(Modifier.width(8.dp))
                TokenChip(state)
            }
        }
    }
}

@Composable
private fun TokenChip(state: UiState) {
    val totalIn = state.tokensInput + state.tokensCached
    val totalOut = state.tokensOutput + state.tokensReasoning
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "🪙",
            fontSize = 10.sp,
        )
        Text(
            "${formatK(totalIn)}↑",
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            "${formatK(totalOut)}↓",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

private fun formatK(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 100_000 -> String.format("%.1fK", n / 1000.0)
    n < 1_000_000 -> "${n / 1000}K"
    else -> String.format("%.1fM", n / 1_000_000.0)
}

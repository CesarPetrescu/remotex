package app.remotex.ui.screens.threads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.ThreadInfo
import app.remotex.ui.UiState
import app.remotex.ui.components.CompactStatusBar
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim

@Composable
fun ThreadsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onNewSession: () -> Unit,
    onResumeThread: (ThreadInfo) -> Unit,
) {
    val selectedHost = state.hosts.firstOrNull { it.id == state.selectedHostId }
    val telemetry = state.selectedHostId?.let { state.hostTelemetry[it]?.data }
    Column(Modifier.fillMaxSize()) {
        CompactStatusBar(host = selectedHost, data = telemetry)
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "sessions on ${selectedHost?.nickname ?: state.selectedHostId ?: "host"}".uppercase(),
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        // A1: primary action — amber-filled tile (was just amber-bordered),
        // collapses to a one-liner once the user has more than a handful
        // of saved threads so the list dominates instead of the button.
        val compact = state.threads.size > 5
        Surface(
            color = Amber,
            shape = RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewSession),
        ) {
            if (compact) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "New session on ${selectedHost?.nickname ?: "host"}",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "→",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    )
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "+",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "New session",
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                        )
                        Text(
                            "start a fresh codex thread",
                            color = Color.Black.copy(alpha = 0.65f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "or continue a previous one",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = InkDim)
            }
        }

        if (state.threadsLoading) {
            Text(
                "loading…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else if (state.threads.isEmpty()) {
            Text(
                "no previous sessions yet — your first “New session” will show up here after it runs.",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // SelectionContainer so users can copy thread titles / cwd
            // paths / previews. The clickable rows still respond to taps —
            // a long-press starts selection mode.
            SelectionContainer {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.threads, key = { it.id }) { t ->
                        ThreadRow(t, onClick = { onResumeThread(t) })
                    }
                }
            }
        }
        }
    }
}

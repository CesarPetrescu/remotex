package app.remotex.ui.screens.threads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.ThreadInfo
import app.remotex.ui.UiState
import app.remotex.ui.components.CompactStatusBar
import app.remotex.ui.useTwoPane
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.OnAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onNewSession: () -> Unit,
    onResumeThread: (ThreadInfo) -> Unit,
) {
    val selectedHost = state.hosts.firstOrNull { it.id == state.selectedHostId }
    val telemetry = state.selectedHostId?.let { state.hostTelemetry[it]?.data }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = useTwoPane(maxWidth, maxHeight)
        val actionWidth = (maxWidth * 0.4f).coerceIn(280.dp, 360.dp)
        if (twoPane) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .testTag("session-actions-pane"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CompactStatusBar(host = selectedHost, data = telemetry)
                    NewSessionCard(
                        hostName = selectedHost?.nickname ?: "host",
                        compact = false,
                        onClick = onNewSession,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                VerticalDivider(color = Line)
                PullToRefreshBox(
                    isRefreshing = state.threadsLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).testTag("saved-sessions-pane"),
                ) {
                    PreviousSessions(
                        state = state,
                        onRefresh = onRefresh,
                        onResumeThread = onResumeThread,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CompactStatusBar(host = selectedHost, data = telemetry)
                PullToRefreshBox(
                    isRefreshing = state.threadsLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NewSessionCard(
                            hostName = selectedHost?.nickname ?: "host",
                            compact = state.threads.size > 5,
                            onClick = onNewSession,
                        )
                        PreviousSessions(
                            state = state,
                            onRefresh = onRefresh,
                            onResumeThread = onResumeThread,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSessionCard(
    hostName: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Amber,
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        if (compact) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", color = OnAccent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "New session on $hostName",
                    color = OnAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("→", color = OnAccent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", color = OnAccent, fontFamily = FontFamily.Monospace, fontSize = 22.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("New session", color = OnAccent, fontSize = 16.sp)
                    Text(
                        "Start a fresh Codex thread on $hostName",
                        color = OnAccent.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviousSessions(
    state: UiState,
    onRefresh: () -> Unit,
    onResumeThread: (ThreadInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Previous sessions", fontSize = 17.sp)
                Text(
                    "Continue where you left off",
                    color = InkDim,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh sessions", tint = InkDim)
            }
        }

        when {
            state.threadsLoading -> Text(
                "Loading sessions…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
            state.threads.isEmpty() -> Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "No previous sessions yet. Start one and it will appear here.",
                    color = InkDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(18.dp),
                )
            }
            else -> SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.threads, key = { it.id }) { thread ->
                        ThreadRow(thread, onClick = { onResumeThread(thread) })
                    }
                }
            }
        }
    }
}

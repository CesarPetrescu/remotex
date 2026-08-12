package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.model.ThreadInfo
import app.remotex.ui.screens.telemetry.TelemetryPanel
import app.remotex.ui.screens.threads.ThreadRow
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

/**
 * Right-hand rail next to the session on wide layouts. Two tabs:
 * `history` (this host's threads — tap to switch the main chat, ⧉ to
 * open a second chat column) and `system` (the telemetry panel that
 * used to own this pane).
 */
@Composable
internal fun SessionSideRail(
    threads: List<ThreadInfo>,
    threadsLoading: Boolean,
    activeThreadId: String?,
    splitEnabled: Boolean,
    hostLabel: String,
    snapshot: HostTelemetrySnapshot?,
    onRefreshThreads: () -> Unit,
    onOpenThread: (ThreadInfo) -> Unit,
    onSplitThread: (ThreadInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf("history") }
    Column(modifier.testTag("session-side-rail")) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailTab("history", tab == "history") { tab = "history" }
            RailTab("system", tab == "system") { tab = "system" }
            if (tab == "history") {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    IconButton(onClick = onRefreshThreads) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh sessions",
                            tint = InkDim,
                        )
                    }
                }
            }
        }
        if (tab == "system") {
            TelemetryPanel(
                hostLabel = hostLabel,
                snapshot = snapshot,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            when {
                threadsLoading && threads.isEmpty() -> Text(
                    "Loading sessions…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
                threads.isEmpty() -> Text(
                    "No previous sessions on this host yet.",
                    color = InkDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(threads, key = { it.id }) { thread ->
                        val active = thread.id == activeThreadId
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .then(
                                        if (active) Modifier.border(1.dp, Amber)
                                        else Modifier,
                                    ),
                            ) {
                                ThreadRow(thread, onClick = { onOpenThread(thread) })
                            }
                            if (splitEnabled) {
                                Surface(
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, Line),
                                    shape = RectangleShape,
                                    onClick = { onSplitThread(thread) },
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                                        .semantics {
                                            contentDescription =
                                                "Open in split view"
                                        },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "⧉",
                                            color = Ink,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Amber else Line),
        shape = RectangleShape,
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 44.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(
                label,
                color = if (selected) Amber else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

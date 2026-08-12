package app.remotex.ui.screens.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.BuildConfig
import app.remotex.model.Host
import app.remotex.net.normalizeRelayBaseUrl
import app.remotex.ui.UiState
import app.remotex.ui.components.RelayUrlField
import app.remotex.ui.components.TokenField
import app.remotex.ui.useTwoPane
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.OnAccent
import app.remotex.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    state: UiState,
    onTokenChange: (String) -> Unit,
    relayUrl: String = "",
    onRelayUrlChange: (String) -> Unit = {},
    onRefresh: () -> Unit,
    onHostTap: (Host) -> Unit,
    @Suppress("UNUSED_PARAMETER") onModelChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onEffortChange: (String) -> Unit,
) {
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = useTwoPane(maxWidth, maxHeight)
            val connectionWidth = (maxWidth * 0.4f).coerceIn(280.dp, 360.dp)
            val showConnectionPane = twoPane && (state.hosts.isEmpty() || connectionExpanded)
            if (showConnectionPane) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .width(connectionWidth)
                            .testTag("connection-pane"),
                    ) {
                        item {
                            ConnectionSection(
                                state = state,
                                relayUrl = relayUrl,
                                onRelayUrlChange = onRelayUrlChange,
                                onTokenChange = onTokenChange,
                                onRefresh = onRefresh,
                                expanded = connectionExpanded,
                                onExpandedChange = { connectionExpanded = it },
                            )
                        }
                    }
                    VerticalDivider(color = Line)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hosts-pane"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        hostItems(state, onHostTap)
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                            .padding(12.dp)
                            .testTag("hosts-content"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            ConnectionSection(
                                state = state,
                                relayUrl = relayUrl,
                                onRelayUrlChange = onRelayUrlChange,
                                onTokenChange = onTokenChange,
                                onRefresh = onRefresh,
                                expanded = connectionExpanded,
                                onExpandedChange = { connectionExpanded = it },
                                modifier = Modifier.testTag("connection-pane"),
                            )
                        }
                        hostItems(state, onHostTap)
                    }
                }
            }
        }
    }
}

// Once hosts are loaded the credential form collapses to a one-line
// summary on every size class — a permanently expanded token form on a
// connected tablet reads as "not signed in yet" and wastes the pane.
@Composable
private fun ConnectionSection(
    state: UiState,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRefresh: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.hosts.isEmpty() || expanded) {
        Column(modifier) {
            ConnectionCard(
                state = state,
                relayUrl = relayUrl,
                onRelayUrlChange = onRelayUrlChange,
                onTokenChange = onTokenChange,
                onRefresh = onRefresh,
            )
            if (state.hosts.isNotEmpty()) {
                TextButton(
                    onClick = { onExpandedChange(false) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Hide connection settings")
                }
            }
        }
    } else {
        ConnectionSummary(
            relayUrl = relayUrl,
            onClick = { onExpandedChange(true) },
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectionSummary(
    relayUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "RELAY CONNECTED",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Text(
                    relayUrl,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Text(
                "settings →",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    state: UiState,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addressReady by rememberSaveable(relayUrl) {
        mutableStateOf(normalizeRelayBaseUrl(relayUrl, BuildConfig.DEBUG).isSuccess)
    }
    val canConnect = addressReady && state.userToken.isNotBlank() && !state.loading

    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Connect to your relay",
                color = Ink,
                fontSize = 22.sp,
            )
            Text(
                "Use the relay address and access token supplied by your administrator.",
                color = InkDim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            RelayUrlField(
                value = relayUrl,
                onCommit = onRelayUrlChange,
                onReadyChange = { addressReady = it },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            TokenField(
                value = state.userToken,
                onChange = onTokenChange,
                onSubmit = { if (canConnect) onRefresh() },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onRefresh,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = OnAccent,
                ),
            ) {
                Text(
                    when {
                        state.loading -> "Connecting…"
                        state.hosts.isEmpty() -> "Connect"
                        else -> "Refresh hosts"
                    },
                )
            }
            when {
                state.error != null -> Text(
                    state.error,
                    color = Warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                state.loading -> Text(
                    "Checking relay and loading hosts…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun LazyListScope.hostItems(state: UiState, onHostTap: (Host) -> Unit) {
    item {
        Text(
            "Available hosts (${state.hosts.size})",
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 4.dp).testTag("hosts-pane-heading"),
        )
    }
    if (state.hosts.isEmpty()) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Line),
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("No hosts available", color = Ink, fontSize = 15.sp)
                    Text(
                        "Connect to your relay, then make sure the Remotex daemon is online.",
                        color = InkDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    } else {
        items(state.hosts, key = { it.id }) { host ->
            HostRow(
                host = host,
                data = state.hostTelemetry[host.id]?.data,
                onClick = { onHostTap(host) },
            )
        }
    }
}

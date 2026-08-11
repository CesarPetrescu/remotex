package app.remotex.ui.screens.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = useTwoPane(maxWidth, maxHeight)
            val connectionWidth = (maxWidth * 0.4f).coerceIn(280.dp, 360.dp)
            if (twoPane) {
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
                            ConnectionCard(
                                state = state,
                                relayUrl = relayUrl,
                                onRelayUrlChange = onRelayUrlChange,
                                onTokenChange = onTokenChange,
                                onRefresh = onRefresh,
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        ConnectionCard(
                            state = state,
                            relayUrl = relayUrl,
                            onRelayUrlChange = onRelayUrlChange,
                            onTokenChange = onTokenChange,
                            onRefresh = onRefresh,
                            modifier = Modifier.testTag("connection-pane"),
                        )
                    }
                    hostItems(state, onHostTap)
                }
            }
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

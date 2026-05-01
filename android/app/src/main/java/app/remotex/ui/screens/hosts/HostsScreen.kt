package app.remotex.ui.screens.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.Host
import app.remotex.ui.UiState
import app.remotex.ui.components.SectionLabel
import app.remotex.ui.components.TokenField
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    state: UiState,
    onTokenChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onHostTap: (Host) -> Unit,
    @Suppress("UNUSED_PARAMETER") onModelChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onEffortChange: (String) -> Unit,
) {
    // A3: pull-to-refresh — wraps the LazyColumn so dragging from the
    // top fires the same onRefresh as the explicit Load hosts button.
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { TokenField(state.userToken, onTokenChange) }
        item {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Ink,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Ink)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.loading) "Loading…" else "Load hosts")
                }
            }
        }
        item { SectionLabel("Hosts (${state.hosts.size})") }
        if (state.hosts.isEmpty()) {
            item {
                Text(
                    if (state.loading) "loading…" else "no hosts yet — tap Load hosts",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(state.hosts, key = { it.id }) { h ->
                HostRow(
                    host = h,
                    data = state.hostTelemetry[h.id]?.data,
                    onClick = { onHostTap(h) },
                )
            }
        }
    }
    }
}

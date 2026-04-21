package app.remotex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remotex.model.Host
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotexApp(relayUrl: String) {
    val vm: RemotexViewModel = viewModel(
        factory = RemotexViewModel.factory(relayUrl)
    )
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "REMOTEX",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(12.dp))
                        StatusBadge(state.status)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TokenField(state.userToken) { vm.setToken(it) }
            Button(
                onClick = vm::refresh,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "Loading…" else "Load hosts", color = Ink)
            }
            Text("Hosts", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            HostsList(
                hosts = state.hosts,
                selected = state.selectedHostId,
                onSelect = vm::selectHost,
                modifier = Modifier.weight(1f, fill = true),
            )
            Button(
                onClick = vm::openSession,
                enabled = state.canOpen,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Color.Black,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
            ) {
                Text("Open session")
            }
            state.lastSessionId?.let { sid ->
                Text(
                    "session $sid",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            state.error?.let { err ->
                Text(err, color = Warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (state.eventsLog.isNotEmpty()) {
                EventLog(state.eventsLog)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = when (status) {
        "connected" -> Ok
        "connecting", "opening" -> Amber
        "idle" -> InkDim
        else -> Warn
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(status, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun TokenField(value: String, onChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "USER TOKEN",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Amber),
            )
        }
    }
}

@Composable
private fun HostsList(
    hosts: List<Host>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hosts.isEmpty()) {
        Text(
            "no hosts yet — pull to load",
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(hosts, key = { it.id }) { h ->
            HostRow(h, selected == h.id) { onSelect(h.id) }
        }
    }
}

@Composable
private fun HostRow(host: Host, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Amber else Line
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (host.online) Ok else InkDim, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    host.nickname,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${host.hostname ?: "—"} · ${if (host.online) "online" else "offline"}",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventLog(lines: List<String>) {
    val scroll = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        Column(
            Modifier
                .padding(10.dp)
                .verticalScroll(scroll),
        ) {
            lines.takeLast(60).forEach {
                Text(
                    it,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

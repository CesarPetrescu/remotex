package app.remotex.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remotex.model.Host
import app.remotex.model.UiEvent
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotexApp(relayUrl: String) {
    val vm: RemotexViewModel = viewModel(factory = RemotexViewModel.factory(relayUrl))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { RemotexBar(state, onBack = { vm.goToHosts() }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.Hosts -> HostsScreen(
                    state = state,
                    onTokenChange = vm::setToken,
                    onRefresh = vm::refresh,
                    onHostTap = vm::openHost,
                )
                Screen.Session -> SessionScreen(
                    state = state,
                    onSend = vm::sendTurn,
                )
            }
            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, Warn),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(
                        err,
                        color = Warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotexBar(state: UiState, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "REMOTEX",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(10.dp))
                StatusBadge(state.status)
            }
        },
        navigationIcon = {
            if (state.screen == Screen.Session) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Hosts", tint = Ink)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun StatusBadge(status: Status) {
    val (color, text) = when (status) {
        Status.Connected -> Ok to "connected"
        Status.Connecting, Status.Opening -> Amber to "connecting"
        Status.Idle -> InkDim to "idle"
        Status.Disconnected -> Warn to "disconnected"
        Status.Error -> Warn to "error"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

// -------------------- Hosts screen --------------------

@Composable
private fun HostsScreen(
    state: UiState,
    onTokenChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onHostTap: (Host) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TokenField(state.userToken, onTokenChange)
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
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
        SectionLabel("Hosts")
        if (state.hosts.isEmpty()) {
            Text(
                if (state.loading) "loading…" else "no hosts yet — tap Load hosts",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.hosts, key = { it.id }) { h ->
                    HostRow(h) { onHostTap(h) }
                }
            }
        }
    }
}

@Composable
private fun TokenField(value: String, onChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
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
                cursorBrush = SolidColor(Amber),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HostRow(host: Host, onClick: () -> Unit) {
    val border = if (host.online) Amber else Line
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = host.online, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (host.online) Ok else InkDim, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    host.nickname,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${host.hostname ?: "—"} · ${if (host.online) "online" else "offline"}",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.online) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "tap to open session →",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// -------------------- Session screen --------------------

@Composable
private fun SessionScreen(state: UiState, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MetaBar(state)
        EventList(
            events = state.events,
            pending = state.pending,
            connected = state.status == Status.Connected,
            modifier = Modifier.weight(1f, fill = true),
        )
        Composer(
            enabled = state.status == Status.Connected && !state.pending,
            pending = state.pending,
            onSend = onSend,
        )
    }
}

@Composable
private fun MetaBar(state: UiState) {
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

@Composable
private fun EventList(
    events: List<UiEvent>,
    pending: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (connected) "send a prompt to start…" else "connecting…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        items(events, key = { it.id }) { EventRow(it, pending) }
    }
}

@Composable
private fun EventRow(event: UiEvent, pending: Boolean) {
    val accent = when (event) {
        is UiEvent.User -> InkDim
        is UiEvent.Reasoning -> Color(0xFF555555)
        is UiEvent.Tool -> Color(0xFF5F8FB0)
        is UiEvent.Agent -> Amber
        is UiEvent.System -> Line
    }
    Row {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxWidthZero()
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            val label = when (event) {
                is UiEvent.User -> "USER"
                is UiEvent.Reasoning -> "REASONING"
                is UiEvent.Tool -> "TOOL · ${event.tool}"
                is UiEvent.Agent -> "AGENT"
                is UiEvent.System -> event.label.uppercase()
            }
            Text(
                label,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(3.dp))
            when (event) {
                is UiEvent.User -> BodyText(event.text)
                is UiEvent.Reasoning -> BodyText(event.text.ifEmpty { "…" }, dim = true, italic = true)
                is UiEvent.Tool -> {
                    if (event.command.isNotEmpty()) CodeBlock(event.command)
                    if (event.output.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        CodeBlock(event.output, dim = true)
                    }
                }
                is UiEvent.Agent -> AgentText(event.text, streaming = pending && !event.completed)
                is UiEvent.System -> BodyText(event.detail.ifEmpty { event.label })
            }
        }
    }
}

@Composable
private fun BodyText(text: String, dim: Boolean = false, italic: Boolean = false) {
    Text(
        text,
        color = if (dim) InkDim else Ink,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic
                    else androidx.compose.ui.text.font.FontStyle.Normal,
    )
}

@Composable
private fun AgentText(text: String, streaming: Boolean) {
    Text(
        buildString {
            append(text)
            if (streaming) append('▍')
        },
        color = Ink,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

@Composable
private fun CodeBlock(text: String, dim: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(3.dp),
    ) {
        Text(
            text,
            color = if (dim) InkDim else Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private fun Modifier.fillMaxWidthZero(): Modifier = this.height(24.dp) // visual accent bar

// -------------------- Composer --------------------

@Composable
private fun Composer(enabled: Boolean, pending: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (enabled) text = it },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = if (enabled) Ink else InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (enabled && text.isNotBlank()) {
                            onSend(text); text = ""
                        }
                    }),
                    cursorBrush = SolidColor(Amber),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                            if (text.isEmpty()) {
                                Text(
                                    if (pending) "codex is thinking…" else "ask codex…",
                                    color = InkDim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = enabled && text.isNotBlank(),
                onClick = {
                    onSend(text); text = ""
                },
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) Amber else InkDim,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = InkDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
}

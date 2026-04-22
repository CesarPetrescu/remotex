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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import app.remotex.model.Host
import app.remotex.model.ThreadInfo
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
    val context = LocalContext.current
    val vm: RemotexViewModel = viewModel(
        factory = RemotexViewModel.factory(context.applicationContext as android.app.Application, relayUrl)
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            RemotexBar(
                state = state,
                onBack = when (state.screen) {
                    Screen.Threads -> ({ vm.goToHosts() })
                    Screen.Session -> ({ vm.goToThreads() })
                    Screen.Hosts -> ({})
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.Hosts -> HostsScreen(
                    state = state,
                    onTokenChange = vm::setToken,
                    onRefresh = vm::refresh,
                    onHostTap = vm::openHost,
                    onModelChange = vm::setModel,
                    onEffortChange = vm::setEffort,
                )
                Screen.Threads -> ThreadsScreen(
                    state = state,
                    onRefresh = vm::refreshThreads,
                    onNewSession = { vm.openSession(null) },
                    onResumeThread = { vm.openSession(it.id) },
                )
                Screen.Session -> SessionScreen(
                    state = state,
                    onSend = vm::sendTurn,
                    onStop = vm::interruptTurn,
                    onModelChange = vm::setModel,
                    onEffortChange = vm::setEffort,
                    onAttachImage = vm::attachImage,
                    onRemoveImage = vm::removeImage,
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
            if (state.screen != Screen.Hosts) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Ink,
                    )
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
    @Suppress("UNUSED_PARAMETER") onModelChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onEffortChange: (String) -> Unit,
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
private fun ModelPickerField(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = MODEL_OPTIONS.firstOrNull { it.id == selected } ?: MODEL_OPTIONS.first()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.clickable { expanded = true },
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("MODEL", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                current.label,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                current.hint,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MODEL_OPTIONS.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                opt.label,
                                color = Ink,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                            Text(
                                opt.hint,
                                color = InkDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    },
                    onClick = { onSelect(opt.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    selected: String,
    options: List<String>,
    displayFor: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.clickable { expanded = true },
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                displayFor(selected),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            displayFor(opt),
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = { onSelect(opt); expanded = false },
                )
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

// -------------------- Threads screen --------------------

@Composable
private fun ThreadsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onNewSession: () -> Unit,
    onResumeThread: (ThreadInfo) -> Unit,
) {
    val hostNickname = state.hosts.firstOrNull { it.id == state.selectedHostId }?.nickname
        ?: state.selectedHostId ?: "host"
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "sessions on $hostNickname".uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        // Primary action — start a fresh codex thread.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, Amber),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewSession),
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).background(Amber, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "New session",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    Text(
                        "start a fresh codex thread",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
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

@Composable
private fun ThreadRow(thread: ThreadInfo, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                thread.preview.ifBlank { "(no preview)" },
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    relativeAge(thread.updatedAt ?: thread.createdAt ?: 0L),
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "· ${thread.id.take(8)}…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                thread.cwd?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· ${shortenCwd(it)}",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun relativeAge(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "—"
    val diff = (System.currentTimeMillis() / 1000L) - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}

private fun shortenCwd(cwd: String): String {
    val home = System.getProperty("user.home")
    val trimmed = if (home != null && cwd.startsWith(home)) "~" + cwd.substring(home.length) else cwd
    return if (trimmed.length > 30) "…" + trimmed.takeLast(27) else trimmed
}

// -------------------- Session screen --------------------

@Composable
private fun SessionScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MetaBar(state)
        EventList(
            events = state.events,
            pending = state.pending,
            connected = state.status == Status.Connected,
            modifier = Modifier.weight(1f, fill = true),
        )
        ComposerBar(
            connected = state.status == Status.Connected,
            pending = state.pending,
            model = state.model,
            effort = state.effort,
            pendingImages = state.pendingImages,
            onModelChange = onModelChange,
            onEffortChange = onEffortChange,
            onSend = onSend,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
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
    if (event is UiEvent.User && event.imageUris.isNotEmpty()) {
        Row(Modifier.padding(bottom = 4.dp)) {
            Box(Modifier.width(2.dp).height(24.dp).background(accent))
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(event.imageUris) { uri ->
                    AsyncImage(
                        model = uri.toUri(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
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
                is UiEvent.Reasoning -> MarkdownText(
                    text = event.text.ifEmpty { "…" },
                    color = InkDim,
                )
                is UiEvent.Tool -> {
                    if (event.command.isNotEmpty()) CodeBlock(event.command)
                    if (event.output.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        CodeBlock(event.output, dim = true)
                    }
                }
                is UiEvent.Agent -> MarkdownText(
                    text = event.text,
                    color = Ink,
                    trailingCursor = pending && !event.completed,
                )
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
private fun ComposerBar(
    connected: Boolean,
    pending: Boolean,
    model: String,
    effort: String,
    pendingImages: List<PendingImage>,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val textEnabled = connected && !pending
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAttachImage(uri)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 0 — attached image thumbnails (only when we have some)
            if (pendingImages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pendingImages.size) { idx ->
                        Box(Modifier.size(56.dp)) {
                            AsyncImage(
                                model = pendingImages[idx].uri.toUri(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .clickable { onRemoveImage(idx) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    tint = Ink,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
            // Row 1 — model + effort as compact chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactModelPicker(
                    selected = model,
                    onSelect = onModelChange,
                    modifier = Modifier.weight(1f),
                )
                CompactEffortPicker(
                    model = model,
                    selected = effort,
                    onSelect = onEffortChange,
                    modifier = Modifier.weight(1f),
                )
            }
            // Row 2 — attach + prompt + send/stop
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = textEnabled,
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Attach image",
                        tint = if (textEnabled) Ink else InkDim,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Line),
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (textEnabled) text = it },
                        enabled = textEnabled,
                        textStyle = TextStyle(
                            color = if (textEnabled) Ink else InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        ),
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textEnabled && text.isNotBlank()) {
                                onSend(text); text = ""
                            }
                        }),
                        cursorBrush = SolidColor(Amber),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                if (text.isEmpty() && !pending) {
                                    Text(
                                        "ask codex",
                                        color = InkDim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                SendOrStopButton(
                    pending = pending,
                    canSend = textEnabled && (text.isNotBlank() || pendingImages.isNotEmpty()),
                    onSend = { onSend(text); text = "" },
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun SendOrStopButton(
    pending: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // A 44dp circular button that flips icon based on pending.
    Surface(
        color = when {
            pending -> Warn
            canSend -> Amber
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape,
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(
            onClick = { if (pending) onStop() else if (canSend) onSend() },
            enabled = pending || canSend,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = if (pending) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                contentDescription = if (pending) "Stop" else "Send",
                tint = Color.Black,
            )
        }
    }
}

@Composable
private fun CompactModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = MODEL_OPTIONS.firstOrNull { it.id == selected } ?: MODEL_OPTIONS.first()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "model",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                current.label,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MODEL_OPTIONS.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(opt.label, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Text(opt.hint, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    },
                    onClick = { onSelect(opt.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CompactEffortPicker(
    model: String,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = effortsFor(model)
    val effectiveSelected = if (selected in options) selected else ""
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "effort",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (effectiveSelected.isEmpty()) "default" else effectiveSelected,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (opt.isEmpty()) "default" else opt,
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = { onSelect(opt); expanded = false },
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

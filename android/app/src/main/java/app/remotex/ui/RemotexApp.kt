package app.remotex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import app.remotex.model.FsEntry
import app.remotex.model.Host
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.model.SearchResult
import app.remotex.model.ThreadInfo
import app.remotex.model.UiEvent
import app.remotex.ui.theme.AccentDeep
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

    // System back: walk the same path as the top-bar arrow. Only the
    // Hosts screen falls through to the default "exit the app" behavior.
    BackHandler(enabled = state.screen == Screen.Session) {
        vm.goToThreads()
    }
    BackHandler(enabled = state.screen == Screen.Files) {
        vm.goToThreads()
    }
    BackHandler(enabled = state.screen == Screen.Threads) {
        vm.goToHosts()
    }
    BackHandler(enabled = state.screen == Screen.Search) {
        vm.goToHosts()
    }

    // Kick a reconnect when we come back to the foreground after a pause.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (state.screen == Screen.Session && state.status != Status.Connected
                    && state.session != null) {
                    vm.reconnectNow()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        WireMeshBackdrop(Modifier.fillMaxSize())
        Scaffold(
            topBar = {
                RemotexBar(
                    state = state,
                    onBack = when (state.screen) {
                        Screen.Threads -> ({ vm.goToHosts() })
                        Screen.Files -> ({ vm.goToThreads() })
                        Screen.Session -> ({ vm.goToThreads() })
                        Screen.Search -> ({ vm.goToHosts() })
                        Screen.Hosts -> ({})
                    },
                    onSearch = { vm.goToSearch() },
                )
            },
            containerColor = Color.Transparent,
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
                        onNewSession = { vm.goToFiles() },
                        onResumeThread = { vm.openSession(it.id) },
                    )
                    Screen.Files -> FilesScreen(
                        state = state,
                        onNavigate = vm::browseDir,
                        onUp = vm::browseUp,
                        onStartHere = vm::startSessionInCurrentPath,
                        onCreateFolder = vm::createFolder,
                    )
                    Screen.Search -> SearchScreen(
                        state = state,
                        onQueryChange = vm::setSearchQuery,
                        onSearch = vm::searchChats,
                        onOpenResult = vm::openSearchResult,
                    )
                    Screen.Session -> SessionScreen(
                        state = state,
                        onSend = vm::sendTurn,
                        onStop = vm::interruptTurn,
                        onModelChange = vm::setModel,
                        onEffortChange = vm::setEffort,
                        onAttachImage = vm::attachImage,
                        onRemoveImage = vm::removeImage,
                        onPermissionsChange = vm::setPermissions,
                    )
                }
                state.pendingApproval?.let { appr ->
                    ApprovalDialog(
                        prompt = appr,
                        onDecision = { vm.resolveApproval(it) },
                    )
                }
                state.slashFeedback?.let { msg ->
                    LaunchedEffect(msg) {
                        kotlinx.coroutines.delay(3500)
                        vm.dismissSlashFeedback()
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Line),
                        shape = RectangleShape,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 140.dp, start = 12.dp, end = 12.dp),
                    ) {
                        Text(
                            msg,
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                state.error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Warn),
                        shape = RectangleShape,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotexBar(state: UiState, onBack: () -> Unit, onSearch: () -> Unit) {
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
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Ink)
            }
        },
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
        Box(Modifier.size(8.dp).background(color))
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
    val selectedHost = state.hosts.firstOrNull { it.id == state.selectedHostId }
    val telemetry = state.selectedHostId?.let { state.hostTelemetry[it] }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        item { TelemetryPanel(telemetry, selectedHost) }
        item { SectionLabel("Hosts") }
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
                HostRow(h) { onHostTap(h) }
            }
        }
    }
}

@Composable
private fun TelemetryPanel(snapshot: HostTelemetrySnapshot?, selectedHost: Host?) {
    val d = snapshot?.data
    val live = snapshot != null &&
        (System.currentTimeMillis() - snapshot.lastUpdateMs) < 10_000
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SYSTEM TELEMETRY",
                            color = InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                        Text(
                            selectedHost?.nickname ?: "no host selected",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TelemetryPill(
                            label = if (live) "LIVE" else if (selectedHost?.online == true) "STALE" else "OFFLINE",
                            value = "3s",
                            accent = if (live) Ok else InkDim,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TelemetryCard(
                        label = "CPU",
                        main = if (d?.cpu?.percent != null) "${formatPct(d.cpu.percent)}%" else "—",
                        sub = d?.cpu?.cores?.let { "$it cores" } ?: "",
                        note = d?.cpu?.tempC?.let { "${it.toInt()}°C package" } ?: "processor load",
                        points = snapshot?.history?.cpu ?: emptyList(),
                        max = 100f,
                        color = Amber,
                        modifier = Modifier.weight(1f),
                        stats = summarizeSeries(snapshot?.history?.cpu ?: emptyList(), ::formatPctLabel),
                    )
                    TelemetryCard(
                        label = "RAM",
                        main = if (d?.memory?.percent != null) "${formatPct(d.memory.percent)}%" else "—",
                        sub = d?.memory?.let { fmtMemoryShort(it.usedBytes, it.totalBytes) } ?: "",
                        note = if (d?.memory != null) "resident working set" else "memory pressure",
                        points = snapshot?.history?.mem ?: emptyList(),
                        max = 100f,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier.weight(1f),
                        stats = summarizeSeries(snapshot?.history?.mem ?: emptyList(), ::formatPctLabel),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TelemetryCard(
                        label = "GPU",
                        main = when {
                            d?.gpu?.percent != null -> "${formatPct(d.gpu.percent)}%"
                            d?.gpu != null -> "—"
                            else -> "n/a"
                        },
                        sub = d?.gpu?.name?.take(24) ?: (if (d?.gpu != null) "" else "no GPU"),
                        note = when {
                            d?.gpu?.memTotalMb != null ->
                                "${fmtMegabytes(d.gpu.memUsedMb)} / ${fmtMegabytes(d.gpu.memTotalMb)} VRAM"
                            d?.gpu != null -> "accelerator state"
                            else -> "accelerator unavailable"
                        },
                        points = snapshot?.history?.gpu ?: emptyList(),
                        max = 100f,
                        color = Ok,
                        modifier = Modifier.weight(1f),
                        stats = summarizeSeries(snapshot?.history?.gpu ?: emptyList(), ::formatPctLabel),
                    )
                    TelemetryCard(
                        label = "NETWORK",
                        main = "",
                        mainRow = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TelemetryInlineLegend("UP", fmtBps(d?.network?.upBps), Color(0xFFF472B6))
                                TelemetryInlineLegend("DOWN", fmtBps(d?.network?.downBps), Color(0xFFA78BFA))
                            }
                        },
                        sub = "",
                        note = "3 second rolling transfer window",
                        points = snapshot?.history?.down ?: emptyList(),
                        secondaryPoints = snapshot?.history?.up ?: emptyList(),
                        max = null,
                        color = Color(0xFFA78BFA),
                        modifier = Modifier.weight(1f),
                        stats = summarizeNetwork(
                            snapshot?.history?.up ?: emptyList(),
                            snapshot?.history?.down ?: emptyList(),
                        ),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TelemetryStat("Uptime", fmtUptime(d?.uptimeS))
                    TelemetryStat(
                        "Load",
                        d?.loadAvg?.joinToString(" ") { String.format("%.2f", it) } ?: "—",
                    )
                    TelemetryStat(
                        "Temp",
                        d?.cpu?.tempC?.let { "${it.toInt()}°C" } ?: "—",
                    )
                }
            }
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    main: String,
    sub: String,
    note: String,
    points: List<Float>,
    max: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    secondaryPoints: List<Float> = emptyList(),
    mainRow: (@Composable () -> Unit)? = null,
    stats: List<Pair<String, String>> = emptyList(),
) {
    Surface(
        color = Color(0xFF121A2C).copy(alpha = 0.92f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(color)
                    )
                }
                if (mainRow != null) {
                    mainRow()
                } else {
                    Text(
                        main,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                    )
                }
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    note,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TelemetryTrendChart(
                    primary = points,
                    secondary = secondaryPoints,
                    primaryColor = color,
                    secondaryColor = Color(0xFFF472B6),
                    max = max,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
                if (stats.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        stats.take(3).forEach { (statLabel, statValue) ->
                            TelemetryMiniStat(statLabel, statValue, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
    }
}

@Composable
private fun TelemetryTrendChart(
    primary: List<Float>,
    secondary: List<Float> = emptyList(),
    primaryColor: Color,
    secondaryColor: Color,
    max: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padX = 6.dp.toPx()
        val padY = 5.dp.toPx()
        val plotW = w - padX * 2
        val plotH = h - padY * 2
        val safePrimary = if (primary.isEmpty()) listOf(0f, 0f) else primary
        val safeSecondary = if (secondary.isEmpty()) emptyList() else secondary
        val cap = max ?: listOf(
            safePrimary.maxOrNull() ?: 0f,
            safeSecondary.maxOrNull() ?: 0f,
            1f,
        ).max()

        repeat(3) { index ->
            val y = padY + plotH * (index / 2f)
            drawLine(
                color = InkDim.copy(alpha = 0.14f),
                start = Offset(padX, y),
                end = Offset(w - padX, y),
                strokeWidth = 1f,
            )
        }
        repeat(4) { index ->
            val x = padX + plotW * (index / 3f)
            drawLine(
                color = InkDim.copy(alpha = 0.1f),
                start = Offset(x, padY),
                end = Offset(x, h - padY),
                strokeWidth = 1f,
            )
        }

        fun path(samples: List<Float>): Pair<Path, Offset>? {
            if (samples.size < 2) return null
            val step = plotW / (samples.size - 1).toFloat()
            val p = Path()
            var last = Offset(padX, h - padY)
            samples.forEachIndexed { i, v ->
                val x = padX + i * step
                val clamped = v.coerceIn(0f, cap)
                val y = h - padY - (clamped / cap) * plotH
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                last = Offset(x, y)
            }
            return p to last
        }

        fun areaPath(samples: List<Float>): Path? {
            val result = path(samples) ?: return null
            val step = plotW / (samples.size - 1).toFloat()
            val p = Path()
            p.moveTo(padX, h - padY)
            samples.forEachIndexed { i, v ->
                val x = padX + i * step
                val clamped = v.coerceIn(0f, cap)
                val y = h - padY - (clamped / cap) * plotH
                p.lineTo(x, y)
            }
            p.lineTo(padX + plotW, h - padY)
            p.close()
            return p
        }

        areaPath(safeSecondary)?.let {
            drawPath(
                path = it,
                brush = Brush.verticalGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.18f), secondaryColor.copy(alpha = 0.02f)),
                ),
                style = Fill,
            )
        }
        areaPath(safePrimary)?.let {
            drawPath(
                path = it,
                brush = Brush.verticalGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.28f), primaryColor.copy(alpha = 0.03f)),
                ),
                style = Fill,
            )
        }
        path(safeSecondary)?.let { (p, last) ->
            drawPath(p, secondaryColor, style = Stroke(width = 2f))
            drawCircle(secondaryColor, radius = 3f, center = last)
        }
        path(safePrimary)?.let { (p, last) ->
            drawPath(p, primaryColor, style = Stroke(width = 2.2f))
            drawCircle(primaryColor, radius = 3.4f, center = last)
        }
        drawRect(
            color = InkDim.copy(alpha = 0.14f),
            topLeft = Offset(padX, padY),
            size = androidx.compose.ui.geometry.Size(plotW, plotH),
            style = Stroke(width = 1f),
        )
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Text(
            value,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TelemetryPill(label: String, value: String, accent: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(label, color = accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(value, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

@Composable
private fun TelemetryInlineLegend(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            "$label $value",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun TelemetryMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0x0FFFFFFF),
        border = BorderStroke(1.dp, InkDim.copy(alpha = 0.12f)),
        shape = RectangleShape,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(label, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 7.sp)
            Text(
                value,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WireMeshBackdrop(
    modifier: Modifier = Modifier,
    lineAlpha: Float = 0.22f,
    diagAlpha: Float = 0.16f,
) {
    Canvas(modifier = modifier) {
        val grid = 40.dp.toPx()
        val diagStep = grid * 3f
        val baseLine = Amber.copy(alpha = lineAlpha)
        val diagLine = Ok.copy(alpha = diagAlpha)

        var y = 0f
        while (y <= size.height) {
            drawLine(baseLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += grid
        }
        var x = 0f
        while (x <= size.width) {
            drawLine(baseLine.copy(alpha = lineAlpha * 0.78f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += grid
        }
        var offset = -size.height
        while (offset <= size.width) {
            drawLine(
                diagLine,
                Offset(offset, size.height),
                Offset(offset + size.height, 0f),
                strokeWidth = 1f,
            )
            offset += diagStep
        }
    }
}

private fun formatPct(p: Double): String =
    if (p >= 10.0) p.toInt().toString() else String.format("%.1f", p)

private fun fmtMemoryShort(used: Long?, total: Long?): String {
    if (used == null || total == null) return ""
    val gbU = used / 1024.0 / 1024.0 / 1024.0
    val gbT = total / 1024.0 / 1024.0 / 1024.0
    return String.format("%.1f / %.1f GB", gbU, gbT)
}

private fun fmtBps(bps: Long?): String {
    val v = bps ?: 0L
    return when {
        v >= 1_000_000_000 -> String.format("%.1f Gbps", v / 1_000_000_000.0)
        v >= 1_000_000 -> String.format("%.1f Mbps", v / 1_000_000.0)
        v >= 1_000 -> String.format("%.1f kbps", v / 1_000.0)
        else -> "$v bps"
    }
}

private fun fmtMegabytes(mb: Double?): String {
    if (mb == null) return "—"
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0) else "${mb.toInt()} MB"
}

private fun fmtUptime(s: Long?): String {
    if (s == null || s <= 0) return "—"
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

private fun formatPctLabel(value: Float): String =
    if (value >= 10f) "${value.toInt()}%" else "${String.format("%.1f", value)}%"

private fun summarizeSeries(points: List<Float>, formatter: (Float) -> String): List<Pair<String, String>> {
    if (points.isEmpty()) return emptyList()
    return listOf(
        "NOW" to formatter(points.last()),
        "PEAK" to formatter(points.maxOrNull() ?: 0f),
        "FLOOR" to formatter(points.minOrNull() ?: 0f),
    )
}

private fun summarizeNetwork(up: List<Float>, down: List<Float>): List<Pair<String, String>> {
    return listOf(
        "UP PEAK" to fmtBps((up.maxOrNull() ?: 0f).toLong()),
        "DOWN PEAK" to fmtBps((down.maxOrNull() ?: 0f).toLong()),
        "LIVE SUM" to fmtBps(((up.lastOrNull() ?: 0f) + (down.lastOrNull() ?: 0f)).toLong()),
    )
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
        shape = RectangleShape,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
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
        shape = RectangleShape,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
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
        shape = RectangleShape,
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
        shape = RectangleShape,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = host.online, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (host.online) Ok else InkDim))
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
            shape = RectangleShape,
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
                    Modifier.size(28.dp).background(Amber),
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
    val hasSpecificTitle = !thread.title.isNullOrBlank() && !thread.titleIsGeneric
    val title = if (hasSpecificTitle) thread.title!! else thread.preview.ifBlank { "(no preview)" }
    val description = if (hasSpecificTitle) (thread.description?.takeIf { it.isNotBlank() } ?: thread.preview.takeIf { it.isNotBlank() }) else null
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

// -------------------- Search screen --------------------

@Composable
private fun SearchScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenResult: (SearchResult) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "semantic chat search".uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            border = BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                singleLine = true,
                modifier = Modifier.padding(10.dp).fillMaxWidth(),
            )
        }
        Button(
            onClick = onSearch,
            enabled = !state.searchLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                contentColor = Color.Black,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledContentColor = InkDim,
            ),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.searchLoading) "Searching" else "Search chats",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        when {
            state.searchLoading && state.searchResults.isEmpty() -> Text(
                "searching...",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
            state.searchResults.isEmpty() -> Text(
                "enter a query to find previous answers and reasoning",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.searchResults, key = { it.id }) { result ->
                    SearchResultRow(result, onClick = { onOpenResult(result) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                result.snippet.ifBlank { result.text },
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    result.role,
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(result.score * 100.0).toInt()}%",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeAge(result.createdAt ?: 0L),
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                result.cwd?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        shortenCwd(it),
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

// -------------------- Files screen --------------------

@Composable
private fun FilesScreen(
    state: UiState,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onStartHere: () -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    val path = state.browsePath.ifEmpty { "/" }
    var newFolderOpen by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "pick a folder for the new session".uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )

        // Breadcrumbs — tap any segment to jump directly to that path.
        Breadcrumbs(path = path, onJump = onNavigate)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onUp,
                enabled = path != "/",
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Ink,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
                shape = RectangleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text("↑ up", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    newFolderOpen = true
                    newFolderName = ""
                },
                enabled = !state.browseLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = AccentDeep,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
                shape = RectangleShape,
                modifier = Modifier.weight(1.2f),
            ) {
                Text("＋ new folder", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Button(
                onClick = onStartHere,
                enabled = !state.browseLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Color.Black,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
                shape = RectangleShape,
                modifier = Modifier.weight(1.6f),
            ) {
                Text("use this folder", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        if (newFolderOpen) {
            NewFolderRow(
                name = newFolderName,
                onNameChange = { newFolderName = it },
                onConfirm = {
                    val n = newFolderName.trim()
                    if (n.isNotEmpty() && "/" !in n && n != "." && n != "..") {
                        onCreateFolder(n)
                        newFolderOpen = false
                        newFolderName = ""
                    }
                },
                onCancel = {
                    newFolderOpen = false
                    newFolderName = ""
                },
            )
        }

        if (state.browseLoading && state.browseEntries.isEmpty()) {
            Text(
                "loading…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(state.browseEntries, key = { it.fileName }) { entry ->
                    FsRow(entry, onOpenDir = {
                        onNavigate(joinPath(path, entry.fileName))
                    })
                }
                if (state.browseEntries.isEmpty()) {
                    item {
                        Text(
                            "empty",
                            color = InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onJump: (String) -> Unit) {
    val segments = remember(path) {
        buildList {
            add("/" to "/")
            if (path != "/" && path.isNotEmpty()) {
                val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
                var acc = ""
                for (p in parts) {
                    acc += "/$p"
                    add(p to acc)
                }
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(segments.size) { idx ->
                val (label, target) = segments[idx]
                val active = idx == segments.lastIndex
                Text(
                    text = label,
                    color = if (active) Ink else AccentDeep,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable(enabled = !active) { onJump(target) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (!active) {
                    Text(
                        "/",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewFolderRow(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, AccentDeep),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "▤",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(AccentDeep),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (name.isEmpty()) {
                        Text(
                            "folder name",
                            color = InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                },
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentDeep,
                    contentColor = Color.Black,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = InkDim,
                ),
                shape = RectangleShape,
            ) {
                Text("Create", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FsRow(entry: FsEntry, onOpenDir: () -> Unit) {
    val dir = entry.isDirectory
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = dir, onClick = onOpenDir),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (dir) "▸" else " ",
                color = if (dir) Amber else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.fileName,
                color = if (dir) Ink else InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun joinPath(base: String, name: String): String {
    val normalizedBase = if (base.endsWith("/")) base.dropLast(1) else base
    return "$normalizedBase/$name".ifEmpty { "/$name" }
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
    onPermissionsChange: (PermissionsMode) -> Unit,
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
            permissions = state.permissions,
            planMode = state.planMode,
            pendingImages = state.pendingImages,
            modelOptions = state.modelOptions,
            onModelChange = onModelChange,
            onEffortChange = onEffortChange,
            onPermissionsChange = onPermissionsChange,
            onSend = onSend,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
        )
    }
}

@Composable
private fun ApprovalDialog(
    prompt: ApprovalPrompt,
    onDecision: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision("cancel") },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        title = {
            Text(
                if (prompt.kind == "command") "COMMAND APPROVAL"
                else "FILE CHANGE APPROVAL",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                prompt.reason?.let {
                    Text(
                        it,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                prompt.command?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Line),
                    ) {
                        Text(
                            it,
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                prompt.cwd?.let {
                    Text(
                        "cwd: $it",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if ("acceptForSession" in prompt.decisions) {
                    TextButton(onClick = { onDecision("acceptForSession") }) {
                        Text(
                            "always",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
                TextButton(onClick = { onDecision("accept") }) {
                    Text(
                        "accept",
                        color = Ok,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision("decline") }) {
                Text(
                    "decline",
                    color = Warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        },
    )
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

// Event groups: a user event is its own group, but a run of
// reasoning/agent/tool/system events collapses into a single AGENT
// group so the UI shows ONE label + ONE continuous blue stripe
// spanning the whole turn (matches web dashboard).
private data class EventGroup(val kind: Kind, val events: List<UiEvent>) {
    enum class Kind { USER, AGENT }
}

private fun groupUiEvents(events: List<UiEvent>): List<EventGroup> {
    val out = mutableListOf<EventGroup>()
    for (e in events) {
        if (e is UiEvent.User) {
            out.add(EventGroup(EventGroup.Kind.USER, listOf(e)))
            continue
        }
        val last = out.lastOrNull()
        if (last != null && last.kind == EventGroup.Kind.AGENT) {
            out[out.lastIndex] = last.copy(events = last.events + e)
        } else {
            out.add(EventGroup(EventGroup.Kind.AGENT, listOf(e)))
        }
    }
    return out
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
    val groups = remember(events) { groupUiEvents(events) }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        items(groups, key = { it.events.first().id }) { group ->
            when (group.kind) {
                EventGroup.Kind.USER -> UserBubble(group.events.first() as UiEvent.User)
                EventGroup.Kind.AGENT -> AgentGroup(group.events, pending)
            }
        }
    }
}

@Composable
private fun UserBubble(event: UiEvent.User) {
    val userAccent = Color(0xFF8FB4FF)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .wrapContentWidth(Alignment.End)
        ) {
            if (event.imageUris.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(event.imageUris) { uri ->
                        AsyncImage(
                            model = uri.toUri(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .background(Color(0x1A3AA0E8))
                    .border(1.dp, Color(0x473AA0E8))
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        "USER",
                        color = userAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        event.text,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 24.dp)
                        .background(userAccent),
                )
            }
        }
    }
}

@Composable
private fun AgentGroup(events: List<UiEvent>, pending: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 40.dp)
                .background(AccentDeep),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                "AGENT",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(6.dp))
            events.forEachIndexed { idx, event ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                AgentSubEvent(event, pending)
            }
        }
    }
}

@Composable
private fun AgentSubEvent(event: UiEvent, pending: Boolean) {
    when (event) {
        is UiEvent.Reasoning -> {
            var expanded by rememberSaveable(event.id) { mutableStateOf(!event.replayed) }
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "REASONING",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (expanded) {
                Spacer(Modifier.height(3.dp))
                MarkdownText(
                    text = event.text.ifEmpty { "…" },
                    color = InkDim,
                )
            }
        }
        is UiEvent.Tool -> {
            var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "TOOL · ${event.tool}",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (event.command.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                CodeBlock(event.command)
            }
            if (event.output.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val lines = event.output.split('\n')
                val needsTruncation = lines.size > 5
                val shown = if (expanded || !needsTruncation) {
                    event.output
                } else {
                    val head = lines.take(2)
                    val tail = lines.takeLast(2)
                    (head + "…" + tail).joinToString("\n")
                }
                CodeBlock(shown, dim = true)
                if (needsTruncation && !expanded) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "… ${lines.size - 4} more lines — tap to expand",
                        color = AccentDeep,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.clickable { expanded = true },
                    )
                }
            }
        }
        is UiEvent.Agent -> {
            MarkdownText(
                text = event.text,
                color = Ink,
                trailingCursor = pending && !event.completed,
            )
        }
        is UiEvent.System -> {
            Text(
                event.label.uppercase(),
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(3.dp))
            BodyText(event.detail.ifEmpty { event.label })
        }
        is UiEvent.User -> Unit // handled by group split, should not happen here
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
        shape = RectangleShape,
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
    permissions: PermissionsMode,
    planMode: Boolean,
    pendingImages: List<PendingImage>,
    modelOptions: List<ModelOption>,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
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
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
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
            // Row 1 — model + effort + permissions chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactModelPicker(
                    selected = model,
                    options = modelOptions,
                    onSelect = onModelChange,
                    modifier = Modifier.weight(1f),
                )
                CompactEffortPicker(
                    model = model,
                    selected = effort,
                    options = modelOptions,
                    onSelect = onEffortChange,
                    modifier = Modifier.weight(1f),
                )
                CompactPermissionsPicker(
                    selected = permissions,
                    onSelect = onPermissionsChange,
                    modifier = Modifier.weight(1f),
                )
            }
            if (planMode) {
                Text(
                    "plan mode active — /default to clear",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
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
                    shape = RectangleShape,
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
        shape = RectangleShape,
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
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val list = if (options.isNotEmpty()) options else MODEL_OPTIONS
    val current = list.firstOrNull { it.id == selected } ?: list.first()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
            list.forEach { opt ->
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
private fun CompactPermissionsPicker(
    selected: PermissionsMode,
    onSelect: (PermissionsMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        border = BorderStroke(1.dp, if (selected == PermissionsMode.Full) Warn else Line),
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("perms", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                selected.label.lowercase(),
                color = if (selected == PermissionsMode.Full) Warn else Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
            PermissionsMode.entries.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                opt.label,
                                color = if (opt == PermissionsMode.Full) Warn else Ink,
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
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CompactEffortPicker(
    model: String,
    selected: String,
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val efforts = effortsFor(model, options)
    val effectiveSelected = if (selected in efforts) selected else ""
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Line),
        ) {
            efforts.forEach { opt ->
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

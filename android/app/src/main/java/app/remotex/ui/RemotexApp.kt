package app.remotex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remotex.ui.app.RemotexBar
import app.remotex.ui.screens.files.FilesScreen
import app.remotex.ui.screens.hosts.HostsScreen
import app.remotex.ui.screens.session.ApprovalDialog
import app.remotex.ui.screens.session.SessionScreen
import app.remotex.ui.screens.session.SessionSideRail
import app.remotex.ui.screens.session.SplitSessionPane
import app.remotex.ui.screens.session.UserInputDialog
import app.remotex.ui.screens.threads.ThreadsScreen
import app.remotex.ui.screens.telemetry.TelemetryPanel
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotexApp(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit = {},
    darkTheme: Boolean = true,
    highContrast: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onSessionOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    // Keyed on the URL: changing it in settings builds a fresh ViewModel
    // (and RelayClient) pointed at the new relay — no reinstall, no
    // process restart.
    val vm: RemotexViewModel = viewModel(
        key = relayUrl,
        factory = RemotexViewModel.factory(context.applicationContext as android.app.Application, relayUrl)
    )
    val state by vm.state.collectAsState()
    var telemetryOpen by rememberSaveable { mutableStateOf(false) }
    // Thread shown in the tablet split view's second chat column; null =
    // no split. Survives rotation; the pane simply hides when the window
    // drops below split width and comes back when it grows again.
    var splitThreadId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.session?.sessionId) {
        if (state.session != null) onSessionOpened()
    }

    BackHandler(enabled = state.screen == Screen.Session) { vm.goToThreads() }
    BackHandler(enabled = state.screen == Screen.Files) { vm.goToThreads() }
    BackHandler(enabled = state.screen == Screen.Threads) { vm.goToHosts() }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.reconnectInventoryNow()
            if (event == Lifecycle.Event.ON_START &&
                currentState.screen == Screen.Session &&
                currentState.status != Status.Connected &&
                currentState.session != null
            ) {
                vm.reconnectNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val permanentTelemetry = state.screen == Screen.Session &&
            usePermanentTelemetryPane(maxWidth, maxHeight)
        val splitCapable = state.screen == Screen.Session &&
            useSplitChat(maxWidth, maxHeight)
        val selectedHost = state.hosts.find { it.id == state.selectedHostId }

        LaunchedEffect(state.screen, permanentTelemetry) {
            if (state.screen != Screen.Session || permanentTelemetry) telemetryOpen = false
        }
        // The rail's history tab needs the thread list; it's empty when the
        // app restored straight into a session without visiting Threads.
        LaunchedEffect(permanentTelemetry) {
            if (permanentTelemetry && state.threads.isEmpty()) vm.refreshThreads()
        }

        Scaffold(
            topBar = {
                RemotexBar(
                    state = state,
                    onBack = when (state.screen) {
                        Screen.Threads -> ({ vm.goToHosts() })
                        Screen.Files -> ({ vm.goToThreads() })
                        Screen.Session -> ({ vm.goToThreads() })
                        Screen.Hosts -> ({})
                    },
                    onModelChange = vm::setModel,
                    onEffortChange = vm::setEffort,
                    darkTheme = darkTheme,
                    highContrast = highContrast,
                    onToggleTheme = onToggleTheme,
                    onOpenTelemetry = { telemetryOpen = true },
                    showTelemetryAction = !permanentTelemetry,
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (state.screen) {
                    Screen.Hosts -> HostsScreen(
                        state = state,
                        onTokenChange = vm::setToken,
                        relayUrl = relayUrl,
                        onRelayUrlChange = { next ->
                            if (next != relayUrl) vm.releaseForRelayChange()
                            onRelayUrlChange(next)
                        },
                        onRefresh = vm::refresh,
                        onHostTap = vm::openHost,
                        onModelChange = vm::setModel,
                        onEffortChange = vm::setEffort,
                    )
                    Screen.Threads -> ThreadsScreen(
                        state = state,
                        onRefresh = vm::refreshThreads,
                        onNewSession = {
                            vm.goToFiles()
                        },
                        onResumeThread = { vm.openSession(it.id) },
                    )
                    Screen.Files -> FilesScreen(
                        state = state,
                        onNavigate = vm::browseDir,
                        onUp = vm::browseUp,
                        onStartHere = vm::startSessionInCurrentPath,
                        onCreateFolder = vm::createFolder,
                        onToggleFavorite = vm::toggleFavorite,
                    )
                    Screen.Session -> {
                        val sessionContent: @Composable (Modifier) -> Unit = { modifier ->
                            SessionScreen(
                                state = state,
                                onSend = vm::sendTurn,
                                onStop = vm::interruptTurn,
                                onSteer = vm::steerTurn,
                                onQueue = vm::queueTurn,
                                onRemoveQueued = vm::removeQueuedTurn,
                                onLoadOlder = vm::loadOlderHistory,
                                onAttachImage = vm::attachImage,
                                onImagePickerActive = vm::setImagePickerActive,
                                onRemoveImage = vm::removeImage,
                                onModelChange = vm::setModel,
                                onEffortChange = vm::setEffort,
                                onPermissionsChange = vm::setPermissions,
                                onSlashCommand = vm::sendSlash,
                                onListWorkspace = vm::listWorkspace,
                                onDeleteWorkspaceFile = vm::deleteWorkspaceFile,
                                onRenameWorkspaceFile = vm::renameWorkspaceFile,
                                onReadWorkspaceFile = vm::readWorkspaceFile,
                                onUploadWorkspaceFile = vm::uploadWorkspaceFile,
                                modifier = modifier,
                            )
                        }
                        val splitOpen = splitCapable && splitThreadId != null &&
                            state.selectedHostId != null
                        when {
                            // Two chats: the rail gives its space to the
                            // second column; close it to get the rail back.
                            splitOpen -> Row(Modifier.fillMaxSize()) {
                                sessionContent(Modifier.weight(1f))
                                VerticalDivider(color = Line)
                                SplitSessionPane(
                                    relayUrl = relayUrl,
                                    hostId = state.selectedHostId!!,
                                    threadId = splitThreadId!!,
                                    onClose = { splitThreadId = null },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            permanentTelemetry -> Row(Modifier.fillMaxSize()) {
                                sessionContent(Modifier.weight(1f))
                                VerticalDivider(color = Line)
                                SessionSideRail(
                                    threads = state.threads,
                                    threadsLoading = state.threadsLoading,
                                    activeThreadId = state.session?.threadId,
                                    splitEnabled = splitCapable,
                                    hostLabel = selectedHost?.nickname ?: "no host selected",
                                    snapshot = state.selectedHostId?.let { state.hostTelemetry[it] },
                                    onRefreshThreads = vm::refreshThreads,
                                    onOpenThread = { vm.openSession(it.id) },
                                    onSplitThread = { splitThreadId = it.id },
                                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                                )
                            }
                            else -> sessionContent(Modifier.fillMaxSize())
                        }
                    }
                }
                // Only the head of each queue is rendered (contract F);
                // answering it pops it and the next one takes its place.
                state.pendingApproval?.let { appr ->
                    ApprovalDialog(
                        prompt = appr,
                        queuedBehind = state.pendingApprovals.size - 1,
                        onDecision = { vm.resolveApproval(it) },
                    )
                }
                state.pendingUserInput?.let { ui ->
                    UserInputDialog(
                        prompt = ui,
                        queuedBehind = state.pendingUserInputs.size - 1,
                        onSubmit = { vm.resolveUserInput(it) },
                        onCancel = { vm.cancelUserInput() },
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
                            .padding(bottom = 140.dp, start = 12.dp, end = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
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
                if (state.screen != Screen.Hosts) state.error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Warn),
                        shape = RectangleShape,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive },
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

        // Telemetry rides a bottom sheet, matching the web phone layout.
        if (telemetryOpen && !permanentTelemetry) {
            ModalBottomSheet(
                onDismissRequest = { telemetryOpen = false },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                TelemetryPanel(
                    hostLabel = selectedHost?.nickname ?: "no host selected",
                    snapshot = state.selectedHostId?.let { state.hostTelemetry[it] },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                )
            }
        }
    }
}

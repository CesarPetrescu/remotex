package app.remotex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remotex.ui.app.RemotexBar
import app.remotex.ui.app.WireMeshBackdrop
import app.remotex.ui.screens.files.FilesScreen
import app.remotex.ui.screens.hosts.HostsScreen
import app.remotex.ui.screens.search.SearchScreen
import app.remotex.ui.screens.session.ApprovalDialog
import app.remotex.ui.screens.session.SessionScreen
import app.remotex.ui.screens.session.UserInputDialog
import app.remotex.ui.screens.threads.ThreadsScreen
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Line
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

    BackHandler(enabled = state.screen == Screen.Session) { vm.goToThreads() }
    BackHandler(enabled = state.screen == Screen.Files) { vm.goToThreads() }
    BackHandler(enabled = state.screen == Screen.Threads) { vm.goToHosts() }
    BackHandler(enabled = state.screen == Screen.Search) { vm.goToHosts() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START &&
                state.screen == Screen.Session &&
                state.status != Status.Connected &&
                state.session != null
            ) {
                vm.reconnectNow()
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
                        onPreferredKindChange = vm::setPreferredKind,
                        onSlashCommand = vm::sendSlash,
                        onListWorkspace = vm::listWorkspace,
                        onDeleteWorkspaceFile = vm::deleteWorkspaceFile,
                        onRenameWorkspaceFile = vm::renameWorkspaceFile,
                        onReadWorkspaceFile = vm::readWorkspaceFile,
                        onUploadWorkspaceFile = vm::uploadWorkspaceFile,
                    )
                }
                state.pendingApproval?.let { appr ->
                    ApprovalDialog(prompt = appr, onDecision = { vm.resolveApproval(it) })
                }
                state.pendingUserInput?.let { ui ->
                    UserInputDialog(
                        prompt = ui,
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

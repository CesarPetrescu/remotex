package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remotex.security.relayScopeKey
import app.remotex.ui.RemotexViewModel
import app.remotex.ui.Status
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Warn

/**
 * Second chat column for the tablet split view. Runs its own
 * [RemotexViewModel] (own session socket, own approval queues) against
 * the same relay and host, so both chats stream independently —
 * fan-out on the daemon side already supports multiple clients per
 * session, and this is just one more client.
 */
@Composable
internal fun SplitSessionPane(
    relayUrl: String,
    hostId: String,
    threadId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: RemotexViewModel = viewModel(
        key = "$relayUrl#split",
        factory = RemotexViewModel.factory(
            app,
            relayUrl,
            activeSessionScope = "${relayScopeKey(relayUrl)}#split",
        ),
    )
    val state by vm.state.collectAsState()

    // The view model loads the persisted token asynchronously; the socket
    // can't open without it, so wait for it before resuming the thread.
    LaunchedEffect(threadId, state.userToken.isBlank()) {
        if (state.userToken.isNotBlank() && state.session?.threadId != threadId) {
            vm.openSession(resumeThreadId = threadId, hostId = hostId)
        }
    }

    Column(modifier.testTag("split-session-pane")) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SPLIT",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text(
                when (state.status) {
                    Status.Connected -> "  ■ connected"
                    Status.Connecting -> "  ■ connecting"
                    else -> "  ■ ${state.status.name.lowercase()}"
                },
                color = if (state.status == Status.Connected) InkDim else Warn,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = Color.Transparent,
                border = BorderStroke(1.dp, Line),
                shape = RectangleShape,
                onClick = onClose,
                modifier = Modifier
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .semantics { contentDescription = "Close split view" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "✕",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        HorizontalDivider(color = Line)
        Box(Modifier.weight(1f)) {
            SessionScreen(
                state = state,
                onSend = vm::sendTurn,
                onStop = vm::interruptTurn,
                onSteer = vm::steerTurn,
                onQueue = vm::queueTurn,
                onRemoveQueued = vm::removeQueuedTurn,
                onLoadOlder = vm::loadOlderHistory,
                onAttachImage = vm::attachImage,
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    // The split chat keeps its own approval/user-input queues; dialogs are
    // modal app-wide either way, so rendering them here is equivalent to
    // the primary pane's wiring in RemotexApp.
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
}

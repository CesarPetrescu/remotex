package app.remotex.ui.screens.session

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.remotex.model.FsEntry
import app.remotex.net.RelayClient
import app.remotex.ui.ContentTooLargeException
import app.remotex.ui.MAX_FILE_BYTES
import app.remotex.ui.PermissionsMode
import app.remotex.ui.Status
import app.remotex.ui.UiState
import app.remotex.ui.openableMetadata
import app.remotex.ui.readBounded
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.screens.session.events.EventList
import app.remotex.ui.screens.session.files.WorkspaceFilesPanel
import app.remotex.ui.screens.session.files.decodeBase64
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SessionScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onSteer: (String) -> Unit,
    onQueue: (String) -> Unit,
    onRemoveQueued: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
    onModelChange: (String) -> Unit = {},
    onEffortChange: (String) -> Unit = {},
    onSlashCommand: (cmd: String, args: String) -> Unit,
    onListWorkspace: suspend (path: String) -> List<FsEntry>,
    onDeleteWorkspaceFile: suspend (path: String) -> Unit,
    onRenameWorkspaceFile: suspend (from: String, to: String) -> Unit,
    onReadWorkspaceFile: suspend (path: String) -> RelayClient.WorkspaceFile,
    onUploadWorkspaceFile: suspend (dir: String, name: String, bytes: ByteArray, mime: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filesPanelOpen by remember { mutableStateOf(false) }
    val workspaceCwd = state.session?.cwd?.takeIf { it.isNotBlank() } ?: "/"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, mime, bytes) = withContext(Dispatchers.IO) {
                    val metadata = ctx.contentResolver.openableMetadata(uri)
                    metadata.size?.let { size ->
                        if (size > MAX_FILE_BYTES) {
                            throw ContentTooLargeException(size, MAX_FILE_BYTES)
                        }
                    }
                    val name = metadata.displayName ?: "upload.bin"
                    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use {
                        readBounded(it, MAX_FILE_BYTES)
                    } ?: error("could not read selected file")
                    Triple(name, mime, bytes)
                }
                onUploadWorkspaceFile(workspaceCwd, name, bytes, mime)
                Toast.makeText(ctx, "Uploaded $name to $workspaceCwd", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Upload failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // imePadding() lifts the bottom of the column above the soft keyboard.
    // This ONLY works with android:windowSoftInputMode="adjustResize" in the
    // manifest: the unspecified default resolves to adjustPan here, which
    // pans the whole window AND lets imePadding lift again — composer ends
    // up a full keyboard-height above the IME (the 2026-08-12 bug).
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .imePadding()
                .testTag("session-content"),
        ) {
            MetaBar(
                state = state,
                onModelChange = onModelChange,
                onEffortChange = onEffortChange,
                onPermissionsChange = onPermissionsChange,
                onOpenFiles = { filesPanelOpen = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
            )
            if (state.resuming) {
                ResumingBanner(sinceMs = state.resumingSinceMs)
            }
            // SelectionContainer wrapped around a weight(1f) LazyColumn breaks
            // vertical sizing (the LazyColumn ends up with unbounded max
            // height and pushes the composer off-screen). Each event row
            // wraps its own text in SelectionContainer instead — drag-
            // select still works inside a single event.
            EventList(
                events = state.events,
                pending = state.pending,
                connected = state.status == Status.Connected,
                historyHasMore = state.historyHasMore,
                historyLoading = state.historyLoading,
                historyTailTick = state.historyTailTick,
                historyCount = state.historyEventCount,
                onLoadOlder = onLoadOlder,
                modifier = Modifier.weight(1f, fill = true),
            )
            ComposerBar(
                // Allow typing during resume — the daemon waits up to 20s
                // for resume to finish before rejecting a turn-start, so
                // best case the send succeeds, worst case we get a clean
                // "still resuming" error. Either way, blocking the input
                // while resume is in flight makes the app feel hung.
                connected = state.status == Status.Connected,
                pending = state.pending,
                planMode = state.planMode,
                pendingImages = state.pendingImages,
                queuedTurns = state.queuedTurns,
                onSend = onSend,
                onStop = onStop,
                onSteer = onSteer,
                onQueue = onQueue,
                onRemoveQueued = onRemoveQueued,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onSlashCommand = onSlashCommand,
            )
        }
    }
    if (filesPanelOpen) {
        WorkspaceFilesPanel(
            cwd = workspaceCwd,
            onDismiss = { filesPanelOpen = false },
            onList = onListWorkspace,
            onDelete = onDeleteWorkspaceFile,
            onRename = onRenameWorkspaceFile,
            onRead = { path ->
                val f = onReadWorkspaceFile(path)
                f.name to decodeBase64(f.base64)
            },
        )
    }
}

package app.remotex.ui.screens.session

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.remotex.model.FsEntry
import app.remotex.net.RelayClient
import app.remotex.ui.PermissionsMode
import app.remotex.ui.Status
import app.remotex.ui.UiState
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.screens.session.events.EventList
import app.remotex.ui.screens.session.files.WorkspaceFilesPanel
import app.remotex.ui.screens.session.files.decodeBase64
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
    onSlashCommand: (cmd: String, args: String) -> Unit,
    onListWorkspace: suspend (path: String) -> List<FsEntry>,
    onDeleteWorkspaceFile: suspend (path: String) -> Unit,
    onRenameWorkspaceFile: suspend (from: String, to: String) -> Unit,
    onReadWorkspaceFile: suspend (path: String) -> RelayClient.WorkspaceFile,
    onUploadWorkspaceFile: suspend (dir: String, name: String, bytes: ByteArray, mime: String) -> Unit,
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
                val name = ctx.contentResolver.query(uri, null, null, null, null)
                    ?.use { cur ->
                        val nameIdx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cur.moveToFirst()) cur.getString(nameIdx) else "upload.bin"
                    } ?: "upload.bin"
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("could not read selected file")
                onUploadWorkspaceFile(workspaceCwd, name, bytes, mime)
                Toast.makeText(ctx, "Uploaded $name to $workspaceCwd", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Upload failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // imePadding() lifts the bottom of the column above the soft keyboard
    // so the composer stays visible while the user is typing.
    // enableEdgeToEdge() at the activity level + a transparent Scaffold
    // means the composer otherwise sits underneath the IME / nav bar.
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        MetaBar(
            state = state,
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
            onSend = onSend,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            onSlashCommand = onSlashCommand,
        )
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

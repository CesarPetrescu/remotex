package app.remotex.ui.screens.session

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.FsEntry
import app.remotex.net.RelayClient
import app.remotex.ui.PermissionsMode
import app.remotex.ui.Status
import app.remotex.ui.UiState
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.screens.session.events.EventList
import app.remotex.ui.screens.session.files.WorkspaceFilesPanel
import app.remotex.ui.screens.session.files.decodeBase64
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
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
        MetaBar(state)
        if (state.resuming) {
            ResumingBanner(sinceMs = state.resumingSinceMs)
        }
        // Workspace toolbar — sits between MetaBar and the chat events.
        // Two affordances:
        //   📁  open the workspace files panel (rename/delete/download)
        //   +   upload a file into the chat's cwd (NOT image attach;
        //       image attach lives in the composer paperclip)
        WorkspaceToolbar(
            cwd = workspaceCwd,
            onOpenFiles = { filesPanelOpen = true },
            onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
        )
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

@Composable
private fun WorkspaceToolbar(
    cwd: String,
    onOpenFiles: () -> Unit,
    onUpload: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarButton("📁 Files", InkDim, onClick = onOpenFiles)
            ToolbarButton("+ Add", AccentDeep, onClick = onUpload)
            Spacer(Modifier.width(4.dp))
            Text(
                cwd,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ToolbarButton(label: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        shape = RectangleShape,
        modifier = Modifier.width(if (label.startsWith("+")) 80.dp else 90.dp),
        onClick = onClick,
    ) {
        Text(
            text = label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

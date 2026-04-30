package app.remotex.ui.screens.session.files

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Base64
import android.widget.Toast
import app.remotex.model.FsEntry
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet panel showing the contents of the chat's cwd. Each file
 * row has a kebab menu with Rename / Delete / Download.
 *
 * The session screen drives this — it owns the open/closed state and
 * passes the cwd in. The panel only knows how to talk to the relay
 * via the supplied callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceFilesPanel(
    cwd: String,
    onDismiss: () -> Unit,
    onList: suspend (path: String) -> List<FsEntry>,
    onDelete: suspend (path: String) -> Unit,
    onRename: suspend (from: String, to: String) -> Unit,
    onRead: suspend (path: String) -> Pair<String, ByteArray>,  // (name, bytes)
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var path by remember { mutableStateOf(cwd.ifEmpty { "/" }) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FsEntry?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val payload = pendingDownload
        pendingDownload = null
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                writeBytesToUri(ctx, uri, payload.second)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Saved ${payload.first}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    suspend fun refresh() {
        loading = true; error = null
        try {
            entries = onList(path)
        } catch (t: Throwable) {
            error = t.message ?: "list failed"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(path) { refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "WORKSPACE FILES",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                if (path != "/") {
                    TextButton(onClick = {
                        path = path.substringBeforeLast('/').ifEmpty { "/" }
                    }) {
                        Text("↑ up", color = AccentDeep, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                path,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            error?.let {
                Text(it, color = Warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (loading) {
                Text("loading…", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().height(360.dp)) {
                    items(entries, key = { it.fileName }) { entry ->
                        WorkspaceRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) {
                                    path = if (path.endsWith("/")) path + entry.fileName else "$path/${entry.fileName}"
                                }
                            },
                            onRename = { renameTarget = entry },
                            onDelete = { deleteTarget = entry },
                            onDownload = {
                                scope.launch {
                                    try {
                                        val full = if (path.endsWith("/")) path + entry.fileName else "$path/${entry.fileName}"
                                        val (name, bytes) = onRead(full)
                                        pendingDownload = name to bytes
                                        createDocLauncher.launch(name)
                                    } catch (t: Throwable) {
                                        Toast.makeText(ctx, "Read failed: ${t.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                    if (entries.isEmpty()) {
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

    renameTarget?.let { target ->
        var newName by remember(target.fileName) { mutableStateOf(target.fileName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            title = { Text("Rename", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            text = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, Line),
                    shape = RectangleShape,
                ) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        textStyle = TextStyle(color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(),
                        singleLine = true,
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nameTrimmed = newName.trim()
                    if (nameTrimmed.isBlank() || nameTrimmed == target.fileName) {
                        renameTarget = null; return@TextButton
                    }
                    val from = if (path.endsWith("/")) path + target.fileName else "$path/${target.fileName}"
                    val to = if (path.endsWith("/")) path + nameTrimmed else "$path/$nameTrimmed"
                    scope.launch {
                        try {
                            onRename(from, to)
                            refresh()
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "Rename failed: ${t.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            renameTarget = null
                        }
                    }
                }) { Text("rename", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("cancel", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            title = { Text("Delete file", color = Warn, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            text = { Text("Delete ${target.fileName}? This cannot be undone.", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val full = if (path.endsWith("/")) path + target.fileName else "$path/${target.fileName}"
                    scope.launch {
                        try {
                            onDelete(full)
                            refresh()
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "Delete failed: ${t.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            deleteTarget = null
                        }
                    }
                }) { Text("delete", color = Warn, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("cancel", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    entry: FsEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory, onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).background(if (entry.isDirectory) Amber else Ok),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.fileName,
            color = if (entry.isDirectory) Ink else InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions", tint = InkDim)
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RectangleShape,
                containerColor = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Line),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Download", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        onClick = { menu = false; onDownload() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    onClick = { menu = false; onRename() },
                )
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = Warn, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

internal fun decodeBase64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

private suspend fun writeBytesToUri(ctx: Context, uri: Uri, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

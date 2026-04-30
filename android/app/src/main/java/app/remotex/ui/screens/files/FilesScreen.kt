package app.remotex.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.UiState
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

@Composable
fun FilesScreen(
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
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "pick a folder for the new session".uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )

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
            SelectionContainer {
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
}

package app.remotex.ui.screens.session.composer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import app.remotex.ui.ModelOption
import app.remotex.ui.PendingImage
import app.remotex.ui.PermissionsMode
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun ComposerBar(
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
    // 4th chip — kind for the next "+ New session". Inside an active
    // session [sessionKind] is non-null and locks the picker read-only.
    preferredKind: SessionKind = SessionKind.Coder,
    sessionKind: SessionKind? = null,
    onPreferredKindChange: (SessionKind) -> Unit = {},
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
            // A8: horizontal scroll instead of weighted Row so the chips
            // never wrap to a second line on narrow phones. Each chip
            // sizes to its content; users swipe sideways if all three
            // don't fit, which is way better than a 2-row composer.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    CompactModelPicker(
                        selected = model,
                        options = modelOptions,
                        onSelect = onModelChange,
                    )
                }
                item {
                    CompactEffortPicker(
                        model = model,
                        selected = effort,
                        options = modelOptions,
                        onSelect = onEffortChange,
                    )
                }
                item {
                    CompactPermissionsPicker(
                        selected = permissions,
                        onSelect = onPermissionsChange,
                    )
                }
                item {
                    CompactKindPicker(
                        selected = preferredKind,
                        onSelect = onPreferredKindChange,
                        lockedTo = sessionKind,
                    )
                }
            }
            if (planMode) {
                Text(
                    "plan mode active — /default to clear",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
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

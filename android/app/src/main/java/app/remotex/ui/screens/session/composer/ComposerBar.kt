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
import app.remotex.ui.theme.AccentDeep
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
    // 4th chip — kind selector. When sessionKind is non-null (i.e.
    // we're already in a session) the chip becomes a "switch session"
    // affordance: picking the OTHER kind starts a NEW session of that
    // kind (current one keeps running). Outside a session it falls
    // back to setting preferredKind.
    preferredKind: SessionKind = SessionKind.Coder,
    sessionKind: SessionKind? = null,
    onPreferredKindChange: (SessionKind) -> Unit = {},
    onSwitchToCoder: () -> Unit = {},
    onSwitchToOrchestrator: () -> Unit = {},
    // Slash command sender — composer bypasses sendTurn for these.
    onSlashCommand: (cmd: String, args: String) -> Unit = { _, _ -> },
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
                        // Show the actual session kind when we have
                        // one; outside any session, show the user's
                        // standing preference for the next "+ New".
                        selected = sessionKind ?: preferredKind,
                        onSelect = { picked ->
                            if (sessionKind != null) {
                                if (picked == sessionKind) return@CompactKindPicker
                                when (picked) {
                                    SessionKind.Orchestrator -> onSwitchToOrchestrator()
                                    SessionKind.Coder -> onSwitchToCoder()
                                }
                            } else {
                                onPreferredKindChange(picked)
                            }
                        },
                        // Never locked — the chip is now a real
                        // switcher even mid-session.
                        lockedTo = null,
                    )
                }
            }
            // Plan-mode toggle chip (Phase C). One tap flips it on/off
            // without typing /plan. Sends the same slash so daemon state
            // stays the source of truth.
            PlanChip(
                planMode = planMode,
                onClick = {
                    onSlashCommand(if (planMode) "default" else "plan", "")
                },
            )
            if (text.startsWith("/") && !text.contains(' ')) {
                SlashAutocomplete(
                    query = text,
                    onPick = { cmd ->
                        if (cmd.takesArg) {
                            text = "/${cmd.id} "
                        } else {
                            onSlashCommand(cmd.id, "")
                            text = ""
                        }
                    },
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
                            val sent = handleSubmit(text, onSlashCommand, onSend)
                            if (sent) text = ""
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
                    onSend = {
                        val sent = handleSubmit(text, onSlashCommand, onSend)
                        if (sent) text = ""
                    },
                    onStop = onStop,
                )
            }
        }
    }
}

/** Returns true if the text should be cleared after submission. */
private fun handleSubmit(
    text: String,
    onSlashCommand: (String, String) -> Unit,
    onSend: (String) -> Unit,
): Boolean {
    if (text.startsWith("/")) {
        val trimmed = text.trim().removePrefix("/")
        val space = trimmed.indexOf(' ')
        val cmd = if (space == -1) trimmed else trimmed.substring(0, space)
        val args = if (space == -1) "" else trimmed.substring(space + 1)
        if (KNOWN_SLASHES.any { it.id == cmd }) {
            onSlashCommand(cmd, args)
            return true
        }
    }
    if (text.isBlank()) return false
    onSend(text)
    return true
}

internal data class SlashSpec(
    val id: String,
    val hint: String,
    val takesArg: Boolean = false,
    val argHint: String = "",
)

internal val KNOWN_SLASHES = listOf(
    SlashSpec("plan", "plan-then-act for the next turn (codex plan mode)"),
    SlashSpec("default", "clear plan mode"),
    SlashSpec("cd", "change cwd for next turns", takesArg = true, argHint = "<path>"),
    SlashSpec("pwd", "show current cwd"),
    SlashSpec("compact", "have codex summarise + compact the thread"),
)

@Composable
private fun PlanChip(planMode: Boolean, onClick: () -> Unit) {
    val accent = if (planMode) Amber else InkDim
    Surface(
        color = if (planMode) Color(0x1A5EE1FF) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (planMode) Amber else Line),
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(
                if (planMode)
                    "plan mode active — tap to clear"
                else
                    "plan mode (tap to enable for next turn)",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SlashAutocomplete(query: String, onPick: (SlashSpec) -> Unit) {
    val q = query.removePrefix("/").lowercase()
    val matches = KNOWN_SLASHES.filter { q.isEmpty() || it.id.startsWith(q) }
    if (matches.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, AccentDeep),
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            matches.forEach { cmd ->
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd) },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            "/${cmd.id}${if (cmd.takesArg) " ${cmd.argHint}" else ""}",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            cmd.hint,
                            color = InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

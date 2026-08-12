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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import app.remotex.ui.PendingImage
import app.remotex.ui.QueuedTurn
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun ComposerBar(
    connected: Boolean,
    pending: Boolean,
    planMode: Boolean,
    pendingImages: List<PendingImage>,
    imagePreparing: Boolean = false,
    queuedTurns: List<QueuedTurn> = emptyList(),
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onSteer: (String) -> Boolean = { false },
    onQueue: (String) -> Boolean = { false },
    onRemoveQueued: (String) -> Unit = {},
    onImagePickerActive: (Boolean) -> Unit = {},
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
    // Slash command sender — composer bypasses sendTurn for these.
    onSlashCommand: (cmd: String, args: String) -> Boolean = { _, _ -> false },
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Typing stays enabled during a turn: what you type is steered into the
    // running turn rather than blocked until it ends.
    val textEnabled = connected
    val hasContent = text.isNotBlank() || pendingImages.isNotEmpty()
    val canSteer = connected && pending && hasContent && !imagePreparing
    val slashOnly = pendingImages.isEmpty() && text.trimStart().startsWith("/")
    val canQueue = canSteer && !slashOnly
    val goalMode = isGoalCommand(text)
    // Plan + goal are mutually exclusive in the chip rail: the plan chip never
    // reads "on" while a /goal command is being composed (clicks already
    // cross-clear; this also covers typing /goal by hand).
    val planActive = planMode && !goalMode
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAttachImage(uri) else onImagePickerActive(false)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (imagePreparing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Amber,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Preparing image…",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
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
                            IconButton(
                                onClick = { onRemoveImage(idx) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(44.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .background(Color.Black.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove attached image",
                                        tint = Ink,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (queuedTurns.isNotEmpty()) {
                Text(
                    "next (${queuedTurns.size})",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(queuedTurns.size, key = { queuedTurns[it].id }) { index ->
                        val queued = queuedTurns[index]
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, Line),
                            shape = RectangleShape,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    queued.text.ifBlank {
                                        "${queued.imageCount} image${if (queued.imageCount == 1) "" else "s"}"
                                    },
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .width(160.dp)
                                        .padding(start = 8.dp),
                                )
                                IconButton(
                                    onClick = { onRemoveQueued(queued.id) },
                                    enabled = !queued.sending,
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove queued turn",
                                        tint = InkDim,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // One horizontal rail for composer modes only. Model/effort
            // live in the top bar; permissions live beside the path.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    PlanChip(
                        planMode = planActive,
                        onClick = {
                            if (onSlashCommand(if (planMode) "default" else "plan", "")) {
                                if (!planMode && goalMode) {
                                    text = removeGoalCommand(text)
                                }
                            }
                        },
                    )
                }
                item {
                    GoalSlashChip(
                        goalMode = goalMode,
                        onClick = {
                            if (goalMode) {
                                text = removeGoalCommand(text)
                            } else {
                                if (!planMode || onSlashCommand("default", "")) {
                                    text = addGoalCommand(text)
                                }
                            }
                        },
                    )
                }
            }
            if (text.startsWith("/") && !text.contains(' ')) {
                SlashAutocomplete(
                    query = text,
                    onPick = { cmd ->
                        if (cmd.takesArg) {
                            text = "/${cmd.id} "
                        } else if (onSlashCommand(cmd.id, "")) {
                            text = ""
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        onImagePickerActive(true)
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = textEnabled && !imagePreparing,
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Attach image",
                        tint = if (textEnabled && !imagePreparing) Ink else InkDim,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, Line),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp, max = 132.dp),
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
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (!imagePreparing) {
                                val sent = handleSubmit(
                                    text,
                                    pendingImages.isNotEmpty(),
                                    onSlashCommand,
                                    if (pending) onSteer else onSend,
                                )
                                if (sent) text = ""
                            }
                        }),
                        cursorBrush = SolidColor(Amber),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                if (text.isEmpty()) {
                                    Text(
                                        if (pending) "steer this turn" else "ask codex",
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
                    canSend = connected && !pending && hasContent && !imagePreparing,
                    canSteer = canSteer,
                    canQueue = canQueue,
                    onSend = {
                        val sent = handleSubmit(
                            text,
                            pendingImages.isNotEmpty(),
                            onSlashCommand,
                            onSend,
                        )
                        if (sent) text = ""
                    },
                    onStop = onStop,
                    onSteer = {
                        val sent = handleSubmit(
                            text,
                            pendingImages.isNotEmpty(),
                            onSlashCommand,
                            onSteer,
                        )
                        if (sent) text = ""
                    },
                    onQueue = {
                        val queued = handleSubmit(
                            text,
                            pendingImages.isNotEmpty(),
                            onSlashCommand,
                            onQueue,
                        )
                        if (queued) text = ""
                    },
                )
            }
        }
    }
}

/** Returns true if the text should be cleared after submission. */
internal fun handleSubmit(
    text: String,
    hasAttachments: Boolean,
    onSlashCommand: (String, String) -> Boolean,
    onSend: (String) -> Boolean,
): Boolean {
    if (!hasAttachments && text.startsWith("/")) {
        val trimmed = text.trim().removePrefix("/")
        val space = trimmed.indexOf(' ')
        val cmd = if (space == -1) trimmed else trimmed.substring(0, space)
        val args = if (space == -1) "" else trimmed.substring(space + 1)
        if (KNOWN_SLASHES.any { it.id == cmd }) {
            return onSlashCommand(cmd, args)
        }
    }
    if (text.isBlank() && !hasAttachments) return false
    return onSend(text)
}

internal data class SlashSpec(
    val id: String,
    val hint: String,
    val takesArg: Boolean = false,
    val argHint: String = "",
)

internal val KNOWN_SLASHES = listOf(
    SlashSpec("goal", "set or inspect the native Codex goal", takesArg = true, argHint = "<objective|pause|resume|clear>"),
    SlashSpec("plan", "plan-then-act for the next turn (codex plan mode)"),
    SlashSpec("default", "clear plan mode"),
    SlashSpec("cd", "change cwd for next turns", takesArg = true, argHint = "<path>"),
    SlashSpec("pwd", "show current cwd"),
    SlashSpec("compact", "have codex summarise + compact the thread"),
    SlashSpec("collab", "list collaboration modes available from codex"),
)

private fun isGoalCommand(text: String): Boolean =
    text == "/goal" || text.startsWith("/goal ")

private fun removeGoalCommand(text: String): String = when {
    text == "/goal" -> ""
    text.startsWith("/goal ") -> text.removePrefix("/goal ")
    else -> text
}

private fun addGoalCommand(text: String): String {
    if (text.isBlank()) return "/goal "
    if (text.startsWith("/")) return "/goal "
    return "/goal ${text.trimStart()}"
}

@Composable
private fun PlanChip(
    planMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (planMode) Amber else InkDim
    Surface(
        color = if (planMode) Color(0x1A5EE1FF) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (planMode) Amber else Line),
        shape = RectangleShape,
        modifier = modifier
            .sizeIn(minHeight = 34.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(
                if (planMode) "plan on" else "/plan",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GoalSlashChip(
    goalMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (goalMode) Amber else InkDim
    Surface(
        color = if (goalMode) Amber.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (goalMode) Amber else Line),
        shape = RectangleShape,
        modifier = modifier
            .sizeIn(minHeight = 34.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(
                if (goalMode) "goal on" else "/goal",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(matches.size, key = { matches[it].id }) { index ->
                val cmd = matches[index]
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 34.dp)
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

package app.remotex.ui.screens.session.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import app.remotex.ui.theme.LocalPalette
import app.remotex.model.UiEvent
import app.remotex.ui.MarkdownText
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

@Composable
internal fun AgentGroup(events: List<UiEvent>, pending: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 40.dp)
                .background(AccentDeep),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                "AGENT",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(6.dp))
            events.forEachIndexed { idx, event ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                AgentSubEvent(event, pending)
            }
        }
    }
}

@Composable
private fun AgentSubEvent(event: UiEvent, pending: Boolean) {
    when (event) {
        is UiEvent.Reasoning -> {
            // Streaming shows the live text; a finished thought folds to
            // one dim headline, like Claude Code.
            val streaming = pending && !event.completed
            var open by rememberSaveable(event.id) { mutableStateOf(false) }
            val expanded = streaming || open
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = !streaming) { open = !open },
            ) {
                Text("✳", color = LocalPalette.current.accentDeep, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "Thinking…" else buildString {
                        append("Thought")
                        if (!open) {
                            val head = headline(event.text)
                            if (head.isNotEmpty()) append(" · ").also { append(head) }
                        }
                    },
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontStyle = if (open || streaming) FontStyle.Normal else FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(3.dp))
                MarkdownText(
                    text = event.text.ifEmpty { "…" },
                    color = InkDim,
                )
            }
        }
        is UiEvent.Tool -> {
            var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
            val streaming = pending && !event.completed
            val failed = event.error.isNotBlank() ||
                (event.status.isNotBlank() && event.status == "failed")
            val isEdit = event.tool == "edit"
            // ● name(arg) · meta — the dot carries state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            when {
                                streaming -> LocalPalette.current.accent
                                failed -> LocalPalette.current.warn
                                else -> LocalPalette.current.ok
                            },
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    event.tool,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isEdit && event.command.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "(${headline(event.command)})",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                val meta = toolMeta(event).removePrefix(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            if (isEdit && event.output.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                DiffView(summary = event.command, diff = event.output, streaming = streaming)
            } else if (event.output.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val lines = event.output.split('\n')
                val limit = if (streaming) 4 else 5
                val needsTruncation = lines.size > limit
                val shown = when {
                    expanded || !needsTruncation -> event.output
                    // Running: follow the tail like a terminal.
                    streaming -> lines.takeLast(limit).joinToString("\n")
                    else -> lines.take(limit - 1).joinToString("\n")
                }
                Row {
                    Text("⎿", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        CodeBlock(shown, dim = true)
                        if (needsTruncation && !expanded) {
                            Text(
                                text = "… ${lines.size - limit + 1} more lines",
                                color = LocalPalette.current.accent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.clickable { expanded = true },
                            )
                        }
                    }
                }
            }
            if (expanded) {
                if (event.rawArguments.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("ARGUMENTS", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    Spacer(Modifier.height(2.dp))
                    CodeBlock(event.rawArguments, dim = true)
                }
                if (event.rawResult.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("RESULT", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    Spacer(Modifier.height(2.dp))
                    CodeBlock(event.rawResult, dim = true)
                }
                if (event.error.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("ERROR", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    Spacer(Modifier.height(2.dp))
                    CodeBlock(event.error, dim = true)
                }
            }
        }
        is UiEvent.Agent -> {
            MarkdownText(
                text = event.text,
                color = Ink,
                trailingCursor = pending && !event.completed,
            )
        }
        is UiEvent.System -> {
            Text(
                event.label.uppercase(),
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
            Spacer(Modifier.height(3.dp))
            BodyText(event.detail.ifEmpty { event.label })
        }
        // Rendered by EventList as its own group, never inside an agent turn.
        is UiEvent.User, is UiEvent.Gap -> Unit
    }
}

private fun toolMeta(event: UiEvent.Tool): String {
    val parts = mutableListOf<String>()
    if (event.status.isNotBlank()) parts += event.status
    event.durationMs?.let { parts += "${it}ms" }
    if (event.error.isNotBlank()) parts += "error"
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}


/** First line, markdown emphasis stripped, clipped for a header row. */
private fun headline(text: String): String {
    val line = text.lineSequence().firstOrNull()?.trim()?.replace(Regex("\\*\\*?|__"), "") ?: ""
    return if (line.length > 80) line.take(77) + "…" else line
}

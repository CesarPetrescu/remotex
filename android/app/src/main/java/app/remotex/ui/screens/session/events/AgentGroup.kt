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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            var expanded by rememberSaveable(event.id) { mutableStateOf(!event.replayed) }
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "REASONING",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
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
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "TOOL · ${event.tool}",
                color = AccentDeep,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (event.command.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                CodeBlock(event.command)
            }
            if (event.output.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val lines = event.output.split('\n')
                val needsTruncation = lines.size > 5
                val shown = if (expanded || !needsTruncation) {
                    event.output
                } else {
                    val head = lines.take(2)
                    val tail = lines.takeLast(2)
                    (head + "…" + tail).joinToString("\n")
                }
                CodeBlock(shown, dim = true)
                if (needsTruncation && !expanded) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "… ${lines.size - 4} more lines — tap to expand",
                        color = AccentDeep,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.clickable { expanded = true },
                    )
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
        is UiEvent.User -> Unit
    }
}

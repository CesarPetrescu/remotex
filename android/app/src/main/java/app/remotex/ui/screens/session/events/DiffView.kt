package app.remotex.ui.screens.session.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.LocalPalette
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

/**
 * Edit diffs the way Claude Code / Codex show them: one card per file with
 * a verb + path header, +N/−M counts, and green/red-tinted lines.
 *
 * Input contract (services/daemon/adapters/items.py::_format_changes):
 *   summary — one "verb /path" line per file (arrives as Tool.command)
 *   diff    — single file: the raw unified diff;
 *             multiple files: sections prefixed with "--- /path".
 */
private data class DiffFile(
    val verb: String,
    val path: String,
    val movedTo: String?,
    val lines: List<String>,
)

private const val COLLAPSED_LINES = 14

private val VERB_RE = Regex("""^(\w+)\s+(.+?)(?:\s+→\s+(.+))?$""")

private fun parseFiles(summary: String, diff: String): List<DiffFile> {
    val verbs = summary.split('\n').mapNotNull { raw ->
        VERB_RE.find(raw.trim())?.let { m ->
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3)?.ifBlank { null })
        }
    }
    val allLines = diff.split('\n')
    if (verbs.size <= 1) {
        val v = verbs.firstOrNull()
        return listOf(
            DiffFile(v?.first ?: "update", v?.second ?: "", v?.third, allLines),
        )
    }
    val files = mutableListOf<DiffFile>()
    var current: MutableList<String>? = null
    for (line in allLines) {
        val head = if (line.startsWith("--- ")) line.removePrefix("--- ") else null
        val match = head?.let { h -> verbs.find { it.second == h } }
        if (match != null) {
            val lines = mutableListOf<String>()
            files.add(DiffFile(match.first, match.second, match.third, lines))
            current = lines
            continue
        }
        current?.add(line)
    }
    return files.ifEmpty { listOf(DiffFile("update", "", null, allLines)) }
}

@Composable
internal fun DiffView(summary: String, diff: String, streaming: Boolean) {
    val files = parseFiles(summary, diff)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEachIndexed { index, file ->
            FileDiff(file, streaming, key = "$index-${file.path}")
        }
    }
}

@Composable
private fun FileDiff(file: DiffFile, streaming: Boolean, key: String) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val adds = file.lines.count { it.startsWith("+") && !it.startsWith("+++") }
    val dels = file.lines.count { it.startsWith("-") && !it.startsWith("---") }
    val overflow = file.lines.size > COLLAPSED_LINES
    val shown = when {
        expanded || !overflow -> file.lines
        // While streaming, the tail is the part being written.
        streaming -> file.lines.takeLast(COLLAPSED_LINES)
        else -> file.lines.take(COLLAPSED_LINES)
    }
    val palette = LocalPalette.current

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.panel2.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.panel2, RoundedCornerShape(6.dp, 6.dp, 0.dp, 0.dp))
                .clickable(enabled = overflow) { expanded = !expanded }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(file.verb, color = InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                file.path + (file.movedTo?.let { " → $it" } ?: ""),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                // Head ellipsis keeps the filename (the useful end) visible.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            if (adds > 0) {
                Text("+$adds", color = Ok, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (dels > 0) {
                Text("−$dels", color = Warn, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (overflow) {
                Text(
                    if (expanded) "collapse" else "expand",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            shown.forEach { line ->
                val bg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> palette.ok.copy(alpha = 0.10f)
                    line.startsWith("-") && !line.startsWith("---") -> palette.warn.copy(alpha = 0.09f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                val fg = when {
                    line.startsWith("+++") || line.startsWith("---") -> palette.inkDim
                    line.startsWith("@@") -> palette.accentDeep
                    line.startsWith("+") -> palette.ok
                    line.startsWith("-") -> palette.warn
                    else -> palette.inkDim
                }
                Text(
                    line.ifEmpty { " " },
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 9.dp),
                )
            }
            if (!expanded && overflow) {
                Text(
                    "… ${file.lines.size - COLLAPSED_LINES} more lines",
                    color = palette.accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        }
    }
}

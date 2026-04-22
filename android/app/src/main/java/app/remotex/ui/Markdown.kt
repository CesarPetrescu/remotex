package app.remotex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

/**
 * Tiny markdown renderer — just enough for Codex agent output.
 *
 * Handles (in order of tokenization):
 *   ``` fenced code blocks ```
 *   # / ## / ### headings
 *   - / * / 1. bullet or ordered lists
 *   **bold**, *italic*, `inline code` (inline spans)
 *   Regular paragraphs separated by blank lines.
 *
 * Any edge case we don't model falls through as plain text. Good enough
 * for chat replies; not trying to be a full CommonMark.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = Ink,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    trailingCursor: Boolean = false,
) {
    // Close obvious unclosed tokens during streaming so partial output
    // doesn't flicker plain → styled when the real closer arrives. If the
    // caller isn't streaming, `trailingCursor=false` skips this pass.
    val rendered = if (trailingCursor) balanceMarkdown(text) else text
    val blocks = remember(rendered) { parseBlocks(rendered) }
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Spacer(Modifier.height(6.dp))
            val isLast = idx == blocks.lastIndex
            when (block) {
                is Block.CodeFence -> CodeBlockView(block.content)
                is Block.Heading -> HeadingView(block.level, block.content, color)
                is Block.Bullet -> BulletView(block.items, color, fontSize)
                is Block.Paragraph -> Text(
                    inlineFormat(block.content, color),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                )
            }
            if (isLast && trailingCursor) {
                Text(
                    "▍",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                )
            }
        }
    }
}

/**
 * Close trailing unclosed tokens so partial markdown renders cleanly while
 * streaming. Mirrors Ember's `_balance_markdown`: triple-fence balance
 * across the buffer, then inline balance (backticks, then **) on the last
 * line. Single-* italic is intentionally not balanced — every stray `*`
 * would flicker italic.
 */
internal fun balanceMarkdown(s: String): String {
    val lines = s.split("\n").toMutableList()
    val openFences = lines.count { it.trimStart().startsWith("```") } % 2 == 1
    if (openFences) lines.add("```")
    val last = lines.last()
    val singleTicks = last.replace("```", "")
    var patched = last
    if (singleTicks.count { it == '`' } % 2 == 1) patched += "`"
    // count "**" occurrences
    var boldCount = 0
    var i = 0
    while (i < patched.length - 1) {
        if (patched[i] == '*' && patched[i + 1] == '*') { boldCount++; i += 2 } else i++
    }
    if (boldCount % 2 == 1) patched += "**"
    lines[lines.lastIndex] = patched
    return lines.joinToString("\n")
}

// --- block model --------------------------------------------------------

private sealed class Block {
    data class Paragraph(val content: String) : Block()
    data class Heading(val level: Int, val content: String) : Block()
    data class CodeFence(val content: String) : Block()
    data class Bullet(val items: List<String>) : Block()
}

private fun parseBlocks(text: String): List<Block> {
    val lines = text.split("\n")
    val out = mutableListOf<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // fenced code block
        if (line.trimStart().startsWith("```")) {
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            out.add(Block.CodeFence(buf.toString()))
            continue
        }
        // heading
        val hMatch = Regex("^(#{1,6})\\s+(.*)").find(line)
        if (hMatch != null) {
            out.add(Block.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2]))
            i++
            continue
        }
        // bullet list — consume consecutive bullet lines
        if (isBulletLine(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && isBulletLine(lines[i])) {
                items.add(stripBullet(lines[i]))
                i++
            }
            out.add(Block.Bullet(items))
            continue
        }
        // blank line separator
        if (line.isBlank()) { i++; continue }
        // paragraph — collect until blank or block change
        val buf = StringBuilder(line)
        i++
        while (i < lines.size
            && lines[i].isNotBlank()
            && !isBulletLine(lines[i])
            && !lines[i].trimStart().startsWith("```")
            && Regex("^#{1,6}\\s").find(lines[i]) == null
        ) {
            buf.append('\n').append(lines[i])
            i++
        }
        out.add(Block.Paragraph(buf.toString()))
    }
    return out
}

private fun isBulletLine(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("- ") || t.startsWith("* ") ||
        Regex("^\\d+\\.\\s").find(t) != null
}

private fun stripBullet(line: String): String {
    val t = line.trimStart()
    if (t.startsWith("- ") || t.startsWith("* ")) return t.substring(2)
    val m = Regex("^\\d+\\.\\s(.*)").find(t)
    return m?.groupValues?.get(1) ?: t
}

// --- inline formatting --------------------------------------------------

private fun inlineFormat(text: String, base: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // triple backtick handled at block level; here we handle single ` … `
        if (text[i] == '`') {
            val end = text.indexOf('`', startIndex = i + 1)
            if (end != -1) {
                withStyle(
                    SpanStyle(
                        color = Amber,
                        background = Color(0xFF1A1E26),
                        fontFamily = FontFamily.Monospace,
                    )
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", startIndex = i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = base)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' || text[i] == '_') {
            val marker = text[i]
            val end = text.indexOf(marker, startIndex = i + 1)
            if (end != -1 && end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = base)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

// --- block views --------------------------------------------------------

@Composable
private fun HeadingView(level: Int, text: String, color: Color) {
    val size = when (level) {
        1 -> 18.sp
        2 -> 16.sp
        3 -> 14.sp
        else -> 13.sp
    }
    Text(
        inlineFormat(text, color),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = size,
    )
}

@Composable
private fun BulletView(items: List<String>, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    Column {
        items.forEach { item ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text("• ", color = InkDim, fontFamily = FontFamily.Monospace, fontSize = fontSize)
                Text(
                    inlineFormat(item, color),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                )
            }
        }
    }
}

@Composable
private fun CodeBlockView(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            style = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

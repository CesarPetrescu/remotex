package app.remotex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import app.remotex.ui.theme.LocalPalette

/**
 * Deliberately small language-agnostic highlighter for fenced code.
 *
 * There is no highlight.js on Android and pulling a full grammar engine
 * for chat transcripts is not worth it, so this tokenises the four things
 * that actually aid reading — comments, strings, numbers, and a shared
 * keyword set across the languages Codex emits (Kotlin/Swift/JS/TS/Python/
 * Go/Rust/shell). Anything unrecognised stays body-coloured, which is the
 * correct failure mode.
 *
 * ponytail: no per-language grammars, no lexer generator. Add a language
 * hint only if a real transcript reads badly.
 */
private val KEYWORDS = setOf(
    // shared across the languages codex writes most
    "fun", "val", "var", "class", "object", "interface", "data", "return",
    "if", "else", "when", "for", "while", "do", "break", "continue", "in",
    "is", "as", "try", "catch", "finally", "throw", "import", "package",
    "private", "public", "internal", "protected", "override", "suspend",
    "const", "let", "function", "async", "await", "new", "typeof", "export",
    "default", "extends", "implements", "static", "void", "true", "false",
    "null", "nil", "None", "True", "False", "def", "lambda", "pass", "raise",
    "with", "yield", "elif", "not", "and", "or", "struct", "enum", "impl",
    "trait", "pub", "mut", "fn", "use", "match", "self", "this", "super",
    "type", "echo", "then", "fi", "esac", "case", "local", "export",
)

private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

@Composable
@ReadOnlyComposable
fun highlightCode(code: String): AnnotatedString {
    val palette = LocalPalette.current
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val c = code[i]

            // line comments: // # --
            val isLineComment = (c == '/' && i + 1 < code.length && code[i + 1] == '/') ||
                c == '#' ||
                (c == '-' && i + 1 < code.length && code[i + 1] == '-')
            if (isLineComment) {
                val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                withStyle(SpanStyle(color = palette.codeComment, fontStyle = FontStyle.Italic)) {
                    append(code.substring(i, end))
                }
                i = end
                continue
            }

            // block comments
            if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                val close = code.indexOf("*/", i + 2)
                val end = if (close == -1) code.length else close + 2
                withStyle(SpanStyle(color = palette.codeComment, fontStyle = FontStyle.Italic)) {
                    append(code.substring(i, end))
                }
                i = end
                continue
            }

            // strings (single, double, backtick); no escape handling beyond \\
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < code.length && code[j] != c) {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, code.length)
                withStyle(SpanStyle(color = palette.codeString)) {
                    append(code.substring(i, end))
                }
                i = end
                continue
            }

            // numbers
            if (c.isDigit()) {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == 'x')) j++
                withStyle(SpanStyle(color = palette.codeNumber)) {
                    append(code.substring(i, j))
                }
                i = j
                continue
            }

            // identifiers / keywords
            if (isIdentStart(c)) {
                var j = i
                while (j < code.length && isIdentPart(code[j])) j++
                val word = code.substring(i, j)
                if (word in KEYWORDS) {
                    withStyle(SpanStyle(color = palette.codeKeyword)) { append(word) }
                } else {
                    append(word)
                }
                i = j
                continue
            }

            append(c)
            i++
        }
    }
}

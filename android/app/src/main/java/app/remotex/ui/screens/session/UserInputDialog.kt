package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.UserInputPrompt
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Warn

/**
 * Modal that mirrors codex's TUI request_user_input overlay:
 * one question at a time with options + per-option notes, paged
 * with prev/next, "skip all" cancels the whole thing.
 *
 * Submitted answer shape:
 *   { questionId → ["selected label", "notes"] }
 * (skipped → ["skipped"])
 */
@Composable
fun UserInputDialog(
    prompt: UserInputPrompt,
    onSubmit: (Map<String, List<String>>) -> Unit,
    onCancel: () -> Unit,
    /** How many more user-input prompts are queued behind this one
     *  (contract F) — answering the head reveals the next. */
    queuedBehind: Int = 0,
) {
    val questions = prompt.questions
    if (questions.isEmpty()) return

    var page by rememberSaveable(prompt.callId) { mutableStateOf(0) }
    // Preserve per-(question, option) notes so switching options doesn't
    // erase what the user typed for the previous one.
    var selected by rememberSaveable(prompt.callId) {
        mutableStateOf(
            questions.associate { question ->
                question.id to (question.options.firstOrNull()?.label ?: "")
            },
        )
    }
    var notes by rememberSaveable(prompt.callId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    val current = questions[page]
    val isLast = page == questions.lastIndex
    val noteKey = "${current.id}|${selected[current.id].orEmpty()}"

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CODEX ASKS",
                    color = AccentDeep,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                if (questions.size > 1) {
                    Text(
                        "${page + 1} / ${questions.size}",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (queuedBehind > 0) {
                    Text(
                        "$queuedBehind more queued — answer this one to see the next",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                if (current.header.isNotBlank()) {
                    Text(
                        current.header,
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                if (current.question.isNotBlank()) {
                    Text(
                        current.question,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                current.options.forEach { opt ->
                    val isSelected = selected[current.id] == opt.label
                    Surface(
                        color = if (isSelected) Color(0x1A5EE1FF) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (isSelected) Amber else Line),
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { selected = selected + (current.id to opt.label) },
                            ),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(if (isSelected) Amber else InkDim),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    opt.label,
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                            if (opt.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    opt.description,
                                    color = InkDim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, Line),
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BasicTextField(
                        value = notes[noteKey].orEmpty(),
                        onValueChange = { notes = notes + (noteKey to it) },
                        textStyle = TextStyle(
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (current.isSecret) {
                                KeyboardType.Password
                            } else {
                                KeyboardType.Text
                            },
                        ),
                        visualTransformation = if (current.isSecret) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(8.dp)
                            .semantics {
                                contentDescription = if (current.isSecret) "Secret answer" else "Answer"
                            },
                        decorationBox = { inner ->
                            if (notes[noteKey].isNullOrEmpty()) {
                                Text(
                                    if (current.options.isNotEmpty())
                                        "optional notes for this choice"
                                    else
                                        "type your answer",
                                    color = InkDim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (questions.size > 1 && page > 0) {
                    TextButton(onClick = { page -= 1 }) {
                        Text("back", color = AccentDeep, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    if (isLast) {
                        onSubmit(buildAnswers(prompt, selected, notes))
                    } else {
                        page += 1
                    }
                }) {
                    Text(
                        if (isLast) "submit" else "next →",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(
                    "skip all",
                    color = Warn,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        },
    )
}

private fun buildAnswers(
    prompt: UserInputPrompt,
    selected: Map<String, String>,
    notes: Map<String, String>,
): Map<String, List<String>> {
    val out = mutableMapOf<String, List<String>>()
    for (q in prompt.questions) {
        val opt = selected[q.id].orEmpty()
        val key = "${q.id}|$opt"
        val text = notes[key].orEmpty().trim()
        val arr = buildList {
            if (opt.isNotEmpty()) add(opt)
            if (text.isNotEmpty()) add(text)
        }
        out[q.id] = if (arr.isEmpty()) listOf("skipped") else arr
    }
    return out
}

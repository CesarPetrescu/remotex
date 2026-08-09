package app.remotex.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim

/**
 * Relay base URL, editable at runtime. Committing (IME Done or focus
 * loss) rebuilds the app's networking against the new relay — edits are
 * NOT applied per keystroke, since the URL keys the whole ViewModel.
 */
@Composable
fun RelayUrlField(value: String, onCommit: (String) -> Unit) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    fun commit() {
        val next = draft.trim()
        if (next.isNotBlank() && next != value) onCommit(next)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "RELAY URL",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commit() },
            )
        }
    }
}

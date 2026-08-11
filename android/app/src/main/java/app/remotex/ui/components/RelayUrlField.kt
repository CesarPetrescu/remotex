package app.remotex.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import app.remotex.BuildConfig
import app.remotex.net.normalizeRelayBaseUrl

/** Commits a validated relay address only after editing finishes. */
@Composable
fun RelayUrlField(
    value: String,
    onCommit: (String) -> Unit,
    onReadyChange: (Boolean) -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    var validationError by rememberSaveable(value) { mutableStateOf<String?>(null) }
    var wasFocused by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commit(): Boolean {
        return normalizeRelayBaseUrl(draft, BuildConfig.DEBUG)
            .fold(
                onSuccess = { next ->
                    validationError = null
                    onReadyChange(next == value)
                    if (next != value) onCommit(next)
                    true
                },
                onFailure = {
                    validationError = it.message ?: "Enter a valid relay address."
                    onReadyChange(false)
                    false
                },
            )
    }

    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            validationError = null
            val normalized = normalizeRelayBaseUrl(it, BuildConfig.DEBUG).getOrNull()
            onReadyChange(normalized != null && normalized == value)
        },
        label = { Text("Relay address") },
        placeholder = { Text("https://relay.example.com") },
        supportingText = {
            Text(validationError ?: "Use the HTTPS URL supplied by your relay administrator.")
        },
        enabled = enabled,
        isError = validationError != null,
        singleLine = true,
        shape = RectangleShape,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                if (commit()) focusManager.moveFocus(FocusDirection.Next)
            },
        ),
        modifier = modifier
            .semantics { contentDescription = "Relay address" }
            .onFocusChanged {
                if (it.isFocused) wasFocused = true
                else if (wasFocused) commit()
            },
    )
}

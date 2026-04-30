package app.remotex.ui.screens.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.remotex.ui.PermissionsMode
import app.remotex.ui.Status
import app.remotex.ui.UiState
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.screens.session.events.EventList

@Composable
fun SessionScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPermissionsChange: (PermissionsMode) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MetaBar(state)
        if (state.resuming) {
            ResumingBanner(sinceMs = state.resumingSinceMs)
        }
        EventList(
            events = state.events,
            pending = state.pending,
            connected = state.status == Status.Connected,
            modifier = Modifier.weight(1f, fill = true),
        )
        ComposerBar(
            // Block sends while we're still resuming — daemon would
            // reject anyway, this just makes the disabled state clear.
            connected = state.status == Status.Connected && !state.resuming,
            pending = state.pending,
            model = state.model,
            effort = state.effort,
            permissions = state.permissions,
            planMode = state.planMode,
            pendingImages = state.pendingImages,
            modelOptions = state.modelOptions,
            onModelChange = onModelChange,
            onEffortChange = onEffortChange,
            onPermissionsChange = onPermissionsChange,
            onSend = onSend,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
        )
    }
}

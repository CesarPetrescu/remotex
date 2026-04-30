package app.remotex.ui.screens.session.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.UiEvent
import app.remotex.ui.theme.InkDim

private data class EventGroup(val kind: Kind, val events: List<UiEvent>) {
    enum class Kind { USER, AGENT }
}

private fun groupUiEvents(events: List<UiEvent>): List<EventGroup> {
    val out = mutableListOf<EventGroup>()
    for (e in events) {
        if (e is UiEvent.User) {
            out.add(EventGroup(EventGroup.Kind.USER, listOf(e)))
            continue
        }
        val last = out.lastOrNull()
        if (last != null && last.kind == EventGroup.Kind.AGENT) {
            out[out.lastIndex] = last.copy(events = last.events + e)
        } else {
            out.add(EventGroup(EventGroup.Kind.AGENT, listOf(e)))
        }
    }
    return out
}

@Composable
internal fun EventList(
    events: List<UiEvent>,
    pending: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (connected) "send a prompt to start…" else "connecting…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        return
    }
    val groups = remember(events) { groupUiEvents(events) }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        items(groups, key = { it.events.first().id }) { group ->
            when (group.kind) {
                EventGroup.Kind.USER -> UserBubble(group.events.first() as UiEvent.User)
                EventGroup.Kind.AGENT -> AgentGroup(group.events, pending)
            }
        }
    }
}

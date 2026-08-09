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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.UiEvent
import app.remotex.ui.theme.InkDim

private data class EventGroup(val kind: Kind, val events: List<UiEvent>) {
    enum class Kind { USER, AGENT, GAP }
}

private fun groupUiEvents(events: List<UiEvent>): List<EventGroup> {
    val out = mutableListOf<EventGroup>()
    for (e in events) {
        if (e is UiEvent.User) {
            out.add(EventGroup(EventGroup.Kind.USER, listOf(e)))
            continue
        }
        // A replay gap is a marker about the transcript itself, not
        // something the agent said — never fold it into an agent group.
        if (e is UiEvent.Gap) {
            out.add(EventGroup(EventGroup.Kind.GAP, listOf(e)))
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
    historyHasMore: Boolean = false,
    historyLoading: Boolean = false,
    historyTailTick: Long = 0L,
    onLoadOlder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // A committed history tail jumps to the newest exchange exactly once.
    LaunchedEffect(historyTailTick) {
        if (events.isNotEmpty()) listState.scrollToItem(Int.MAX_VALUE / 2)
    }
    // Live events follow the tail only while the user is already there —
    // reading old turns must never get yanked back down. Prepended history
    // pages keep their anchor for free: LazyColumn pins to item keys.
    LaunchedEffect(events.size) {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= info.totalItemsCount - 2
        if (events.isNotEmpty() && nearBottom) {
            listState.animateScrollToItem(Int.MAX_VALUE / 2)
        }
    }
    // Scroll-to-top backfill: when the loader row (or first group) becomes
    // visible, ask for the previous page. The ViewModel guards re-entry.
    LaunchedEffect(listState, historyHasMore) {
        if (!historyHasMore) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first -> if (first <= 0) onLoadOlder() }
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
        if (historyHasMore) {
            item(key = "history-loader") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (historyLoading) "loading older turns…" else "older turns load as you scroll",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        items(groups, key = { it.events.first().id }) { group ->
            when (group.kind) {
                EventGroup.Kind.USER -> UserBubble(group.events.first() as UiEvent.User)
                EventGroup.Kind.GAP -> GapMarker(group.events.first() as UiEvent.Gap)
                EventGroup.Kind.AGENT -> AgentGroup(group.events, pending)
            }
        }
    }
}

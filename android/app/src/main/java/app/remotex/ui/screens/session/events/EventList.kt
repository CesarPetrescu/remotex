package app.remotex.ui.screens.session.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.LocalPalette
import kotlinx.coroutines.launch
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
    historyCount: Int = 0,
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
        if (connected) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "send a prompt to start…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        } else {
            // Attaching with nothing to show yet: skeleton exchange
            // instead of a bare "connecting…" caption (mirrors web).
            SkeletonTranscript(modifier)
        }
        return
    }
    val groups = remember(events) { groupUiEvents(events) }
    // "new messages" divider: history events sit at the front of the list,
    // so the group that crosses historyCount is the boundary.
    val liveBoundary = remember(groups, historyCount) {
        if (historyCount <= 0) return@remember -1
        var seen = 0
        var boundary = -1
        for ((index, group) in groups.withIndex()) {
            if (seen >= historyCount) { boundary = index; break }
            seen += group.events.size
        }
        boundary
    }
    Box(modifier) {
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
        itemsIndexed(groups, key = { _, g -> g.events.first().id }) { index, group ->
            if (index == liveBoundary) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "── new messages ──",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
            when (group.kind) {
                EventGroup.Kind.USER -> UserBubble(group.events.first() as UiEvent.User)
                EventGroup.Kind.GAP -> GapMarker(group.events.first() as UiEvent.Gap)
                EventGroup.Kind.AGENT -> AgentGroup(group.events, pending)
            }
        }
    }

    // Jump-to-latest: only while scrolled away from the tail.
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    if (!atTail && events.isNotEmpty()) {
        val scope = rememberCoroutineScope()
        Surface(
            color = LocalPalette.current.panel,
            shape = androidx.compose.foundation.shape.CircleShape,
            border = BorderStroke(1.dp, Line),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(44.dp)
                .clickable {
                    scope.launch { listState.animateScrollToItem(Int.MAX_VALUE / 2) }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("↓", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                if (pending) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(7.dp)
                            .background(LocalPalette.current.ok, androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
    }
    }
}

// Pulsing placeholder shaped like a real exchange: left-aligned agent
// lines with right-aligned user bubbles. Infinite transitions are
// test-friendly (the compose test clock skips them).
@Composable
private fun SkeletonTranscript(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("session-skeleton"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        data class SkelRow(val widthFraction: Float, val heightDp: Int, val user: Boolean)
        val rows = listOf(
            SkelRow(0.35f, 36, true),
            SkelRow(0.9f, 12, false),
            SkelRow(0.72f, 12, false),
            SkelRow(0.55f, 12, false),
            SkelRow(0.28f, 36, true),
            SkelRow(0.85f, 12, false),
            SkelRow(0.6f, 12, false),
        )
        rows.forEach { row ->
            Box(
                Modifier
                    .fillMaxWidth(row.widthFraction)
                    .height(row.heightDp.dp)
                    .align(if (row.user) Alignment.End else Alignment.Start)
                    .graphicsLayer { alpha = pulse }
                    .background(Line),
            )
        }
    }
}

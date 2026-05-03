package app.remotex.ui.screens.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.OrchAgentTranscript
import app.remotex.model.OrchStep
import app.remotex.model.OrchSubagentEvent
import app.remotex.model.OrchestratorState
import app.remotex.ui.screens.session.events.EventList
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok
import app.remotex.ui.theme.Warn

private val Accent = AccentDeep
private val OK = Ok

/** Renders the orchestrator's plan DAG with status pills + a live
 *  block under each running step (current label + last ~800 chars of
 *  streaming agent text). Mirrors apps/web/src/components/PlanTree.jsx.
 *
 *  Collapsible: the header is a tap-target. State is local to this
 *  composable since collapsing is just a viewing preference. Auto-
 *  expands when a step starts running so the live block isn't hidden
 *  behind a closed header. */
@Composable
fun PlanTreePanel(orchestrator: OrchestratorState, modifier: Modifier = Modifier) {
    val steps = orchestrator.steps
    val running = steps.count { it.status == "running" }
    val done = steps.count { it.status == "completed" }
    val failed = steps.count { it.status == "failed" || it.status == "cancelled" }
    var collapsed by remember { mutableStateOf(false) }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    val selectedAgent = selectedAgentId?.let { orchestrator.agents[it] }
    LaunchedEffect(running) {
        if (running > 0) collapsed = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, Line, RectangleShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectedAgent != null) {
            AgentTranscriptPanel(
                agent = selectedAgent,
                agents = orchestrator.agents,
                onBack = { selectedAgentId = null },
                onSelectAgent = { selectedAgentId = it },
            )
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (collapsed) "▸" else "▾",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text(
                "PLAN",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildHeaderSummary(
                    steps.size,
                    running,
                    done,
                    failed,
                    orchestrator.finished,
                    orchestrator.brainSubagents.size,
                ),
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (!collapsed) {
            if (orchestrator.brainSubagents.isNotEmpty()) {
                BrainSubagentsRow(
                    events = orchestrator.brainSubagents,
                    onOpenBrain = { selectedAgentId = "brain" },
                    onSelectAgent = { selectedAgentId = it },
                )
            }
            if (steps.isEmpty() && orchestrator.brainSubagents.isEmpty()) {
                Text(
                    "waiting for the orchestrator to submit a plan…",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            } else {
                steps.forEach { step ->
                    PlanStepRow(
                        step = step,
                        onOpen = { selectedAgentId = "step:${step.stepId}" },
                        onSelectAgent = { selectedAgentId = it },
                    )
                }
            }
            orchestrator.finished?.let { f ->
                val color = if (f.ok) OK else Warn
                val text = if (f.ok) "✓ ${f.summary ?: "orchestration complete"}"
                           else "✗ ${f.error ?: "orchestrator stopped"}"
                Text(
                    text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.06f))
                        .padding(6.dp),
                )
            }
        }
    }
}

private fun buildHeaderSummary(
    total: Int,
    running: Int,
    done: Int,
    failed: Int,
    finished: app.remotex.model.OrchFinished?,
    brainAgents: Int,
): String {
    if (total == 0 && finished == null && brainAgents == 0) return "waiting…"
    val parts = mutableListOf<String>()
    if (total > 0) parts += "$total ${if (total == 1) "step" else "steps"}"
    if (total == 0 && brainAgents > 0) parts += "$brainAgents native ${if (brainAgents == 1) "call" else "calls"}"
    if (running > 0) parts += "$running running"
    if (done > 0) parts += "$done done"
    if (failed > 0) parts += "$failed failed"
    if (finished != null) parts += if (finished.ok) "✓ done" else "✗ stopped"
    return parts.joinToString(" · ")
}

@Composable
private fun PlanStepRow(
    step: OrchStep,
    onOpen: () -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    val accent: Color = when (step.status) {
        "running" -> Accent
        "completed" -> OK
        "failed" -> Warn
        "cancelled" -> InkDim
        else -> InkDim
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.dp, color = Color.Transparent, shape = RectangleShape)
            .padding(start = 8.dp)
            .background(if (step.status == "running") Accent.copy(alpha = 0.05f) else Color.Transparent)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    ) {
        Row(
            modifier = Modifier.clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(accent)
            )
            Text(
                step.stepId,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text(
                step.title.ifBlank { step.stepId },
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp).run { this },
            )
            Spacer(Modifier.weight(1f))
            Text(
                statusLabel(step.status),
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (step.deps.isNotEmpty()) {
            Text(
                "← ${step.deps.joinToString(", ")}",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 11.dp, top = 2.dp),
            )
        }
        if (step.status == "running" && step.live != null && (step.live.label != null || step.live.text.isNotEmpty())) {
            LiveBlock(label = step.live.label, text = step.live.text)
        }
        if (step.live?.subagents?.isNotEmpty() == true) {
            SubagentTrace(step.live.subagents, onSelectAgent)
        }
        if (step.summary.isNotEmpty()) {
            val summary = if (step.summary.length > 240) step.summary.take(240) + "…" else step.summary
            Text(
                summary,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 11.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun BrainSubagentsRow(
    events: List<OrchSubagentEvent>,
    onOpenBrain: () -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .background(Amber.copy(alpha = 0.05f))
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    ) {
        Row(
            modifier = Modifier.clickable { onOpenBrain() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(Amber)
            )
            Text(
                "brain",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Text(
                "native subagents",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                events.size.toString(),
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        SubagentTrace(events, onSelectAgent)
    }
}

@Composable
private fun AgentTranscriptPanel(
    agent: OrchAgentTranscript,
    agents: Map<String, OrchAgentTranscript>,
    onBack: () -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    val children = agents.values.filter { it.parentAgentId == agent.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "‹ PLAN",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.clickable { onBack() },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    agent.label.ifBlank { agent.id },
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    agent.status.uppercase() + (agent.threadId?.let { " · ${it.shortThread()}" } ?: ""),
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        }
        if (children.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                children.forEach { child ->
                    Text(
                        child.label.ifBlank { child.id.shortThread() },
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .border(1.dp, Line, RectangleShape)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable { onSelectAgent(child.id) },
                    )
                }
            }
        }
        if (agent.events.isEmpty()) {
            Text(
                "no transcript events yet",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        } else {
            EventList(
                events = agent.events,
                pending = agent.status == "running",
                connected = true,
                modifier = Modifier.heightIn(max = 260.dp),
            )
        }
    }
}

@Composable
private fun LiveBlock(label: String?, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 11.dp, top = 6.dp)
            .background(Accent.copy(alpha = 0.05f))
            .border(0.dp, Color.Transparent)
            .padding(6.dp),
    ) {
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot()
                Text(
                    label.uppercase(),
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (text.isNotEmpty()) {
            // Cap height so a noisy step doesn't push the whole panel
            // off-screen; auto-scrolls with content as new tokens arrive.
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 96.dp)
                    .padding(top = 4.dp)
                    .verticalScroll(scroll),
            ) {
                Text(
                    text,
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}

@Composable
private fun SubagentTrace(events: List<OrchSubagentEvent>, onSelectAgent: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 11.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        events.forEach { event ->
            SubagentRow(event, onSelectAgent)
        }
    }
}

@Composable
private fun SubagentRow(event: OrchSubagentEvent, onSelectAgent: (String) -> Unit) {
    val color = subagentColor(event.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((event.depth - 1).coerceAtLeast(0) * 14).dp)
            .border(1.dp, Line, RectangleShape)
            .background(Color.White.copy(alpha = 0.018f))
            .clickable(enabled = event.agentId.isNotBlank()) { onSelectAgent(event.agentId) }
            .padding(5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 22.dp)
                .background(color)
        )
        Text(
            subagentStatusLabel(event.status).uppercase(),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.label.ifBlank { formatSubagentTool(event.tool) }.uppercase(),
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
            )
            if (event.prompt.isNotBlank()) {
                Text(
                    event.prompt.truncateForPlan(96),
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            if (event.agentStates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    event.agentStates.forEach { state ->
                        Text(
                            "${state.threadId.shortThread()} ${state.status}" +
                                if (state.message.isNotBlank()) ": ${state.message.truncateForPlan(60)}" else "",
                            color = subagentColor(state.status),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
        val meta = event.model.ifBlank { event.receiverThreadIds.firstOrNull()?.shortThread() ?: "" }
        if (meta.isNotBlank()) {
            Text(
                meta,
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun PulseDot() {
    val transition = rememberInfiniteTransition(label = "plan-step-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "plan-step-pulse-alpha",
    )
    Box(
        Modifier
            .size(6.dp)
            .alpha(alpha)
            .background(Accent, shape = CircleShape),
    )
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "QUEUED"
    "running" -> "RUNNING"
    "completed" -> "DONE"
    "failed" -> "FAILED"
    "cancelled" -> "CANCELLED"
    else -> status.uppercase()
}

private fun formatSubagentTool(tool: String): String = when (tool) {
    "spawnAgent" -> "spawn agent"
    "sendInput" -> "send input"
    "resumeAgent" -> "resume agent"
    "wait" -> "wait agents"
    "closeAgent" -> "close agent"
    else -> tool.ifBlank { "subagent" }
}

private fun subagentStatusLabel(status: String): String = when (status) {
    "completed" -> "done"
    "failed", "errored" -> "fail"
    "shutdown" -> "closed"
    "running", "inProgress", "pendingInit" -> "run"
    else -> status.ifBlank { "run" }
}

private fun subagentColor(status: String): Color = when (status) {
    "completed" -> OK
    "failed", "errored" -> Warn
    "shutdown" -> InkDim
    else -> Accent
}

private fun String.shortThread(): String =
    if (length > 8) take(8) else this

private fun String.truncateForPlan(max: Int): String =
    if (length <= max) this else take(max) + "…"

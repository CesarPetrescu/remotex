package app.remotex.ui.screens.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.OrchStep
import app.remotex.model.OrchestratorState
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
 *  streaming agent text). Mirrors apps/web/src/components/PlanTree.jsx. */
@Composable
fun PlanTreePanel(orchestrator: OrchestratorState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, Line, RectangleShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "PLAN",
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        if (orchestrator.steps.isEmpty()) {
            Text(
                "waiting for the orchestrator to submit a plan…",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        } else {
            orchestrator.steps.forEach { step ->
                PlanStepRow(step)
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

@Composable
private fun PlanStepRow(step: OrchStep) {
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
        // Left edge bar — accent color cues status without taking row space.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

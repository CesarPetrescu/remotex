package app.remotex.ui.screens.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.OrchestratorDraft
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Warn

private val PERMISSION_OPTIONS = listOf("readonly", "default", "full")
private val APPROVAL_OPTIONS = listOf("never", "on-failure", "on-request")
private val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh")

/** Modal collected before POST /api/sessions for kind=orchestrator.
 *  The brain inherits these on its first turn; we can't sneak them in
 *  later without restarting the brain. Mirrors apps/web/src/components/
 *  OrchestratorLauncher.jsx. */
@Composable
fun OrchestratorLauncherDialog(
    draft: OrchestratorDraft,
    modelOptions: List<String>,
    onUpdate: ((OrchestratorDraft) -> OrchestratorDraft) -> Unit,
    onCancel: () -> Unit,
    onLaunch: (OrchestratorDraft) -> Unit,
) {
    val canLaunch = draft.task.isNotBlank()
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                onClick = { if (canLaunch) onLaunch(draft) },
                enabled = canLaunch,
            ) {
                Text("Launch", color = if (canLaunch) Amber else InkDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = InkDim) }
        },
        title = {
            Text(
                "Orchestrate a long task",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This opens an orchestrator brain, not a coder. It uses " +
                        "orchestrator MCP tools to plan a DAG, delegate concrete " +
                        "work to child Codex agents, await them, and synthesize " +
                        "the final result.",
                    color = InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                if (!draft.cwd.isNullOrBlank()) {
                    Text(
                        "cwd: ${draft.cwd}",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }

                FieldLabel("Task")
                MultilineField(
                    value = draft.task,
                    placeholder = "audit auth module for OWASP top-10, file an issue per finding",
                    onValueChange = { v -> onUpdate { it.copy(task = v) } },
                )

                FieldLabel("Model")
                ChipRow(
                    options = modelOptions.ifEmpty { listOf("") },
                    selected = draft.model,
                    onSelect = { v -> onUpdate { it.copy(model = v) } },
                    labelFor = { it.ifEmpty { "default" } },
                )

                FieldLabel("Effort")
                ChipRow(
                    options = EFFORT_OPTIONS,
                    selected = draft.effort,
                    onSelect = { v -> onUpdate { it.copy(effort = v) } },
                )

                FieldLabel("Permissions (children)")
                ChipRow(
                    options = PERMISSION_OPTIONS,
                    selected = draft.childPermissions,
                    onSelect = { v -> onUpdate { it.copy(childPermissions = v) } },
                )

                FieldLabel("Approval policy")
                ChipRow(
                    options = APPROVAL_OPTIONS,
                    selected = draft.approvalPolicy,
                    onSelect = { v -> onUpdate { it.copy(approvalPolicy = v) } },
                )
            }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        color = InkDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MultilineField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 140.dp)
            .border(1.dp, Line, RectangleShape)
            .padding(8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                color = InkDim.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(Amber),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    labelFor: (String) -> String = { it },
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { value ->
            val isSelected = value == selected
            Box(
                Modifier
                    .border(1.dp, if (isSelected) Amber else Line, RectangleShape)
                    .background(if (isSelected) Amber.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    labelFor(value),
                    color = if (isSelected) Amber else Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

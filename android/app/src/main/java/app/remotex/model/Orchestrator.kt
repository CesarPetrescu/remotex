package app.remotex.model

/** One durable native-Codex subagent activity row. The orchestrator
 *  backend streams these as collab_agent_tool_call items; clients
 *  derive depth from sender/receiver thread ids so child and
 *  grandchild agents stay visible in the plan tab. */
data class OrchSubagentEvent(
    val id: String,
    val agentId: String = "",
    val kind: String = "",
    val tool: String = "",
    val label: String = "",
    val status: String = "inProgress",
    val prompt: String = "",
    val model: String = "",
    val reasoningEffort: String = "",
    val senderThreadId: String = "",
    val receiverThreadIds: List<String> = emptyList(),
    val agentStates: List<OrchSubagentState> = emptyList(),
    val depth: Int = 1,
)

data class OrchSubagentState(
    val threadId: String,
    val status: String = "running",
    val message: String = "",
    val depth: Int = 1,
)

/** Live progress block under one running orchestrator step. Mirrors
 *  the web reducer's `live` object — a bounded streaming text buffer
 *  plus the current "what's running" label. */
data class OrchStepLive(
    val text: String = "",
    val label: String? = null,
    val itemId: String? = null,
    val itemType: String? = null,
    val completed: Boolean = false,
    val subagents: List<OrchSubagentEvent> = emptyList(),
)

/** One node in the orchestrator's plan DAG. Status transitions:
 *  pending → running → (completed | failed | cancelled). */
data class OrchStep(
    val stepId: String,
    val title: String = "",
    val deps: List<String> = emptyList(),
    val status: String = "pending",
    val summary: String = "",
    val childSessionId: String? = null,
    val live: OrchStepLive? = null,
)

data class OrchFinished(
    val ok: Boolean,
    val summary: String? = null,
    val error: String? = null,
)

data class OrchAgentTranscript(
    val id: String,
    val label: String = "",
    val parentAgentId: String? = null,
    val stepId: String? = null,
    val threadId: String? = null,
    val depth: Int = 0,
    val status: String = "running",
    val events: List<UiEvent> = emptyList(),
)

/** Orchestrator-only sub-state pinned to the current session. Empty
 *  for plain coder sessions. */
data class OrchestratorState(
    val active: Boolean = false,
    val steps: List<OrchStep> = emptyList(),
    val finished: OrchFinished? = null,
    val brainSubagents: List<OrchSubagentEvent> = emptyList(),
    val agents: Map<String, OrchAgentTranscript> = emptyMap(),
)

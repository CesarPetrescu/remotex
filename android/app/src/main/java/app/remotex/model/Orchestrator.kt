package app.remotex.model

/** Live progress block under one running orchestrator step. Mirrors
 *  the web reducer's `live` object — a bounded streaming text buffer
 *  plus the current "what's running" label. */
data class OrchStepLive(
    val text: String = "",
    val label: String? = null,
    val itemId: String? = null,
    val itemType: String? = null,
    val completed: Boolean = false,
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

/** Orchestrator-only sub-state pinned to the current session. Empty
 *  for plain coder sessions. */
data class OrchestratorState(
    val active: Boolean = false,
    val steps: List<OrchStep> = emptyList(),
    val finished: OrchFinished? = null,
)

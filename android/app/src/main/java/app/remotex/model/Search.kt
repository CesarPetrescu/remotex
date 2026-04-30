package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val id: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    val kind: String,
    val role: String,
    val snippet: String = "",
    val text: String = "",
    val cwd: String? = null,
    val model: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val score: Double = 0.0,
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
)

/**
 * One step in the relay's semantic-search pipeline. Mirrors the web
 * client's searchStages so both clients render the same vertical
 * progress list while a search is running.
 *
 * Status values used by the relay's stream:
 *   pending  — declared in the initial `plan` event, not started yet
 *   running  — work is in flight
 *   done     — finished, has elapsedMs + count
 *   error    — failed mid-pipeline (only used for `rerank`)
 *   skipped  — declared in plan but not run (e.g. rerank disabled)
 */
data class SearchStage(
    val name: String,
    val status: String = "pending",
    val elapsedMs: Long? = null,
    val count: Int? = null,
    val error: String? = null,
)

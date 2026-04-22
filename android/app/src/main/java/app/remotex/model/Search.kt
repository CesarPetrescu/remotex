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

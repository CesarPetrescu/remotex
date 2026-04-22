package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreadInfo(
    val id: String,
    val preview: String = "",
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val cwd: String? = null,
    val ephemeral: Boolean = false,
)

@Serializable
data class ThreadsResponse(
    @SerialName("host_id") val hostId: String,
    val threads: List<ThreadInfo>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

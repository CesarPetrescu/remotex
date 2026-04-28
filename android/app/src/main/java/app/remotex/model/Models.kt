package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Host(
    val id: String,
    val nickname: String,
    val hostname: String? = null,
    val platform: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long? = null,
)

@Serializable
data class HostsResponse(val hosts: List<Host>)

@Serializable
data class OpenSessionRequest(@SerialName("host_id") val hostId: String)

@Serializable
data class OpenSessionResponse(@SerialName("session_id") val sessionId: String)

@Serializable
data class ModelInfo(
    val id: String,
    val label: String,
    val hint: String = "",
    val efforts: List<String> = emptyList(),
)

@Serializable
data class ModelsResponse(
    val models: List<ModelInfo> = emptyList(),
    val efforts: List<String> = emptyList(),
)

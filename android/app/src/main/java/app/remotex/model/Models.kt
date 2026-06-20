package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Host(
    val id: String,
    val nickname: String,
    val hostname: String? = null,
    val platform: String? = null,
    @SerialName("os_user") val osUser: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long? = null,
    @SerialName("home_dir") val homeDir: String? = null,
    @SerialName("default_cwd") val defaultCwd: String? = null,
)

@Serializable
data class HostsResponse(val hosts: List<Host>)

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

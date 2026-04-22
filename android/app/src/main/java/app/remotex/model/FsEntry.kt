package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FsEntry(
    val fileName: String,
    @SerialName("isDirectory") val isDirectory: Boolean = false,
    @SerialName("isFile") val isFile: Boolean = false,
)

@Serializable
data class FsListResponse(
    @SerialName("host_id") val hostId: String,
    val path: String,
    val entries: List<FsEntry>,
)

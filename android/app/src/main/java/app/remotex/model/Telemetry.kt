package app.remotex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shape for /api/hosts/{id}/telemetry. Every numeric field is
// nullable because the daemon omits what it can't measure (e.g. GPU
// block absent on non-NVIDIA machines; temperature unreadable inside
// containers).

@Serializable
data class HostTelemetryResponse(
    @SerialName("host_id") val hostId: String,
    val data: HostTelemetryData? = null,
    val ts: Double? = null,
)

@Serializable
data class HostTelemetryData(
    val cpu: CpuTelemetry? = null,
    val memory: MemoryTelemetry? = null,
    // New: full list of attached GPUs (empty when nvidia-smi absent).
    // `gpu` is the first item, kept for older relays/web clients.
    val gpus: List<GpuTelemetry> = emptyList(),
    val gpu: GpuTelemetry? = null,
    val network: NetworkTelemetry? = null,
    @SerialName("uptime_s") val uptimeS: Long? = null,
    @SerialName("load_avg") val loadAvg: List<Double>? = null,
    val ts: Double? = null,
)

@Serializable
data class CpuTelemetry(
    val percent: Double? = null,
    val cores: Int? = null,
    @SerialName("temp_c") val tempC: Double? = null,
)

@Serializable
data class MemoryTelemetry(
    @SerialName("used_bytes") val usedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    val percent: Double? = null,
)

@Serializable
data class GpuTelemetry(
    val name: String? = null,
    val percent: Double? = null,
    @SerialName("mem_used_mb") val memUsedMb: Double? = null,
    @SerialName("mem_total_mb") val memTotalMb: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
)

@Serializable
data class NetworkTelemetry(
    @SerialName("up_bps") val upBps: Long? = null,
    @SerialName("down_bps") val downBps: Long? = null,
)

/** Ring-buffered client-side history for sparklines. */
data class TelemetryHistory(
    val cpu: List<Float> = emptyList(),
    val mem: List<Float> = emptyList(),
    val gpu: List<Float> = emptyList(),
    val up: List<Float> = emptyList(),
    val down: List<Float> = emptyList(),
) {
    fun push(data: HostTelemetryData): TelemetryHistory = TelemetryHistory(
        cpu = pushSample(cpu, (data.cpu?.percent ?: 0.0).toFloat()),
        mem = pushSample(mem, (data.memory?.percent ?: 0.0).toFloat()),
        gpu = pushSample(gpu, (data.gpu?.percent ?: 0.0).toFloat()),
        up = pushSample(up, (data.network?.upBps ?: 0L).toFloat()),
        down = pushSample(down, (data.network?.downBps ?: 0L).toFloat()),
    )

    companion object {
        private const val MAX = 60
        private fun pushSample(list: List<Float>, v: Float): List<Float> {
            val trimmed = if (list.size >= MAX) list.drop(list.size - MAX + 1) else list
            return trimmed + v
        }
    }
}

data class HostTelemetrySnapshot(
    val data: HostTelemetryData,
    val history: TelemetryHistory,
    val lastUpdateMs: Long,
)

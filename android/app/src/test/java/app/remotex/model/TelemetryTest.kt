package app.remotex.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryTest {
    @Test
    fun relayRingBuildsOneOldestFirstSeriesPerGpu() {
        val history = TelemetryHistory.fromRelay(
            listOf(
                sample(ageMs = 3_000, gpuA = 10.0, gpuB = 20.0),
                sample(ageMs = 0, gpuA = 30.0, gpuB = 40.0),
            ),
        )

        assertEquals(listOf(10f, 30f), history.gpus[0])
        assertEquals(listOf(20f, 40f), history.gpus[1])
        assertEquals(history.gpus[0], history.gpu)
    }

    @Test
    fun oldAndOversizedSamplesAreDropped() {
        val samples = (15 downTo 0).map { index ->
            sample(ageMs = index * 3_000L, gpuA = index.toDouble(), gpuB = 0.0)
        }

        val history = TelemetryHistory.fromRelay(samples)

        assertEquals(11, history.cpu.size)
        assertEquals(30f, history.cpu.first())
        assertEquals(0f, history.cpu.last())
    }

    @Test
    fun legacySingleGpuStillFeedsThePrimarySeries() {
        val history = TelemetryHistory().push(
            HostTelemetryData(gpu = GpuTelemetry(name = "legacy", percent = 12.0)),
        )

        assertEquals(listOf(12f), history.gpu)
        assertEquals(listOf(listOf(12f)), history.gpus)
    }

    private fun sample(ageMs: Long, gpuA: Double, gpuB: Double) = HostTelemetrySample(
        ageMs = ageMs,
        data = HostTelemetryData(
            cpu = CpuTelemetry(percent = ageMs / 1_000.0),
            gpus = listOf(
                GpuTelemetry(name = "A", percent = gpuA),
                GpuTelemetry(name = "B", percent = gpuB),
            ),
        ),
    )
}

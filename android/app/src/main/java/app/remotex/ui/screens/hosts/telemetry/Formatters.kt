package app.remotex.ui.screens.hosts.telemetry

internal fun formatPct(p: Double): String =
    if (p >= 10.0) p.toInt().toString() else String.format("%.1f", p)

internal fun fmtMemoryShort(used: Long?, total: Long?): String {
    if (used == null || total == null) return ""
    val gbU = used / 1024.0 / 1024.0 / 1024.0
    val gbT = total / 1024.0 / 1024.0 / 1024.0
    return String.format("%.1f / %.1f GB", gbU, gbT)
}

internal fun fmtBps(bps: Long?): String {
    val v = bps ?: 0L
    return when {
        v >= 1_000_000_000 -> String.format("%.1f Gbps", v / 1_000_000_000.0)
        v >= 1_000_000 -> String.format("%.1f Mbps", v / 1_000_000.0)
        v >= 1_000 -> String.format("%.1f kbps", v / 1_000.0)
        else -> "$v bps"
    }
}

internal fun fmtMegabytes(mb: Double?): String {
    if (mb == null) return "—"
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0) else "${mb.toInt()} MB"
}

internal fun fmtUptime(s: Long?): String {
    if (s == null || s <= 0) return "—"
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

internal fun formatPctLabel(value: Float): String =
    if (value >= 10f) "${value.toInt()}%" else "${String.format("%.1f", value)}%"

internal fun summarizeSeries(points: List<Float>, formatter: (Float) -> String): List<Pair<String, String>> {
    if (points.isEmpty()) return emptyList()
    return listOf(
        "NOW" to formatter(points.last()),
        "PEAK" to formatter(points.maxOrNull() ?: 0f),
        "FLOOR" to formatter(points.minOrNull() ?: 0f),
    )
}

internal fun summarizeNetwork(up: List<Float>, down: List<Float>): List<Pair<String, String>> {
    return listOf(
        "UP PEAK" to fmtBps((up.maxOrNull() ?: 0f).toLong()),
        "DOWN PEAK" to fmtBps((down.maxOrNull() ?: 0f).toLong()),
        "LIVE SUM" to fmtBps(((up.lastOrNull() ?: 0f) + (down.lastOrNull() ?: 0f)).toLong()),
    )
}

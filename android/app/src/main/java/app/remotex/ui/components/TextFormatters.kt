package app.remotex.ui.components

fun relativeAge(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "—"
    val diff = (System.currentTimeMillis() / 1000L) - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}

fun shortenCwd(cwd: String): String {
    val home = System.getProperty("user.home")
    val trimmed = if (home != null && cwd.startsWith(home)) "~" + cwd.substring(home.length) else cwd
    return if (trimmed.length > 30) "…" + trimmed.takeLast(27) else trimmed
}

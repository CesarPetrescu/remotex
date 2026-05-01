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

/**
 * Shorten a path for display. A5: truncate from the LEFT so the
 * leaf folder (the most informative bit) stays visible.
 *   /home/cesar5514/remotex/services/daemon/adapters/stdio.py
 *   → …/daemon/adapters/stdio.py
 *
 * Tries to land on a clean slash boundary so we don't chop a
 * folder name in half — falls back to a hard takeLast() if no
 * boundary exists in the trailing window.
 */
fun shortenCwd(cwd: String): String {
    val home = System.getProperty("user.home")
    val trimmed = if (home != null && cwd.startsWith(home)) "~" + cwd.substring(home.length) else cwd
    if (trimmed.length <= 30) return trimmed
    val tail = trimmed.takeLast(28)
    val firstSlash = tail.indexOf('/')
    val landed = if (firstSlash > 0) tail.substring(firstSlash) else tail
    return "…$landed"
}

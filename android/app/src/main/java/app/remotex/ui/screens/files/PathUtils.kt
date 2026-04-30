package app.remotex.ui.screens.files

internal fun joinPath(base: String, name: String): String {
    val normalizedBase = if (base.endsWith("/")) base.dropLast(1) else base
    return "$normalizedBase/$name".ifEmpty { "/$name" }
}

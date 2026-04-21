package app.remotex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bg = Color(0xFF0D0F13)
private val Panel = Color(0xFF14171D)
private val Panel2 = Color(0xFF1A1E26)
val Line = Color(0xFF262A33)
val Ink = Color(0xFFE8DFD0)
val InkDim = Color(0xFF9A958A)
val Amber = Color(0xFFE8A756)
val Ok = Color(0xFF7DC87D)
val Warn = Color(0xFFE05A3E)

private val RemotexColors = darkColorScheme(
    primary = Amber,
    onPrimary = Bg,
    background = Bg,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Panel2,
    onSurfaceVariant = InkDim,
    error = Warn,
    onError = Bg,
)

@Composable
fun RemotexTheme(
    // Always dark in the wireframe aesthetic; parameter kept so the
    // system can still flip us if future designs need a light mode.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = RemotexColors, content = content)
}

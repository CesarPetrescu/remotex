package app.remotex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette derived from docs/brand/logo.png but pitched to OLED-dark:
// the cyan/teal accents from the logo on top of near-black surfaces
// so the phone doesn't light up a wall of blue at night.

private val Bg = Color(0xFF050910)        // near-black with a hint of navy
private val Panel = Color(0xFF0A1120)     // one step up from bg
private val Panel2 = Color(0xFF121A2C)    // elevated surface
val Line = Color(0xFF1D2940)              // subtle divider
val Ink = Color(0xFFE3EDFA)               // primary text, cool white
val InkDim = Color(0xFF88A4C4)             // secondary text
val Amber = Color(0xFF5EE1FF)              // brand accent (cyan from logo)
                                          // name kept for migration — swap later
val AccentDeep = Color(0xFF3AA0E8)        // deeper accent for hover / hint
val Ok = Color(0xFF6AE0C2)                // teal, sits inside the blue family
val Warn = Color(0xFFFF7070)              // soft red, legible on black

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
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = RemotexColors, content = content)
}

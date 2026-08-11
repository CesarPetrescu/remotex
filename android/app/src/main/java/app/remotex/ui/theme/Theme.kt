package app.remotex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Two palettes, one token set — mirrors apps/web/src/styles.css.
//
// The token NAMES are kept as top-level properties (Ink, InkDim, Amber…)
// because ~20 files already import them directly. Redefining them as
// @Composable getters over a CompositionLocal makes every existing call
// site theme-aware without a single import change.

data class RemotexPalette(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val ink: Color,
    val inkDim: Color,
    val accent: Color,
    val accentDeep: Color,
    val ok: Color,
    val warn: Color,
    val gold: Color,
    val onAccent: Color,
    // Syntax highlighting + diff tints.
    val codeString: Color,
    val codeNumber: Color,
    val codeKeyword: Color,
    val codeComment: Color,
    val isDark: Boolean,
)

// OLED-dark: cyan/teal accents from docs/brand/logo.png on near-black.
val DarkPalette = RemotexPalette(
    bg = Color(0xFF050910),
    panel = Color(0xFF0A1120),
    panel2 = Color(0xFF121A2C),
    line = Color(0xFF1D2940),
    ink = Color(0xFFE3EDFA),
    inkDim = Color(0xFF88A4C4),
    accent = Color(0xFF5EE1FF),
    accentDeep = Color(0xFF3AA0E8),
    ok = Color(0xFF6AE0C2),
    warn = Color(0xFFFF7070),
    gold = Color(0xFFFFD166),
    onAccent = Color(0xFF050910),
    codeString = Color(0xFF9CD4A0),
    codeNumber = Color(0xFFE0B479),
    codeKeyword = Color(0xFF5EE1FF),
    codeComment = Color(0xFF88A4C4),
    isDark = true,
)

// Light: same identity, accents darkened for AA contrast on white.
val LightPalette = RemotexPalette(
    bg = Color(0xFFF6F8FB),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFEEF2F8),
    line = Color(0xFFD7DFEB),
    ink = Color(0xFF101B2C),
    inkDim = Color(0xFF51677F),
    accent = Color(0xFF007C9E),
    accentDeep = Color(0xFF1668C7),
    ok = Color(0xFF0D7F66),
    warn = Color(0xFFD92D20),
    gold = Color(0xFFA97B00),
    onAccent = Color(0xFFFFFFFF),
    codeString = Color(0xFF1A7F37),
    codeNumber = Color(0xFFA15C07),
    codeKeyword = Color(0xFF007C9E),
    codeComment = Color(0xFF51677F),
    isDark = false,
)

// Optional maximum-contrast palette for bright sunlight, low-vision users,
// and displays whose vendor color processing crushes the regular dark tones.
// Every informational foreground is deliberately high-luminance on black;
// selection never relies on color alone in the controls that use it.
val HighContrastPalette = RemotexPalette(
    bg = Color.Black,
    panel = Color.Black,
    panel2 = Color(0xFF111111),
    line = Color.White,
    ink = Color.White,
    inkDim = Color(0xFFE6E6E6),
    accent = Color(0xFF00E5FF),
    accentDeep = Color(0xFF80BFFF),
    ok = Color(0xFF80FFD4),
    warn = Color(0xFFFF8A8A),
    gold = Color(0xFFFFE066),
    onAccent = Color.Black,
    codeString = Color(0xFF9CFF9C),
    codeNumber = Color(0xFFFFCE80),
    codeKeyword = Color(0xFF80EFFF),
    codeComment = Color(0xFFE6E6E6),
    isDark = true,
)

enum class RemotexThemeMode {
    Dark,
    Light,
    HighContrast;

    fun next(): RemotexThemeMode = when (this) {
        Dark -> Light
        Light -> HighContrast
        HighContrast -> Dark
    }

    companion object {
        fun fromStored(value: String?, systemDark: Boolean): RemotexThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: if (systemDark) Dark else Light
    }
}

val LocalPalette = compositionLocalOf { DarkPalette }

// --- token accessors: same names the whole app already imports ---------

val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val InkDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inkDim
val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
val Amber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val AccentDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accentDeep
val Ok: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ok
val Warn: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warn
val Gold: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.gold
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent

private fun schemeFor(p: RemotexPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        background = p.bg,
        onBackground = p.ink,
        surface = p.panel,
        onSurface = p.ink,
        surfaceVariant = p.panel2,
        onSurfaceVariant = p.inkDim,
        error = p.warn,
        onError = p.bg,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        background = p.bg,
        onBackground = p.ink,
        surface = p.panel,
        onSurface = p.ink,
        surfaceVariant = p.panel2,
        onSurfaceVariant = p.inkDim,
        error = p.warn,
        onError = p.panel,
    )
}

@Composable
fun RemotexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = when {
        highContrast -> HighContrastPalette
        darkTheme -> DarkPalette
        else -> LightPalette
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = schemeFor(palette), content = content)
    }
}

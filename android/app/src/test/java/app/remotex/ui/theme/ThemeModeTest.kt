package app.remotex.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun cycleIncludesHighContrastAndReturnsToDark() {
        assertEquals(RemotexThemeMode.Light, RemotexThemeMode.Dark.next())
        assertEquals(RemotexThemeMode.HighContrast, RemotexThemeMode.Light.next())
        assertEquals(RemotexThemeMode.Dark, RemotexThemeMode.HighContrast.next())
    }

    @Test
    fun missingPreferenceFollowsSystemAndStoredValueWins() {
        assertEquals(RemotexThemeMode.Dark, RemotexThemeMode.fromStored(null, systemDark = true))
        assertEquals(RemotexThemeMode.Light, RemotexThemeMode.fromStored(null, systemDark = false))
        assertEquals(
            RemotexThemeMode.HighContrast,
            RemotexThemeMode.fromStored("highcontrast", systemDark = false),
        )
    }
}

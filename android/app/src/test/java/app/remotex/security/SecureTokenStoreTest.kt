package app.remotex.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecureTokenStoreTest {
    @Test
    fun scopeUsesNormalizedBaseAndSeparatesPathProviders() {
        assertEquals(
            relayScopeKey("https://relay.example.com/path"),
            relayScopeKey("https://RELAY.example.com:443/path/"),
        )
        assertNotEquals(
            relayScopeKey("https://relay.example.com/a"),
            relayScopeKey("https://relay.example.com/b"),
        )
        assertNotEquals(
            relayScopeKey("https://relay-a.example.com"),
            relayScopeKey("https://relay-b.example.com"),
        )
        assertNotEquals(
            relayScopeKey("https://relay.example.com"),
            relayScopeKey("https://relay.example.com:8443"),
        )
        assertEquals(
            relayScopeKey("https://relay.example.com"),
            relayScopeKey("https://relay.example.com:443"),
        )
    }
}

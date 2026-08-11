package app.remotex.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBaseUrlTest {
    @Test
    fun normalizesCaseDefaultPortAndPathTrailingSlash() {
        assertEquals(
            "https://relay.example.com/remotex",
            normalizeRelayBaseUrl("  HTTPS://RELAY.EXAMPLE.COM:443/remotex///  ").getOrThrow(),
        )
        assertEquals(
            "http://[::1]:8080",
            normalizeRelayBaseUrl("http://[::1]:8080/").getOrThrow(),
        )
    }

    @Test
    fun rejectsUnsafeOrAmbiguousBases() {
        val invalid = listOf(
            "",
            "relay.example.com",
            "ftp://relay.example.com",
            "https:///missing-host",
            "https://user@relay.example.com",
            "https://user:secret@relay.example.com",
            "https://relay.example.com?next=https://evil.example",
            "https://relay.example.com/#fragment",
        )

        invalid.forEach { raw ->
            assertTrue("expected rejection for $raw", normalizeRelayBaseUrl(raw).isFailure)
        }
    }

    @Test
    fun releasePolicyRejectsHttpWithClearMessage() {
        assertTrue(normalizeRelayBaseUrl("http://10.0.2.2:8080").isSuccess)
        val failure = normalizeRelayBaseUrl(
            "http://relay.example.com",
            allowInsecureHttp = false,
        ).exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("HTTPS"))
    }
}

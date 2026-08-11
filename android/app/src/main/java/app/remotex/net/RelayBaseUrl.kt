package app.remotex.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Parse once at the trust boundary before a bearer token can be attached.
 * A path prefix is supported for reverse-proxy deployments; its trailing
 * slash is removed so endpoint concatenation is deterministic.
 */
internal fun normalizeRelayBaseUrl(
    raw: String,
    allowInsecureHttp: Boolean = true,
): Result<String> = runCatching {
    val candidate = raw.trim()
    require(candidate.isNotEmpty()) { "Enter a relay URL." }
    val structure = runCatching { URI(candidate) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid relay URL.")
    require(structure.scheme.equals("http", ignoreCase = true) ||
        structure.scheme.equals("https", ignoreCase = true)) {
        "Relay URL must use http or https."
    }
    require(allowInsecureHttp || structure.scheme.equals("https", ignoreCase = true)) {
        "Release builds require an HTTPS relay URL."
    }
    require(structure.rawAuthority != null) { "Relay URL must include a host." }
    require(structure.rawUserInfo == null) {
        "Relay URL must not contain embedded credentials."
    }
    require(structure.rawQuery == null) { "Relay URL must not contain a query." }
    require(structure.rawFragment == null) { "Relay URL must not contain a fragment." }
    val url = candidate.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Enter a valid relay URL.")
    require(url.host.isNotBlank()) { "Relay URL must include a host." }
    url.toString().trimEnd('/')
}

internal fun requireRelayBaseUrl(raw: String, allowInsecureHttp: Boolean = true): String =
    normalizeRelayBaseUrl(raw, allowInsecureHttp).getOrElse { cause ->
        throw IllegalArgumentException(cause.message ?: "Invalid relay URL.", cause)
    }

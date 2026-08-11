package app.remotex.persistence

import android.content.Context

/**
 * The minimum state needed to reattach after Android recreates the UI
 * process. Session ids are scoped to the normalized relay base (including a
 * reverse-proxy path) and are not copied between providers. The relay remains
 * authoritative: a stale id is discarded when
 * the attach returns a fatal error.
 */
data class ActiveSession(
    val sessionId: String,
    val hostId: String,
    val threadId: String? = null,
    val lastSeq: Long = 0L,
)

class ActiveSessionStore(
    context: Context,
    private val scopeKey: String,
    preferencesName: String = "remotex.session",
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun load(): ActiveSession? {
        val sessionId = preferences.getString(key("session_id"), null)?.takeIf { it.isNotBlank() }
            ?: return null
        val hostId = preferences.getString(key("host_id"), null)?.takeIf { it.isNotBlank() }
            ?: return null
        return ActiveSession(
            sessionId = sessionId,
            hostId = hostId,
            threadId = preferences.getString(key("thread_id"), null)?.takeIf { it.isNotBlank() },
            lastSeq = preferences.getLong(key("last_seq"), 0L).coerceAtLeast(0L),
        )
    }

    fun save(session: ActiveSession) {
        preferences.edit()
            .putString(key("session_id"), session.sessionId)
            .putString(key("host_id"), session.hostId)
            .putString(key("thread_id"), session.threadId)
            .putLong(key("last_seq"), session.lastSeq.coerceAtLeast(0L))
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(key("session_id"))
            .remove(key("host_id"))
            .remove(key("thread_id"))
            .remove(key("last_seq"))
            .apply()
    }

    private fun key(name: String) = "$name.$scopeKey"
}

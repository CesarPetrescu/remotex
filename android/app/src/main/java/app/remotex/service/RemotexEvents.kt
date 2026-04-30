package app.remotex.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide signal bus. Lets components without a direct ViewModel
 * reference (notification action receiver, FG service, deep-link
 * intents) ask the active session ViewModel to do something.
 *
 * Replays of `extraBufferCapacity = 1` mean a signal sent before the
 * ViewModel starts collecting still gets delivered when collection
 * begins (e.g. tapping a notification while the app is killed).
 */
object RemotexEvents {
    val cancelTurn = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** (hostId, threadId) — open this saved chat (resume in-flight if any). */
    val openSession = MutableSharedFlow<Pair<String, String>>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

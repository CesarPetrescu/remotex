package app.remotex.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Sticky foreground service that the ViewModel starts when a turn
 * goes pending and stops when it completes. Its only job is to keep
 * the app process alive (and the WebSocket attached) while the user
 * has the app backgrounded — without it, Doze tears the WS down within
 * ~30s and the user misses the turn-completed frame.
 *
 * The persistent notification it posts is the user's signal that work
 * is actively happening.
 */
class SessionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chatTitle = intent?.getStringExtra(EXTRA_CHAT_TITLE) ?: "current chat"
        val hostNickname = intent?.getStringExtra(EXTRA_HOST_NICKNAME) ?: "host"
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID)
        val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)
        val notification = SessionNotifier.buildRunningNotification(
            ctx = this,
            chatTitle = chatTitle,
            hostNickname = hostNickname,
            hostId = hostId,
            threadId = threadId,
        )
        startForeground(SessionNotifier.NOTIF_ID_RUNNING, notification)
        return START_STICKY
    }

    companion object {
        const val EXTRA_CHAT_TITLE = "chat_title"
        const val EXTRA_HOST_NICKNAME = "host_nickname"
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_THREAD_ID = "thread_id"

        fun start(
            ctx: Context,
            chatTitle: String,
            hostNickname: String,
            hostId: String?,
            threadId: String?,
        ) {
            val intent = Intent(ctx, SessionForegroundService::class.java).apply {
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                putExtra(EXTRA_HOST_NICKNAME, hostNickname)
                if (!hostId.isNullOrBlank()) putExtra(EXTRA_HOST_ID, hostId)
                if (!threadId.isNullOrBlank()) putExtra(EXTRA_THREAD_ID, threadId)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionForegroundService::class.java))
        }
    }
}

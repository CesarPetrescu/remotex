package app.remotex.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.remotex.MainActivity

/**
 * Owns the two notification channels and renders both kinds of
 * session-related notifications:
 *   - persistent "agent running" notification (used by the FG service)
 *   - one-shot "agent done" notification (posted when a turn completes
 *     while the user isn't actively watching the app)
 */
object SessionNotifier {
    const val CHANNEL_RUNNING = "remotex.session.running"
    const val CHANNEL_DONE = "remotex.session.done"
    const val NOTIF_ID_RUNNING = 1
    private const val NOTIF_ID_DONE_BASE = 1000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                "Agent running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent indicator while a codex turn is in flight."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                "Agent finished",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Posted when a codex turn finishes while the app is in the background."
            }
        )
    }

    /** Foreground-service notification — must be returned from startForeground. */
    fun buildRunningNotification(
        ctx: Context,
        chatTitle: String,
        hostNickname: String,
        hostId: String?,
        threadId: String?,
    ): android.app.Notification {
        ensureChannels(ctx)
        val openIntent = openChatPendingIntent(ctx, hostId, threadId)
        val cancelIntent = CancelTurnReceiver.pendingIntent(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Codex is running")
            .setContentText("$chatTitle · $hostNickname")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel turn",
                    cancelIntent,
                ).build()
            )
            .build()
    }

    /** Posted when a turn completes while the app is backgrounded. */
    fun postDoneNotification(
        ctx: Context,
        chatTitle: String,
        hostNickname: String,
        hostId: String?,
        threadId: String?,
        tokensIn: Long = 0L,
        tokensOut: Long = 0L,
    ) {
        ensureChannels(ctx)
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) return
        val openIntent = openChatPendingIntent(ctx, hostId, threadId)
        val subtitle = buildString {
            append(hostNickname)
            if (tokensIn > 0L || tokensOut > 0L) {
                append(" · ")
                append("${formatK(tokensIn)}↑ ${formatK(tokensOut)}↓")
            }
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Codex done · $chatTitle")
            .setContentText(subtitle)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .build()
        // Stable id per thread so back-to-back turns on the same chat
        // replace the previous notification rather than stacking.
        val id = NOTIF_ID_DONE_BASE + (threadId?.hashCode()?.and(0xFFFFFF) ?: 0)
        try {
            nmc.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied; nothing to do.
        }
    }

    private fun openChatPendingIntent(ctx: Context, hostId: String?, threadId: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!hostId.isNullOrBlank()) putExtra(MainActivity.EXTRA_HOST_ID, hostId)
            if (!threadId.isNullOrBlank()) putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
        }
        return PendingIntent.getActivity(
            ctx,
            (threadId ?: "main").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatK(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 100_000 -> String.format("%.1fK", n / 1000.0)
        n < 1_000_000 -> "${n / 1000}K"
        else -> String.format("%.1fM", n / 1_000_000.0)
    }
}

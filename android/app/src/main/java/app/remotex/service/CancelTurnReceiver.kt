package app.remotex.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification "Cancel turn" action target. Routes the request to the
 * ViewModel through RemotexEvents — the ViewModel collects the flow
 * and calls vm.interruptTurn().
 */
class CancelTurnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemotexEvents.cancelTurn.tryEmit(Unit)
    }

    companion object {
        const val ACTION = "app.remotex.action.CANCEL_TURN"

        fun pendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, CancelTurnReceiver::class.java).apply {
                action = ACTION
                setPackage(ctx.packageName)
            }
            return PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

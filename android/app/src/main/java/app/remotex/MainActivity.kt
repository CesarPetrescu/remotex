package app.remotex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.remotex.service.RemotexEvents
import app.remotex.service.SessionNotifier
import app.remotex.ui.RemotexApp
import app.remotex.ui.theme.RemotexTheme

class MainActivity : ComponentActivity() {
    private val notificationsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: declined just means no done notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SessionNotifier.ensureChannels(this)
        maybeRequestNotificationsPermission()
        setContent {
            RemotexTheme {
                RemotexApp(relayUrl = BuildConfig.RELAY_URL)
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Notification taps deliver a fresh Intent with EXTRA_HOST_ID +
     * EXTRA_THREAD_ID. Forward to the ViewModel via RemotexEvents so it
     * opens (or rejoins) that chat — the session-reuse logic on the
     * relay side means an in-flight turn is reattached rather than
     * spawning a duplicate.
     */
    private fun handleDeepLink(intent: Intent?) {
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID) ?: return
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: return
        if (hostId.isBlank() || threadId.isBlank()) return
        RemotexEvents.openSession.tryEmit(hostId to threadId)
        intent.removeExtra(EXTRA_HOST_ID)
        intent.removeExtra(EXTRA_THREAD_ID)
    }

    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationsPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}

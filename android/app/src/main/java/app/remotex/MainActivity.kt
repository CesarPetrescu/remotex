package app.remotex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.remotex.ui.RemotexApp
import app.remotex.ui.theme.RemotexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemotexTheme {
                RemotexApp(relayUrl = BuildConfig.RELAY_URL)
            }
        }
    }
}

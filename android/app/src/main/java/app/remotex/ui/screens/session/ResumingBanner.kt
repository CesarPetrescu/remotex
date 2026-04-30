package app.remotex.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.InkDim
import kotlinx.coroutines.delay

@Composable
internal fun ResumingBanner(sinceMs: Long) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedSec = ((nowMs - sinceMs).coerceAtLeast(0L) / 1000L).toInt()
    Surface(
        color = Amber.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.45f)),
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(Amber))
            Spacer(Modifier.width(8.dp))
            Text(
                "Resuming saved chat — codex is reading the rollout",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${elapsedSec}s",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

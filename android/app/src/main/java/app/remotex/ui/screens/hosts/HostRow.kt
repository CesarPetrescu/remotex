package app.remotex.ui.screens.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.Host
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line
import app.remotex.ui.theme.Ok

@Composable
internal fun HostRow(host: Host, onClick: () -> Unit) {
    val border = if (host.online) Amber else Line
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = host.online, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (host.online) Ok else InkDim))
                Spacer(Modifier.width(8.dp))
                Text(
                    host.nickname,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${host.hostname ?: "—"} · ${if (host.online) "online" else "offline"}",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.online) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "tap to open session →",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

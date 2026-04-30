package app.remotex.ui.screens.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
internal fun Breadcrumbs(path: String, onJump: (String) -> Unit) {
    val segments = remember(path) {
        buildList {
            add("/" to "/")
            if (path != "/" && path.isNotEmpty()) {
                val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
                var acc = ""
                for (p in parts) {
                    acc += "/$p"
                    add(p to acc)
                }
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(segments.size) { idx ->
                val (label, target) = segments[idx]
                val active = idx == segments.lastIndex
                Text(
                    text = label,
                    color = if (active) Ink else AccentDeep,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable(enabled = !active) { onJump(target) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (!active) {
                    Text(
                        "/",
                        color = InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

package app.remotex.ui.screens.session.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import kotlinx.coroutines.delay

@Composable
internal fun BodyText(text: String, dim: Boolean = false, italic: Boolean = false) {
    Text(
        text,
        color = if (dim) InkDim else Ink,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )
}

@Composable
internal fun AgentText(text: String, streaming: Boolean) {
    Text(
        buildString {
            append(text)
            if (streaming) append('▍')
        },
        color = Ink,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

@Composable
internal fun CodeBlock(text: String, dim: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        modifier = Modifier.clickable {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    ) {
        Box {
            Text(
                text,
                color = if (dim) InkDim else Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            // Tap-affordance + transient confirmation, both top-right.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(Color.Black.copy(alpha = if (copied) 0.55f else 0.0f)),
            ) {
                Text(
                    text = if (copied) "copied" else "📋",
                    color = if (copied) Amber else InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

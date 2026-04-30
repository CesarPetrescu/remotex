package app.remotex.ui.screens.session.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import app.remotex.model.UiEvent
import app.remotex.ui.theme.Ink

@Composable
internal fun UserBubble(event: UiEvent.User) {
    val userAccent = Color(0xFF8FB4FF)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .wrapContentWidth(Alignment.End)
        ) {
            if (event.imageUris.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(event.imageUris) { uri ->
                        AsyncImage(
                            model = uri.toUri(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .background(Color(0x1A3AA0E8))
                    .border(1.dp, Color(0x473AA0E8))
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        "USER",
                        color = userAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        event.text,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 24.dp)
                        .background(userAccent),
                )
            }
        }
    }
}

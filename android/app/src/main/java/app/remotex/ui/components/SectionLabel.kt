package app.remotex.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.remotex.ui.theme.InkDim

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = InkDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
}

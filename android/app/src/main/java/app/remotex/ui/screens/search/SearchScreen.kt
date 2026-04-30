package app.remotex.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.model.SearchResult
import app.remotex.ui.UiState
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

@Composable
fun SearchScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenResult: (SearchResult) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "semantic chat search".uppercase(),
            color = InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            border = BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                singleLine = true,
                modifier = Modifier.padding(10.dp).fillMaxWidth(),
            )
        }
        Button(
            onClick = onSearch,
            enabled = !state.searchLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                contentColor = Color.Black,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledContentColor = InkDim,
            ),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.searchLoading) "Searching" else "Search chats",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        when {
            state.searchLoading && state.searchResults.isEmpty() -> Text(
                "searching...",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
            state.searchResults.isEmpty() -> Text(
                "enter a query to find previous answers and reasoning",
                color = InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.searchResults, key = { it.id }) { result ->
                    SearchResultRow(result, onClick = { onOpenResult(result) })
                }
            }
        }
    }
}

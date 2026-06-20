package app.remotex.ui.screens.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remotex.ui.UiState
import app.remotex.ui.theme.AccentDeep
import app.remotex.ui.theme.Amber
import app.remotex.ui.theme.Ink
import app.remotex.ui.theme.InkDim
import app.remotex.ui.theme.Line

private val Mono = FontFamily.Monospace

// Jump — search-first folder picker (the mobile sibling of the web JumpPicker).
// Fuzzy-filter the current dir or type a /path to teleport, quick-jump chips for
// home + pinned folders, a RECENT section, compact borderless rows you tap to
// enter, and a persistent "Select this folder" bar that commits the cwd.
@Composable
fun FilesScreen(
    state: UiState,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onStartHere: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val path = state.browsePath.ifEmpty { "/" }
    val host = state.hosts.find { it.id == state.selectedHostId }
    val home = host?.homeDir ?: host?.defaultCwd ?: "/"
    var query by rememberSaveable { mutableStateOf("") }
    var newFolderOpen by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }

    fun display(p: String): String = when {
        p == home -> "~"
        home != "/" && p.startsWith("$home/") -> "~" + p.substring(home.length)
        else -> p
    }
    fun expand(raw: String): String {
        var s = raw.trim()
        if (s == "~") return home
        if (s.startsWith("~/")) return joinPath(home, s.substring(2))
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    val q = query.trim()
    val isPathQuery = q.startsWith("/") || q.startsWith("~")
    val dirs = state.browseEntries.filter { it.isDirectory }
    val filtered = if (q.isEmpty() || isPathQuery) {
        dirs
    } else {
        dirs.mapNotNull { e -> fuzzyScore(q, e.fileName)?.let { e to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }
    val favSet = state.favorites.toSet()

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ── fixed header: search + breadcrumb + quick chips ─────────────
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = query,
                    onChange = { query = it },
                    onClear = { query = "" },
                    onSubmit = {
                        if (isPathQuery && q.isNotEmpty()) {
                            onNavigate(expand(q)); query = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                IconChip("↑", enabled = path != "/") { onUp() }
            }
            Breadcrumbs(path = path, onJump = onNavigate)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Chip("⌂ ~", accent = true, active = path == home) { onNavigate(home) } }
                items(state.favorites) { fav ->
                    Chip("★ " + baseName(fav), accent = false, active = path == fav) { onNavigate(fav) }
                }
                item { Chip("＋ new", accent = true) { newFolderOpen = true; newFolderName = "" } }
            }
            if (newFolderOpen) {
                NewFolderRow(
                    name = newFolderName,
                    onNameChange = { newFolderName = it },
                    onConfirm = {
                        val n = newFolderName.trim()
                        if (n.isNotEmpty() && "/" !in n && n != "." && n != "..") {
                            onCreateFolder(n); newFolderOpen = false; newFolderName = ""
                        }
                    },
                    onCancel = { newFolderOpen = false; newFolderName = "" },
                )
            }
        }

        // ── scrollable results ──────────────────────────────────────────
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            if (isPathQuery && q.isNotEmpty()) {
                item { JumpToRow(display(expand(q))) { onNavigate(expand(q)); query = "" } }
            }
            if (q.isEmpty() && state.recents.isNotEmpty()) {
                item { SectionHeader("RECENT") }
                items(state.recents) { r ->
                    SimpleRow(glyph = "↺", label = display(r)) { onNavigate(r) }
                }
            }
            if (q.isEmpty()) item { SectionHeader("FOLDERS") }
            if (state.browseLoading && filtered.isEmpty()) {
                item {
                    Text(
                        "loading…", color = InkDim, fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        if (q.isEmpty()) "no subfolders" else "no matches",
                        color = InkDim, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                items(filtered, key = { it.fileName }) { entry ->
                    val full = joinPath(path, entry.fileName)
                    FsRowCompact(
                        name = entry.fileName,
                        fav = favSet.contains(full),
                        onOpen = { onNavigate(full) },
                        onToggleFav = { onToggleFavorite(full) },
                    )
                }
            }
        }

        // ── persistent commit bar ───────────────────────────────────────
        SelectBar(displayPath = display(path), onSelect = onStartHere)
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("›", color = Amber, fontFamily = Mono, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("filter… or type /path", color = InkDim, fontFamily = Mono, fontSize = 12.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Ink, fontFamily = Mono, fontSize = 12.sp),
                    cursorBrush = SolidColor(Amber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Text(
                    "×", color = InkDim, fontFamily = Mono, fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, accent: Boolean, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (active) Line else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) Amber else Line),
        shape = RectangleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = if (accent) Amber else Ink,
            fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun IconChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        shape = RectangleShape,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text,
            color = if (enabled) Ink else InkDim,
            fontFamily = Mono, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = InkDim, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun FsRowCompact(name: String, fav: Boolean, onOpen: () -> Unit, onToggleFav: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▸", color = Amber, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            name, color = Ink, fontFamily = Mono, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (fav) "★" else "☆",
            color = if (fav) Amber else InkDim,
            fontFamily = Mono, fontSize = 13.sp,
            modifier = Modifier
                .clickable(onClick = onToggleFav)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text("›", color = AccentDeep, fontFamily = Mono, fontSize = 13.sp)
    }
    Divider()
}

@Composable
private fun SimpleRow(glyph: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = AccentDeep, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            label, color = Ink, fontFamily = Mono, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("›", color = AccentDeep, fontFamily = Mono, fontSize = 13.sp)
    }
    Divider()
}

@Composable
private fun JumpToRow(target: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("→", color = Amber, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            "go to $target", color = Ink, fontFamily = Mono, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Divider()
}

@Composable
private fun SelectBar(displayPath: String, onSelect: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("USE", color = InkDim, fontFamily = Mono, fontSize = 9.sp)
                Text(
                    displayPath, color = Ink, fontFamily = Mono, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                shape = RectangleShape,
            ) {
                Text("Select this folder", fontFamily = Mono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Line),
    )
}

private fun baseName(p: String): String {
    if (p == "/" || p.isEmpty()) return "/"
    val t = p.trimEnd('/')
    return t.substring(t.lastIndexOf('/') + 1)
}

// Subsequence fuzzy match mirroring the web util/fuzzy.js: rewards contiguous
// runs, word-boundary hits (/ _ - . space) and start matches. null = no match.
internal fun fuzzyScore(query: String, target: String): Int? {
    if (query.isEmpty()) return 0
    val q = query.lowercase()
    val t = target.lowercase()
    var qi = 0
    var score = 0
    var prev = -2
    var streak = 0
    for (ti in t.indices) {
        if (qi >= q.length) break
        if (t[ti] != q[qi]) continue
        var pts = 1
        if (ti == prev + 1) {
            streak++; pts += streak * 2
        } else {
            streak = 0
        }
        val prevCh = if (ti > 0) t[ti - 1] else '/'
        if (prevCh in "/._- ") pts += 3
        if (ti == 0) pts += 2
        score += pts
        prev = ti
        qi++
    }
    if (qi < q.length) return null
    return score - t.length / 20
}

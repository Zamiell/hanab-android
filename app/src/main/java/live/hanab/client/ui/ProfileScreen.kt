@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.hanab.client.GridColumn
import live.hanab.client.ProfileUi
import live.hanab.client.ProfileView
import live.hanab.client.ProfileViewModel
import live.hanab.client.net.GameLine
import live.hanab.client.net.ProfileSource
import live.hanab.client.net.VariantRow
import java.util.Locale

private val GOLD = Color(0xFFFFC107)

/** A player's profile like the website's: the facts and a grid of variants, with the game history behind a button. */
@Composable
fun ProfileScreen(
    name: String,
    source: ProfileSource,
    onBack: () -> Unit,
    onOpenReplay: (Long) -> Unit,
    onAnalyze: (GameLine) -> Unit,
) {
    val pvm: ProfileViewModel = viewModel()
    LaunchedEffect(name) { pvm.open(name, source) }
    val ui by pvm.ui.collectAsStateWithLifecycle()
    val inHistory = ui.view == ProfileView.History
    val back = { if (inHistory) pvm.show(ProfileView.Stats) else onBack() }
    BackHandler(onBack = back)

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("Back") }
                Text(if (inHistory) "$name · games" else name, style = MaterialTheme.typography.titleLarge)
            }
            if (inHistory) HistoryView(ui, pvm, onOpenReplay, onAnalyze) else StatsView(ui, pvm)
        }
    }
}

// ------------------------------------------------------------------------------------------ stats

private val NAME_WIDTH = 150.dp
private val NUMBER_WIDTH = 60.dp

private val columnHeaders = mapOf(
    GridColumn.Variant to "Variant", GridColumn.Games to "Games", GridColumn.Best2 to "2p", GridColumn.Best3 to "3p",
    GridColumn.Best4 to "4p", GridColumn.Best5 to "5p", GridColumn.Best6 to "6p", GridColumn.Average to "Avg",
    GridColumn.Strikeouts to "K.O.",
)

/** The columns shown: the website's page stopped giving an average and a strikeout rate, so those are dropped. */
private fun columnsOf(ui: ProfileUi): List<GridColumn> =
    GridColumn.entries.filter { (ui.scores?.hasAverages ?: true) || it == GridColumn.Variant || it <= GridColumn.Best6 }

private fun sortKey(row: VariantRow, column: GridColumn): Comparable<*> = when (column) {
    GridColumn.Variant -> row.variant.lowercase()
    GridColumn.Games -> row.games
    GridColumn.Best2 -> row.best[0].score
    GridColumn.Best3 -> row.best[1].score
    GridColumn.Best4 -> row.best[2].score
    GridColumn.Best5 -> row.best[3].score
    GridColumn.Best6 -> row.best[4].score
    GridColumn.Average -> row.average ?: -1.0
    GridColumn.Strikeouts -> row.strikeoutRate ?: -1.0
}

@Composable
private fun StatsView(ui: ProfileUi, pvm: ProfileViewModel) {
    val page = ui.scores
    if (page == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                ui.scoresError != null -> {
                    Text(ui.scoresError, color = MaterialTheme.colorScheme.error)
                    if (!ui.scoresError.startsWith("No player")) TextButton(onClick = pvm::retryScores) { Text("Try again") }
                }
                else -> CircularProgressIndicator()
            }
        }
        return
    }

    val rows = remember(page, ui.gridFilter, ui.sortColumn, ui.sortDescending) {
        val filtered = page.rows.filter { it.variant.contains(ui.gridFilter, ignoreCase = true) }
        @Suppress("UNCHECKED_CAST")
        val sorted = filtered.sortedWith(compareBy<VariantRow> { sortKey(it, ui.sortColumn) as Comparable<Any> })
        if (ui.sortDescending) sorted.reversed() else sorted
    }
    val pageSize = if (ui.gridPageSize == 0) maxOf(rows.size, 1) else ui.gridPageSize
    val pages = maxOf(1, (rows.size + pageSize - 1) / pageSize)
    val current = ui.gridPage.coerceIn(0, pages - 1)
    val shown = rows.drop(current * pageSize).take(pageSize)
    // One scroll position for the header and every row, so the numbers move together under the fixed names.
    val scroll = rememberScrollState()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                page.facts.forEach { (label, value) ->
                    Row {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Button(onClick = { pvm.show(ProfileView.History) }, modifier = Modifier.fillMaxWidth()) { Text("Game history") }
            OutlinedTextField(
                value = ui.gridFilter, onValueChange = pvm::setGridFilter, singleLine = true,
                label = { Text("Filter variants") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Rows", style = MaterialTheme.typography.labelMedium)
                listOf(10, 20, 30, 0).forEach { n ->
                    FilterChip(selected = ui.gridPageSize == n, onClick = { pvm.setGridPageSize(n) }, label = { Text(if (n == 0) "All" else "$n") })
                }
            }
            Pager(current, pages, pvm::setGridPage)
            GridHeader(ui, scroll, pvm::sortBy)
            HorizontalDivider()
        }
        items(shown, key = { it.variant }) { row ->
            GridRow(row, scroll, columnsOf(ui))
            HorizontalDivider()
        }
        item {
            if (shown.isEmpty()) Text("No variants match.", Modifier.padding(vertical = 12.dp))
            Pager(current, pages, pvm::setGridPage)
        }
    }
}

@Composable
private fun GridHeader(ui: ProfileUi, scroll: androidx.compose.foundation.ScrollState, onSort: (GridColumn) -> Unit) {
    fun label(c: GridColumn) = columnHeaders.getValue(c) + if (ui.sortColumn == c) (if (ui.sortDescending) " ▼" else " ▲") else ""
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label(GridColumn.Variant), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(NAME_WIDTH).clickable { onSort(GridColumn.Variant) }.padding(vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(scroll)) {
            columnsOf(ui).drop(1).forEach { c ->
                Text(
                    label(c), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.width(NUMBER_WIDTH).clickable { onSort(c) }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GridRow(row: VariantRow, scroll: androidx.compose.foundation.ScrollState, columns: List<GridColumn>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.variant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(NAME_WIDTH).padding(vertical = 8.dp))
        Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            Cell("${row.games}")
            row.best.forEach { b ->
                // A best of 0 is a size never played (or never scored); the max is the most the variant allows.
                Cell(
                    // A marked score was made with an option, and still counts as a normal best.
                    if (b.modified) "${b.score}*" else "${b.score}",
                    color = when {
                        b.score == b.max -> GOLD
                        b.score == 0 -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                        else -> Color.Unspecified
                    },
                    bold = b.score == b.max,
                )
            }
            if (GridColumn.Average in columns) Cell(row.average?.let { "%.1f".format(Locale.US, it) } ?: "-")
            if (GridColumn.Strikeouts in columns) {
                Cell(row.strikeoutRate?.let { "%.1f%%".format(Locale.US, it).replace(".0%", "%") } ?: "-")
            }
        }
    }
}

@Composable
private fun Cell(text: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Box(Modifier.width(NUMBER_WIDTH), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

/** First / previous / "Page n of m" / next / last. [page] counts from 0. */
@Composable
private fun Pager(page: Int, pages: Int, onPage: (Int) -> Unit) {
    if (pages <= 1) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { onPage(0) }, enabled = page > 0) { Text("⏮") }
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("◀") }
        Text("Page ${page + 1} of $pages", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onPage(page + 1) }, enabled = page < pages - 1) { Text("▶") }
        TextButton(onClick = { onPage(pages - 1) }, enabled = page < pages - 1) { Text("⏭") }
    }
}

// ------------------------------------------------------------------------------------------ history

@Composable
private fun HistoryView(ui: ProfileUi, pvm: ProfileViewModel, onOpenReplay: (Long) -> Unit, onAnalyze: (GameLine) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Filters(ui, pvm::setPlayers, pvm::setVariant)
            Text(
                "${"%,d".format(Locale.US, ui.historyTotal)} games",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Pager(ui.historyPage, ui.historyPages, pvm::setHistoryPage)
        }
        items(ui.history, key = { it.id }) { line ->
            GameRow(line, onAnalyze = { onAnalyze(line) }) { onOpenReplay(line.id) }
            HorizontalDivider()
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    ui.historyLoading -> CircularProgressIndicator()
                    ui.historyError != null -> {
                        Text(ui.historyError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = pvm::retryHistory) { Text("Try again") }
                    }
                    ui.history.isEmpty() -> Text("No games found.")
                    else -> Pager(ui.historyPage, ui.historyPages, pvm::setHistoryPage)
                }
            }
        }
    }
}

@Composable
private fun Filters(ui: ProfileUi, onPlayers: (Int?) -> Unit, onVariant: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = ui.players == null, onClick = { onPlayers(null) }, label = { Text("Any size") })
            (2..6).forEach { n ->
                FilterChip(selected = ui.players == n, onClick = { onPlayers(n) }, label = { Text("${n}p") })
            }
        }
        var query by remember { mutableStateOf("") }
        val selected = ui.variant
        if (selected != null) {
            FilterChip(selected = true, onClick = { onVariant(null) }, label = { Text("$selected  ×") })
        } else {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Filter by variant") }, modifier = Modifier.fillMaxWidth(),
            )
            if (query.length >= 2) {
                val matches = remember(query, ui.variantNames) {
                    ui.variantNames.filter { it.contains(query, ignoreCase = true) }.take(8)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    matches.forEach { name ->
                        FilterChip(selected = false, onClick = { onVariant(name); query = "" }, label = { Text(name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(line: GameLine, onAnalyze: () -> Unit, onClick: () -> Unit) {
    val max = line.max
    val perfect = max != null && line.score == max
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.variant, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${line.date} · ${line.players}p · with ${line.others.joinToString(", ").ifEmpty { "nobody else" }}" +
                    (line.note?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }
        Text(
            if (max != null) "${line.score}/$max" else "${line.score}",
            fontWeight = if (perfect) FontWeight.Bold else FontWeight.Normal,
            color = if (perfect) GOLD else Color.Unspecified,
        )
        // The bot, on this phone, goes through the game (not offered where the library is not packaged).
        if (live.hanab.client.NativeOffline.available && live.hanab.client.game.Variants.canAnalyze(line.variant)) {
            TextButton(onClick = onAnalyze) { Text("Analyze") }
        }
    }
}

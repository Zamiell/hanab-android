@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.hanab.client.game.Variants
import live.hanab.client.net.TableOpts

/**
 * The fields for creating a table or editing one in the pregame: name, variant, maximum players, an
 * optional password (creating only) and the special options. Used by both, so they stay identical.
 */
@Composable
fun TableForm(
    name: String,
    onName: (String) -> Unit,
    opts: TableOpts,
    onOpts: (TableOpts) -> Unit,
    maxPlayers: Int,
    onMaxPlayers: (Int) -> Unit,
    password: String? = null,
    onPassword: (String) -> Unit = {},
) {
    var showOptions by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = onName, label = { Text("Name") }, singleLine = true)

        VariantChooser(opts.variantName, Variants.allSupported()) { onOpts(opts.copy(variantName = it)) }

        Text("Max players", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (2..6).forEach { n ->
                FilterChip(selected = maxPlayers == n, onClick = { onMaxPlayers(n) }, label = { Text("$n") })
            }
        }

        if (password != null) {
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text("Password (optional)") },
                singleLine = true,
            )
        }

        OutlinedButton(onClick = { showOptions = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Special options" + if (opts.activeLabels.isEmpty()) "" else " (${opts.activeLabels.size})")
        }
        if (opts.activeLabels.isNotEmpty()) {
            Text(opts.activeLabels.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showOptions) TableOptionsDialog(opts, onOpts) { showOptions = false }
}

private class OptionRow(
    val title: String,
    val text: String,
    val get: (TableOpts) -> Boolean,
    val set: (TableOpts, Boolean) -> TableOpts,
)

private val optionRows = listOf(
    OptionRow(
        "Speedrun",
        "A race to finish as fast as possible. There are no clocks, and the game doesn't count toward your " +
            "stats (best times are kept on Speedrun.com). On the website the controls change; the rules don't, " +
            "and this app plays it like a normal game.",
        { it.speedrun }, { o, v -> o.copy(speedrun = v) },
    ),
    OptionRow(
        "Bottom-deck blind play",
        "When one card is left in the deck, a player may play it blind, without seeing it.",
        { it.deckPlays }, { o, v -> o.copy(deckPlays = v) },
    ),
    OptionRow(
        "Empty clues",
        "Allow clues that touch no cards.",
        { it.emptyClues }, { o, v -> o.copy(emptyClues = v) },
    ),
    OptionRow(
        "One extra card",
        "Everyone is dealt one more card. Easier with few players, harder with many. (Cancels out One less card.)",
        { it.oneExtraCard }, { o, v -> o.copy(oneExtraCard = v, oneLessCard = if (v) false else o.oneLessCard) },
    ),
    OptionRow(
        "One less card",
        "Everyone is dealt one fewer card. Easier with many players, harder with few. (Cancels out One extra card.)",
        { it.oneLessCard }, { o, v -> o.copy(oneLessCard = v, oneExtraCard = if (v) false else o.oneExtraCard) },
    ),
    OptionRow(
        "All or nothing",
        "The game doesn't end when the deck runs out; play on until every stack is complete. It ends with a " +
            "score of 0 on 3 strikes, if a critical card is discarded, or if a player has no cards and no clues are left.",
        { it.allOrNothing }, { o, v -> o.copy(allOrNothing = v) },
    ),
    OptionRow(
        "Detrimental characters",
        "Each player gets a character with an extra restriction, such as only being allowed to give certain " +
            "clues. The server enforces them; this app shows each player's character.",
        { it.detrimentalCharacters }, { o, v -> o.copy(detrimentalCharacters = v) },
    ),
)

/** The special game options, each a switch with a short description of what it changes. */
@Composable
fun TableOptionsDialog(opts: TableOpts, onOpts: (TableOpts) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Special options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                optionRows.forEach { row ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleSmall)
                            Text(row.text, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = row.get(opts), onCheckedChange = { onOpts(row.set(opts, it)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

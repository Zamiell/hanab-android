package live.hanab.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.hanab.client.BotStore
import live.hanab.client.NativeOffline
import live.hanab.client.game.Variants

/**
 * Choose who to play against offline: the table size, the bots' conventions and the variant, with the less common
 * choices (fast mode, a seed) behind Options.
 */
@Composable
fun OfflineDialog(
    onStart: (players: Int, convention: String, variant: String, fast: Boolean, seed: String) -> Unit,
    onStats: () -> Unit,
    onDismiss: () -> Unit,
) {
    var players by remember { mutableIntStateOf(3) }
    var convention by remember { mutableStateOf(BotStore.convention) }
    var variant by remember { mutableStateOf("No Variant") }
    var fast by remember { mutableStateOf(BotStore.fast) }
    var seed by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play offline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Players", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    (NativeOffline.MIN_PLAYERS..NativeOffline.MAX_PLAYERS).forEach { n ->
                        FilterChip(selected = players == n, onClick = { players = n }, label = { Text("$n") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bots play", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    ConventionPicker(convention) { convention = it }
                }
                VariantChooser(variant, Variants.botSupported()) { variant = it }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { showOptions = !showOptions }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Options", style = MaterialTheme.typography.labelLarge)
                        if (!showOptions) {
                            Text(
                                (if (fast) "Fast mode" else "Thinking delay") + " · " +
                                    (if (seed.isBlank()) "next seed" else "seed $seed"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(if (showOptions) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
                }
                if (showOptions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fast, onCheckedChange = { fast = it })
                        Text("Fast mode (no thinking delay)")
                    }
                    OutlinedTextField(
                        value = seed, onValueChange = { seed = it }, singleLine = true,
                        label = { Text("Seed") },
                        placeholder = { Text("next one you have not played") },
                        supportingText = { Text("The same cards as on hanab.live. A whole seed (p3v0s258) also sets the players and variant.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(players, convention, variant, fast, seed); onDismiss() }) { Text("Start") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onStats(); onDismiss() }) { Text("Offline stats") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

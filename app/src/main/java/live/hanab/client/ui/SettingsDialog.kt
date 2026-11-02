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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.hanab.client.AnimSpeed
import live.hanab.client.AppSettings
import live.hanab.client.OutlineStyle
import live.hanab.client.SoundMode

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow("Open clue panel on my turn", AppSettings.autoClue, AppSettings::changeAutoClue)
                SwitchRow("Fireworks background", AppSettings.backdrop, AppSettings::changeBackdrop)
                SwitchRow("Use simplified card display", AppSettings.simplifiedCards, AppSettings::changeSimplifiedCards)
                SwitchRow("Suit symbols", AppSettings.pips, AppSettings::changePips)
                SwitchRow("Vibration", AppSettings.haptics, AppSettings::changeHaptics)
                SwitchRow("Keep screen on in games", AppSettings.keepAwake, AppSettings::changeKeepAwake)

                Label("Sounds")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundMode.entries.forEach { m ->
                        FilterChip(AppSettings.soundMode == m, { AppSettings.changeSoundMode(m) }, { Text(m.label) })
                    }
                }
                Label("Volume ${AppSettings.volume}%")
                Slider(
                    value = AppSettings.volume.toFloat(),
                    onValueChange = { AppSettings.changeVolume(it.toInt()) },
                    valueRange = 0f..100f,
                )

                Label("Animation speed")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimSpeed.entries.forEach { a ->
                        FilterChip(AppSettings.animSpeed == a, { AppSettings.changeAnimSpeed(a) }, { Text(a.label) })
                    }
                }

                Label("Number outline")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlineStyle.entries.forEach { o ->
                        FilterChip(AppSettings.outline == o, { AppSettings.changeOutline(o) }, { Text(o.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

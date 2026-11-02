@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package live.hanab.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import live.hanab.client.game.Variants
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import live.hanab.client.LobbyUiState
import live.hanab.client.LobbyViewModel
import live.hanab.client.net.Table

@Composable
fun LobbyScreen(ui: LobbyUiState, vm: LobbyViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    // Leaving the PMs tab closes the open conversation, so new messages count as unread again.
    LaunchedEffect(tab) { if (tab != 3) vm.openPm(null) }
    val pmBadge = if (ui.pmUnread.isEmpty()) "" else " (${ui.pmUnread.size})"
    val titles = listOf("Tables (${ui.tables.size})", "Chat", "Users (${ui.users.size})", "PMs$pmBadge")

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(ui.me?.username ?: "", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live.hanab.client.NativeOffline.available) {
                        var offline by remember { mutableStateOf(false) }
                        TextButton(onClick = { offline = true }) { Text("Offline") }
                        if (offline) OfflineDialog(onStart = vm::startOffline, onStats = vm::openOfflineStats, onDismiss = { offline = false })
                    }
                    var showSettings by remember { mutableStateOf(false) }
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                    if (showSettings) SettingsDialog { showSettings = false }
                    TextButton(onClick = vm::logout) { Text("Log out") }
                }
            }
            ui.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { vm.clearError() }.padding(horizontal = 16.dp),
                )
            }
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> TablesTab(ui, vm)
                1 -> ChatTab(ui, vm)
                2 -> UsersTab(ui, onProfile = vm::openProfile) { name -> vm.openPm(name); tab = 3 }
                else -> PmTab(ui, vm)
            }
        }
    }
}

@Composable
private fun TablesTab(ui: LobbyUiState, vm: LobbyViewModel) {
    var creating by remember { mutableStateOf(false) }
    val all = ui.tables.values.sortedWith(compareBy<Table> { it.running }.thenByDescending { it.id })
    // A table in a variant this app cannot play is not offered; the footer says how many are hidden.
    val unsupported = all.filter { !Variants.isSupported(it.variant) && !it.joined }
    val tables = if (live.hanab.client.AppSettings.showUnsupported) all else all - unsupported.toSet()
    Column(Modifier.fillMaxSize()) {
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("New table") }
        if (tables.isEmpty() && unsupported.isEmpty()) Text("No tables right now.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(tables, key = { it.id }) { t -> TableRow(t, vm, supported = Variants.isSupported(t.variant) || t.joined, current = ui.joinedTableID) }
            if (unsupported.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${unsupported.size} table${if (unsupported.size == 1) "" else "s"} in variants not supported on mobile",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { live.hanab.client.AppSettings.changeShowUnsupported(!live.hanab.client.AppSettings.showUnsupported) }) {
                            Text(if (live.hanab.client.AppSettings.showUnsupported) "Hide" else "Show")
                        }
                    }
                }
            }
        }
    }
    if (creating) CreateTableDialog(ui, vm) { creating = false }
}

@Composable
private fun CreateTableDialog(ui: LobbyUiState, vm: LobbyViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(ui.me?.randomTableName ?: "") }
    var opts by remember { mutableStateOf(live.hanab.client.net.TableOpts(variantName = Variants.quick.first())) }
    var maxPlayers by remember { mutableIntStateOf(5) }
    var password by remember { mutableStateOf(live.hanab.client.AppSettings.lastPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New table") },
        text = {
            TableForm(
                name, { name = it }, opts, { opts = it }, maxPlayers, { maxPlayers = it },
                password = password, onPassword = { password = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.createTable(name, opts, maxPlayers, password); onDismiss() }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
/** A small padlock, drawn directly so it does not depend on an icon font. */
@Composable
private fun LockIcon(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        // The shackle: an arch over the body.
        drawArc(
            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.04f), size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.7f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.13f),
        )
        // The body, with a keyhole.
        drawRoundRect(
            color, topLeft = Offset(w * 0.1f, h * 0.42f), size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.54f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
        )
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.62f))
        drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(w * 0.46f, h * 0.62f), size = androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.2f))
    }
}

@Composable
private fun TableRow(t: Table, vm: LobbyViewModel, supported: Boolean = true, current: Int? = null) {
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(live.hanab.client.AppSettings.lastPassword) }
    Column(Modifier.fillMaxWidth().alpha(if (supported) 1f else 0.55f).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${t.variant} - ${t.numPlayers}/${t.maxPlayers} players" +
                        (if (t.running) (if (t.sharedReplay) " - replay" else " - in progress") else "") +
                        (if (t.passwordProtected) " - locked" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(t.players.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                if (!supported) {
                    Text(
                        "Not supported on mobile - play this variant on the website",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB300),
                    )
                }
            }
            // No Join / Watch for a variant the app cannot play: its clue and card logic would be wrong.
            // The server keeps a finished game's seats listed as a shared replay, so "joined" stays true after
            // we leave it; it cannot be re-attended or left from here, only watched again (which can then be left).
            if (supported && t.running && t.sharedReplay) {
                OutlinedButton(onClick = { vm.spectateTable(t.id) }) { Text("Replay") }
            } else if (supported && t.running && t.joined && t.id != current) {
                OutlinedButton(onClick = { vm.reopenTable(t.id) }) { Text("Open") } // a game we are still seated in
            } else if (supported && t.running && !t.joined) {
                OutlinedButton(onClick = { vm.spectateTable(t.id) }) { Text("Watch") }
            }
            if (!t.running && t.joined && t.id != current) {
                TextButton(onClick = { vm.leaveTableById(t.id) }) { Text("Leave") }
            }
            if (supported && !t.running && !t.joined) {
                // A table with a password: the lock tells you before you tap Join.
                if (t.passwordProtected) LockIcon(Modifier.padding(end = 8.dp).size(18.dp), Color(0xFFFFB300))
                Button(onClick = { if (t.passwordProtected) askPassword = !askPassword else vm.joinTable(t.id) }) {
                    Text("Join")
                }
            }
        }
        if (askPassword) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.joinTable(t.id, password) }) { Text("Go") }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ChatTab(ui: LobbyUiState, vm: LobbyViewModel) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.chat.size) {
        if (ui.chat.isNotEmpty()) listState.scrollToItem(ui.chat.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState) {
            items(ui.chat) { c -> ChatLine(c, ui.me?.username) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.sendChat(draft); draft = "" }) { Text("Send") }
        }
    }
}

@Composable
private fun UsersTab(ui: LobbyUiState, onProfile: (String) -> Unit, onPm: (String) -> Unit) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    val onlineNames = ui.users.values.map { it.name.lowercase() }.toSet()
    // Marked bots first, then everyone else by name; bots that are offline are listed below.
    val users = ui.users.values.sortedWith(compareBy({ !live.hanab.client.BotStore.isBot(it.name) }, { it.name.lowercase() }))
    val offlineBots = live.hanab.client.BotStore.bots.filter { it.lowercase() !in onlineNames }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(users, key = { it.userID }) { u ->
            val bot = live.hanab.client.BotStore.isBot(u.name)
            Column(Modifier.fillMaxWidth().clickable { menuFor = u.name }.padding(vertical = 8.dp)) {
                Text(u.name + if (bot) "  [BOT]" else "")
                Text(
                    statusLabel(u, ui),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        if (offlineBots.isNotEmpty()) {
            item { Text("Offline bots", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(offlineBots, key = { "off-$it" }) { n ->
                Text(
                    "$n  [BOT]",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().clickable { menuFor = n }.padding(vertical = 10.dp),
                )
            }
        }
    }
    menuFor?.let { name ->
        UserMenuDialog(
            name = name,
            online = name.lowercase() in onlineNames,
            onProfile = { onProfile(name) },
            onMessage = { onPm(name) },
            onDismiss = { menuFor = null },
        )
    }
}
/** Conversation list, or one conversation when [LobbyUiState.pmOpen] is set. Tap a user in Users to start one. */
@Composable
private fun PmTab(ui: LobbyUiState, vm: LobbyViewModel) {
    val open = ui.pmOpen
    if (open == null) {
        val names = (ui.pms.keys + ui.pmUnread).toList().sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            Text("No private messages yet. Tap a user in Users, or type /w name message in chat.", Modifier.padding(16.dp))
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(names, key = { it }) { n ->
                val unread = n in ui.pmUnread
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openPm(n) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(n, style = if (unread) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
                    if (unread) Text("new", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.openPm(null) }) { Text("Back") }
                Text(open, style = MaterialTheme.typography.titleMedium)
            }
            ChatPanel(ui.pms[open].orEmpty(), { vm.sendPm(open, it) }, me = ui.me?.username)
        }
    }
}


/**
 * Where a user is, in words. The server sends a number: 0 lobby, 1 pregame, 2 playing, 3 spectating,
 * 4 replay, 5 shared replay. If they are at a table, its name is added; "(away)" marks an inactive user.
 */
private fun statusLabel(u: live.hanab.client.net.User, ui: LobbyUiState): String {
    val what = when (u.status.trim().toIntOrNull()) {
        0 -> "In lobby"
        1 -> "In pregame"
        2 -> "In game"
        3 -> "Spectating"
        4 -> "In replay"
        5 -> "In shared replay"
        else -> u.status.ifBlank { "Online" }
    }
    val table = u.tableID?.let { ui.tables[it]?.name }?.takeIf { u.status.trim() != "0" }
    return what + (if (table != null) " - $table" else "") + (if (u.inactive) " (away)" else "")
}

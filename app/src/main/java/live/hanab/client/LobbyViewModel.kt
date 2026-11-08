package live.hanab.client

import android.app.Application
import android.text.Html
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import live.hanab.client.ui.PipSettings
import live.hanab.client.ui.PipStore
import live.hanab.client.game.GameState
import live.hanab.client.game.MoveEvent
import live.hanab.client.game.NoteEntry
import live.hanab.client.game.Variants
import live.hanab.client.net.ChatList
import live.hanab.client.net.PmHistory
import live.hanab.client.net.ChatMessage
import live.hanab.client.net.ConnState
import live.hanab.client.net.HanabClient
import live.hanab.client.net.HanabJson
import live.hanab.client.net.IdOnly
import live.hanab.client.net.ServerMessage
import live.hanab.client.net.Table
import live.hanab.client.net.TableGone
import live.hanab.client.net.User
import live.hanab.client.net.Welcome

/** Present when the table is a shared replay (a finished game that people can step through together). */
data class ReplayInfo(val databaseID: Int, val leader: String)

data class LobbyUiState(
    val loggedIn: Boolean = false,
    val loggingIn: Boolean = false,
    val error: String? = null,
    val me: Welcome? = null,
    val users: Map<Int, User> = emptyMap(),
    val tables: Map<Int, Table> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    /** Chat of the table we are seated at (pregame and in-game). */
    val tableChat: List<ChatMessage> = emptyList(),
    /** Live plays/discards waiting to be animated (oldest first). */
    val moves: List<MoveEvent> = emptyList(),
    /** Whether we have voted to terminate the current game (the server tells us only our own vote). */
    val terminateVote: Boolean = false,
    /** Latest progress / failure text per bot being invited (key = bot name). */
    val botStatus: Map<String, String> = emptyMap(),
    val draws: List<live.hanab.client.game.DrawEvent> = emptyList(),
    /** True while the connection is down and we are signing back in; the screen stays as it was. */
    val reconnecting: Boolean = false,
    val replay: ReplayInfo? = null,
    /** Segment the replay leader is on (equals an index into GameState.turnStops). */
    val sharedSegment: Int? = null,
    /** Private-message conversations keyed by the other user's name, oldest first. */
    val pms: Map<String, List<ChatMessage>> = emptyMap(),
    val pmUnread: Set<String> = emptySet(),
    /** Conversation currently on screen, so its new messages are not counted as unread. */
    val pmOpen: String? = null,
    val connection: ConnState = ConnState.Disconnected,
    /** Table we are seated at (pregame or in progress). */
    val joinedTableID: Int? = null,
    val game: GameState? = null,
    /** Playing against bots on this phone: there is no server, so no lobby, chat or reconnecting. */
    val offline: Boolean = false,
    /** Player whose profile (history and stats) is on screen. */
    val profile: String? = null,
    /** The stats and games from offline play are on screen. */
    val offlineStats: Boolean = false,
    /** The id of the offline game that has just been kept, so it can be analysed from its end panel. */
    val lastOfflineGame: Long? = null,
    /** The bot's analysis of a game: choosing conventions, running, or its result. */
    val analysis: AnalysisUi? = null,
    /** The website's id of the game or replay on screen (0 if it has none), so it can be analysed. */
    val replayDatabaseID: Int = 0,
    /** The id of the saved offline game being replayed, so it can be analysed. */
    val offlineReplayId: Long? = null,
)

class LobbyViewModel(app: Application) : AndroidViewModel(app) {
    val client = HanabClient()
    private val creds = CredentialStore(app)

    private val _ui = MutableStateFlow(LobbyUiState())
    val ui: StateFlow<LobbyUiState> = _ui

    val savedUsername: String get() = creds.username

    private val botPower = BotPower(app)

    override fun onCleared() {
        botPower.stop()
    }

    init {
        Variants.load(app)
        botPower.start()
        PipStore.init(app)
        AppSettings.load(app)
        BotStore.load(app)
        live.hanab.client.game.Characters.load(app)
        GameSounds.init(app)
        KeepAliveService.onExpired = ::onBackgroundTimeout
        creds.password()?.let { pw -> if (creds.username.isNotBlank()) login(creds.username, pw) }
        viewModelScope.launch { client.messages.collect(::handle) }
        viewModelScope.launch { client.errors.collect { e -> _ui.update { it.copy(error = e) } } }
        viewModelScope.launch {
            client.state.collect { s ->
                val dropped = s == ConnState.Disconnected && _ui.value.loggedIn && !_ui.value.offline
                if (dropped && suspended) {
                    // We closed the socket ourselves after the app sat in the background too long.
                    _ui.update { it.copy(connection = s) }
                } else if (dropped && creds.password() != null) {
                    // Keep the current screen (even a game in progress) and sign back in behind it.
                    _ui.update { it.copy(connection = s, reconnecting = true) }
                    startReconnect()
                } else {
                    _ui.update {
                        // No saved login: a dropped socket returns to the login screen.
                        if (dropped) LobbyUiState(error = it.error ?: "Disconnected") else it.copy(connection = s)
                    }
                }
            }
        }
    }

    fun openProfile(name: String) = _ui.update { it.copy(profile = name) }

    fun closeProfile() = _ui.update { it.copy(profile = null) }

    /** Watch a finished game from the history, on our own (a solo replay); the game screen takes over when it loads. */
    fun openReplay(databaseID: Int) {
        client.send("replayCreate", buildJsonObject {
            put("source", "id")
            put("databaseID", databaseID)
            put("visibility", "solo")
        })
    }

    /** Set while an offline game interrupts a signed-in session: the connection is restored afterwards. */
    private var resumeOnline = false

    /**
     * Sit down with bots on this phone. [players] counts you; [fast] is the bots' fast mode (no thinking
     * pause). Signing in is not needed; if you are signed in, the connection is closed for the game and
     * restored when you leave it.
     */
    fun startOffline(players: Int, convention: String, variant: String, fast: Boolean, seed: String) {
        val app = getApplication<Application>()
        // A whole seed as the website shows it (p3v0s258) says its own players and variant; the rest is the suffix.
        val full = Regex("""^p(\d+)v(\d+)s(.+)$""").find(seed.trim())
        val seedPlayers = full?.groupValues?.get(1)?.toInt()
        val seedVariant = full?.groupValues?.get(2)?.toInt()?.let { Variants.nameOfId(it) }
        if (full != null && (seedPlayers !in NativeOffline.MIN_PLAYERS..NativeOffline.MAX_PLAYERS || seedVariant == null || seedVariant !in Variants.botSupported())) {
            _ui.update {
                it.copy(error = "Offline games are for ${NativeOffline.MIN_PLAYERS} to ${NativeOffline.MAX_PLAYERS} players in a variant the bots can play; that seed is not one (${seed.trim()}).")
            }
            return
        }
        val players = seedPlayers ?: players
        val variant = seedVariant ?: variant
        val seed = full?.groupValues?.get(3) ?: seed
        val variants = app.assets.open("variants.json").bufferedReader().use { it.readText() }
        val suits = app.assets.open("suits.json").bufferedReader().use { it.readText() }
        val config = buildJsonObject {
            put("variant", variant)
            put("players", players)
            put("convention", convention)
            // The same seed deals the same cards as the website does for it; none given: the next one not yet played.
            put("seed", seed.trim().ifEmpty { nextUnplayedSeed(OfflineStore.all(app), players, variant) })
            put("humanName", "You")
            put("paceMs", if (fast) 900 else 2500)
        }
        BotStore.remember(convention, fast)
        enterOffline(client.startOffline(viewModelScope, variants, suits, config.toString()))
    }

    /** Watch a finished offline game again; leaving it returns to the offline stats. */
    fun openOfflineReplay(id: Long) {
        val record = OfflineStore.record(getApplication(), id)
        if (record == null) {
            _ui.update { it.copy(error = "That saved game could not be found.") }
            return
        }
        statsAfterOffline = true
        enterOffline(client.startOfflineReplay(viewModelScope, record))
        if (_ui.value.offline) _ui.update { it.copy(offlineReplayId = id) }
    }

    // ---------------------------------------------------------------- analysis (the bot, on this phone)

    private var analysisJob: Job? = null

    /**
     * Opens the analysis of a game: the one kept from before if there is one, otherwise the offer to run it (nothing
     * runs until [startAnalysis]).
     */
    fun askAnalysis(request: AnalysisRequest) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val kept = withContext(kotlinx.coroutines.Dispatchers.IO) { AnalysisStore.load(app, request) }
            _ui.update { it.copy(analysis = kept ?: AnalysisUi.Setup(request)) }
        }
    }

    /** Offers to run the analysis again, say with other conventions. */
    fun analyzeAgain(request: AnalysisRequest) = _ui.update { it.copy(analysis = AnalysisUi.Setup(request)) }

    /** Opens the replay of an analysed game from its analysis; the analysis then shows under the hands. */
    fun watchAnalysed(request: AnalysisRequest) {
        if (request.online) openReplay(request.id.toInt()) else openOfflineReplay(request.id)
    }

    fun closeAnalysis() {
        analysisJob?.cancel()
        _ui.update { it.copy(analysis = null) }
    }

    /** Goes through the game with the bot using [convention]; works for games of the website and offline games. */
    fun startAnalysis(convention: String) {
        val request = (_ui.value.analysis as? AnalysisUi.Setup)?.request ?: return
        fun fail(message: String) = _ui.update { it.copy(analysis = AnalysisUi.Failed(request, message)) }
        if (!NativeOffline.available) return fail("The bot's analysis is not available on this phone.")
        if (request.variant !in Variants.botSupported()) return fail("The bot cannot play ${request.variant}, so it cannot analyze it.")
        BotStore.remember(convention, BotStore.fast)
        _ui.update { it.copy(analysis = AnalysisUi.Running(request, convention, System.currentTimeMillis())) }
        val app = getApplication<Application>()
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            var handle = 0L
            try {
                val game = if (request.online) live.hanab.client.net.ProfileApi.export(request.id)
                else withContext(kotlinx.coroutines.Dispatchers.IO) { OfflineStore.record(app, request.id) }
                    ?: return@launch fail("That saved game could not be found.")
                handle = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val variants = app.assets.open("variants.json").bufferedReader().use { it.readText() }
                    val suits = app.assets.open("suits.json").bufferedReader().use { it.readText() }
                    NativeOffline.analyzeStart(variants, suits, game, request.variant, convention)
                }
                if (handle == 0L) return@launch fail("The analysis could not start.")
                while (true) {
                    val reply = NativeOffline.analyzePoll(handle)
                    if (reply.startsWith("result ")) {
                        val comments = parseAnalysis(reply.removePrefix("result "))
                        withContext(kotlinx.coroutines.Dispatchers.IO) { AnalysisStore.save(app, request, convention, comments) }
                        _ui.update { it.copy(analysis = AnalysisUi.Done(request, convention, comments)) }
                        return@launch
                    }
                    val (done, total) = reply.removePrefix("progress ").split(' ').map { it.toIntOrNull() ?: 0 }
                    _ui.update { s -> (s.analysis as? AnalysisUi.Running)?.let { s.copy(analysis = it.copy(done = done, total = total)) } ?: s }
                    kotlinx.coroutines.delay(250)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Closing the analysis stops the bot between moves.
                if (handle != 0L) NativeOffline.analyzeCancel(handle)
                throw e
            } catch (e: Exception) {
                fail(if (request.online) "Could not analyze the game: ${e.message}" else (e.message ?: "The analysis failed."))
            }
        }
    }

    fun openOfflineStats() = _ui.update { it.copy(offlineStats = true) }

    fun closeOfflineStats() = _ui.update { it.copy(offlineStats = false) }

    /** Whether to show the offline stats when the offline game or replay that is on screen is left. */
    private var statsAfterOffline = false

    /** Switches to the offline table that [error] == null says has just been started (else shows the error). */
    private fun enterOffline(error: String?) {
        if (error != null) {
            statsAfterOffline = false
            _ui.update { it.copy(error = error) }
            return
        }
        val wasOnline = _ui.value.loggedIn && !_ui.value.offline
        // The offline screen first, so closing the socket below is not mistaken for a dropped connection.
        // The analysis stays: watching a game from its analysis shows the analysis under the replay.
        val analysis = _ui.value.analysis
        _ui.value = LobbyUiState(
            me = Welcome(username = "You"), loggedIn = true, offline = true, connection = ConnState.Connected, analysis = analysis,
        )
        if (wasOnline) {
            resumeOnline = true
            reconnectJob?.cancel()
            client.disconnect()
        }
    }

    private fun exitOffline() {
        // Clear the screen first, so the table closing is not mistaken for a dropped connection.
        _ui.value = LobbyUiState(offlineStats = statsAfterOffline)
        statsAfterOffline = false
        client.stopOffline()
        if (resumeOnline) {
            resumeOnline = false
            creds.password()?.let { pw -> if (creds.username.isNotBlank()) login(creds.username, pw) }
        }
    }

    private var reconnectJob: Job? = null

    /** True once the connection was closed on purpose because the app stayed out of sight; cleared on return. */
    private var suspended = false

    /** The app has been in the background for the whole keep-alive period: close the connection quietly. */
    fun onBackgroundTimeout() {
        if (!_ui.value.loggedIn || _ui.value.offline) return
        suspended = true
        reconnectJob?.cancel()
        _ui.update { it.copy(reconnecting = false) }
        client.disconnect()
    }

    /** Retries sign-in with growing pauses until the server answers with "welcome". */
    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (_ui.value.reconnecting) {
                delay(minOf(1000L shl attempt.coerceAtMost(4), 15_000L))
                attempt++
                val pw = creds.password() ?: break
                val err = client.login(creds.username, pw)
                when {
                    err == null -> {
                        client.connect()
                        delay(8_000) // give the socket time to deliver "welcome", which ends reconnecting
                    }
                    err.startsWith("Network error") -> Unit // offline: keep trying
                    else -> { // the server rejected the saved password
                        creds.clearPassword()
                        _ui.value = LobbyUiState(error = err)
                        break
                    }
                }
            }
        }
    }

    /** Called when the app returns to the foreground; the phone may have dropped the socket meanwhile. */
    fun onForeground() {
        suspended = false
        val s = _ui.value
        if (s.loggedIn && !s.offline && client.state.value == ConnState.Disconnected && !s.reconnecting && creds.password() != null) {
            _ui.update { it.copy(reconnecting = true) }
            startReconnect()
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _ui.update { it.copy(error = "Enter a username and password") }
            return
        }
        _ui.update { it.copy(loggingIn = true, error = null) }
        viewModelScope.launch {
            val err = client.login(username.trim(), password)
            if (err != null) {
                // A rejected password must not be retried on every launch; network errors keep it.
                if (!err.startsWith("Network error")) creds.clearPassword()
                _ui.update { it.copy(loggingIn = false, error = err) }
            } else {
                creds.save(username.trim(), password)
                client.connect()
            }
        }
    }

    fun logout() {
        creds.clearPassword()
        client.disconnect()
        _ui.value = LobbyUiState()
    }

    private val pmAliases = setOf("pm", "w", "whisper", "msg", "tell", "t")

    /** Shows [text] where the user is looking: table chat during a table, otherwise the error line. */
    private fun notice(text: String) {
        _ui.update {
            if (it.joinedTableID != null) it.copy(tableChat = it.tableChat + ChatMessage(msg = text, who = "server", source = "server"))
            else it.copy(error = text)
        }
    }

    /** Handles "/pm Alice hi" (and aliases), which the server only understands as a "chatPM" command. */
    private fun tryPmCommand(text: String, room: String): Boolean {
        if (!text.startsWith("/")) return false
        val parts = text.drop(1).trim().split(Regex("\\s+"), limit = 3)
        if (parts[0].lowercase() !in pmAliases) return false
        val recipient = parts.getOrNull(1).orEmpty()
        val msg = parts.getOrNull(2).orEmpty().trim()
        val state = _ui.value
        when {
            recipient.isEmpty() || msg.isEmpty() -> notice("The format of a private message is: /w Alice hello")
            recipient.equals(state.me?.username, ignoreCase = true) -> notice("You cannot send a private message to yourself.")
            else -> {
                val user = state.users.values.firstOrNull { it.name.equals(recipient, ignoreCase = true) }
                if (user == null) notice("User \"$recipient\" is not currently online.")
                else client.send("chatPM", buildJsonObject {
                    put("msg", msg)
                    put("recipient", user.name)
                    put("room", room)
                })
            }
        }
        return true
    }

    // ---------------------------------------------------------------- bots

    /** Password of the table we created or joined, if any; "/join" needs it for protected tables. */
    var tablePassword: String = ""
        private set

    private enum class InviteStage { Settings, Fast, Join }

    private class Invite(val fast: Boolean, val password: String, var stage: InviteStage, var job: Job? = null)

    private val invites = HashMap<String, Invite>()

    private fun setBotStatus(bot: String, text: String?) = _ui.update {
        it.copy(botStatus = if (text == null) it.botStatus - bot else it.botStatus + (bot to text))
    }

    /**
     * Ask a bot to join our table. The bot is configured by PM first: "/settings <convention>" (its
     * reply says whether fast mode is on), then "/fastmode" only if that differs from what we want
     * (it is a toggle), then "/join [password]".
     */
    fun inviteBot(bot: String, convention: String, fast: Boolean, password: String) {
        BotStore.remember(convention, fast)
        val key = bot.lowercase()
        invites.remove(key)?.job?.cancel()
        val inv = Invite(fast, password, InviteStage.Settings)
        invites[key] = inv
        setBotStatus(bot, "Setting $convention...")
        sendPm(bot, "/settings $convention")
        inv.job = viewModelScope.launch {
            delay(6_000)
            if (invites[key] === inv && inv.stage != InviteStage.Join) {
                setBotStatus(bot, "No reply from $bot; trying to join anyway")
                joinStep(bot, inv)
            }
        }
    }

    private fun joinStep(bot: String, inv: Invite) {
        inv.job?.cancel()
        inv.stage = InviteStage.Join
        setBotStatus(bot, "Joining...")
        sendPm(bot, if (inv.password.isNotEmpty()) "/join ${inv.password}" else "/join")
        inv.job = viewModelScope.launch {
            delay(8_000)
            // A bot that joined shows up in the table list; anything else stays as its last status.
            if (invites[bot.lowercase()] === inv) { invites.remove(bot.lowercase()); setBotStatus(bot, null) }
        }
    }

    /** Called for every PM we receive; moves an invite along when the bot answers. */
    private fun onBotReply(bot: String, text: String) {
        val inv = invites[bot.lowercase()] ?: return
        when (inv.stage) {
            InviteStage.Settings -> when {
                text.startsWith("Currently playing with") -> {
                    if (replyShowsFastMode(text) != inv.fast) {
                        inv.stage = InviteStage.Fast
                        setBotStatus(bot, if (inv.fast) "Turning fast mode on..." else "Turning fast mode off...")
                        sendPm(bot, "/fastmode")
                    } else joinStep(bot, inv)
                }
                text.startsWith("Unrecognized convention") -> {
                    inv.job?.cancel(); invites.remove(bot.lowercase()); setBotStatus(bot, text)
                }
            }
            InviteStage.Fast -> if (text.startsWith("Fast mode is now")) joinStep(bot, inv)
            InviteStage.Join -> {
                // e.g. "Could not join, as you are not in a room." / "Room is password protected..."
                if (text.startsWith("Could not") || text.startsWith("Room is password")) {
                    inv.job?.cancel(); invites.remove(bot.lowercase()); setBotStatus(bot, text)
                }
            }
        }
    }

    fun kickBot(bot: String) = sendPm(bot, "/leave")

    /** Table-wide bot commands go through the table chat, where every bot at the table hears them. */
    fun setAllBots(convention: String) = sendTableChat("/setall $convention")
    fun leaveAllBots() = sendTableChat("/leaveall")

    fun dismissBotStatus(bot: String) = setBotStatus(bot, null)

    fun sendPm(recipient: String, text: String) {
        if (text.isBlank()) return
        client.send("chatPM", buildJsonObject {
            put("msg", text)
            put("recipient", recipient)
            put("room", "lobby")
        })
    }

    fun openPm(name: String?) {
        _ui.update { it.copy(pmOpen = name, pmUnread = if (name == null) it.pmUnread else it.pmUnread - name) }
        if (name != null) client.send("chatPMHistoryGet", buildJsonObject {
            put("amount", 50)
            put("username", name)
        })
    }

    private fun addPm(c: ChatMessage) {
        val me = _ui.value.me?.username ?: return
        val other = if (c.who.equals(me, ignoreCase = true)) c.recipient.orEmpty() else c.who.orEmpty()
        if (other.isEmpty()) return
        _ui.update { s ->
            val merged = mergePms(s.pms[other].orEmpty(), listOf(c))
            val incoming = !c.who.equals(me, ignoreCase = true)
            var next = s.copy(
                pms = s.pms + (other to merged),
                pmUnread = if (incoming && s.pmOpen != other) s.pmUnread + other else s.pmUnread,
            )
            // Mid-game the PM tab is out of sight, so also show it in the table chat.
            if (s.joinedTableID != null) {
                val label = if (incoming) "PM from $other" else "PM to $other"
                next = next.copy(tableChat = next.tableChat + c.copy(who = label))
            }
            next
        }
        // Outside the state update above: handling a reply can send messages and change state itself.
        if (!c.who.equals(me, ignoreCase = true)) onBotReply(other, plain(c.msg))
    }

    private fun mergePms(old: List<ChatMessage>, new: List<ChatMessage>): List<ChatMessage> =
        (old + new).distinctBy { it.pmID ?: "${it.who}|${it.datetime}|${it.msg}" }
            .sortedWith(compareBy({ it.pmID ?: Int.MAX_VALUE }, { it.datetime }))

    fun sendChat(text: String) {
        if (text.isBlank()) return
        if (tryPmCommand(text.trim(), "lobby")) return
        client.send("chat", buildJsonObject {
            put("msg", text)
            put("room", "lobby")
        })
    }

    fun sendTableChat(text: String) {
        val id = _ui.value.joinedTableID ?: return
        if (text.isBlank()) return
        if (tryPmCommand(text.trim(), "table$id")) return
        client.send("chat", buildJsonObject {
            put("msg", text)
            put("room", "table$id")
        })
    }

    fun joinTable(id: Int, password: String = "") {
        tablePassword = password
        AppSettings.rememberPassword(password)
        client.send("tableJoin", buildJsonObject {
            put("tableID", id)
            put("password", password)
        })
    }

    /** Change the options of the table we are at (the server only accepts this from the table owner). */
    fun updateTable(name: String, opts: live.hanab.client.net.TableOpts, maxPlayers: Int) {
        val table = _ui.value.joinedTableID?.let { _ui.value.tables[it] } ?: return
        client.send("tableUpdate", buildJsonObject {
            put("tableID", table.id)
            put("name", name.ifBlank { table.name })
            put("maxPlayers", maxPlayers)
            put("options", opts.toJson())
        })
    }

    fun createTable(name: String, opts: live.hanab.client.net.TableOpts, maxPlayers: Int, password: String = "") {
        client.send("tableCreate", buildJsonObject {
            put("name", name.ifBlank { _ui.value.me?.randomTableName ?: "Table" })
            put("maxPlayers", maxPlayers)
            if (password.isNotEmpty()) put("password", password)
            tablePassword = password
            AppSettings.rememberPassword(password)
            put("options", opts.toJson())
        })
    }

    fun startTable() {
        val id = _ui.value.joinedTableID ?: return
        client.send("tableStart", buildJsonObject { put("tableID", id) })
    }

    /**
     * Leave the table we are at. Before the game starts that is "tableLeave". During or after a game
     * (including the shared replay a finished game turns into) it is "tableUnattend", which the
     * server treats as leaving the replay: when the last person leaves, the replay is deleted.
     * The game's own table id is used, as the "joined" id can lag behind (e.g. after play again).
     */
    fun leaveTable() {
        if (_ui.value.offline) { exitOffline(); return }
        val ui = _ui.value
        val id = ui.game?.tableID ?: ui.joinedTableID ?: return
        client.send(if (ui.game != null) "tableUnattend" else "tableLeave", buildJsonObject { put("tableID", id) })
        _ui.update {
            it.copy(
                joinedTableID = null, game = null, tableChat = emptyList(), replay = null,
                sharedSegment = null, moves = emptyList(), draws = emptyList(),
            )
        }
    }
    /** Go back into a game we are still seated in. (A finished game cannot be re-attended; see spectateTable.) */
    fun reopenTable(id: Int) {
        client.send("tableReattend", buildJsonObject { put("tableID", id) })
    }

    /** Leave a table that has not started, from the lobby list, without having it open. */
    fun leaveTableById(id: Int) {
        client.send("tableLeave", buildJsonObject { put("tableID", id) })
    }

    /**
     * Start another game with the same players. The server only allows this while every original
     * player is still in the shared replay; otherwise it answers with a warning, shown in the end panel.
     */
    fun restartTable() {
        val id = _ui.value.joinedTableID ?: return
        client.send("tableRestart", buildJsonObject {
            put("tableID", id)
            // true = start the new game immediately with the same players, as the website does.
            put("hidePregame", true)
        })
    }

    /** Watch a running game or a shared replay. */
    fun spectateTable(id: Int) {
        client.send("tableSpectate", buildJsonObject {
            put("tableID", id)
            put("shadowingPlayerIndex", -1)
        })
    }

    /** Shared replay leader only: move everyone to this segment. */
    fun sendReplaySegment(segment: Int) {
        val id = _ui.value.joinedTableID ?: return
        client.send("replayAction", buildJsonObject {
            put("tableID", id)
            put("type", 0)
            put("segment", segment)
        })
    }

    fun setNote(order: Int, text: String) {
        val g = _ui.value.game ?: return
        _ui.update { it.copy(game = it.game?.withNote(order, text)) }
        client.send("note", buildJsonObject {
            put("tableID", g.tableID)
            put("order", order)
            put("note", text.trim())
        })
    }

    /** Toggle our vote to terminate the game; it ends once half the players (both, with two) have voted. */
    fun voteToTerminate() {
        val id = _ui.value.game?.tableID ?: return
        client.send("tableVoteForTermination", buildJsonObject { put("tableID", id) })
    }

    /** End the game right now (it counts as a loss). Any player may do this. */
    fun terminateNow() {
        val id = _ui.value.game?.tableID ?: return
        client.send("tableTerminate", buildJsonObject { put("tableID", id) })
    }

    fun play(order: Int) = sendAction(0, order, 0)

    /** Bottom-deck blind play: play the last card of the deck without seeing it (its order is the highest). */
    fun playDeck() {
        val g = _ui.value.game ?: return
        if (g.deckPlayable) sendAction(0, g.totalCards - 1, 0)
    }
    fun discard(order: Int) = sendAction(1, order, 0)
    fun colorClue(player: Int, color: Int) = sendAction(2, player, color)
    fun rankClue(player: Int, rank: Int) = sendAction(3, player, rank)

    private fun sendAction(type: Int, target: Int, value: Int) {
        val g = _ui.value.game ?: return
        if (!g.ourTurn) return
        client.send("action", buildJsonObject {
            put("tableID", g.tableID)
            put("type", type)
            put("target", target)
            put("value", value)
        })
    }

    private fun beginGame(tableID: Int) {
        client.send("getGameInfo1", buildJsonObject { put("tableID", tableID) })
    }

    private fun onInit(d: JsonObject) {
        val tableID = d["tableID"]?.jsonPrimitive?.intOrNull ?: return
        val names = (d["playerNames"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return
        val variantName = d["options"]?.jsonObject?.get("variantName")?.jsonPrimitive?.content ?: "No Variant"
        val suits = Variants.suits(variantName) ?: listOf("Red", "Yellow", "Green", "Blue", "Purple")
        val state = GameState(
            tableID = tableID,
            playerNames = names,
            ourIndex = d["ourPlayerIndex"]?.jsonPrimitive?.intOrNull ?: 0,
            spectating = (d["spectating"] as? JsonPrimitive)?.content == "true" ||
                (d["replay"] as? JsonPrimitive)?.content == "true",
            variantName = variantName,
            suits = suits,
            suitInfos = suits.map(Variants::info),
            rules = Variants.rules(variantName),
        )
        // The special options of this game, and each player's detrimental character if there are any.
        val opts = d["options"] as? JsonObject
        fun flag(k: String) = (opts?.get(k) as? JsonPrimitive)?.content == "true"
        val gameOptions = live.hanab.client.game.GameOptions(
            emptyClues = flag("emptyClues"), cardCycle = flag("cardCycle"), deckPlays = flag("deckPlays"),
            allOrNothing = flag("allOrNothing"), detrimentalCharacters = flag("detrimentalCharacters"),
            speedrun = flag("speedrun"), timed = flag("timed"),
        )
        val assignments = d["characterAssignments"] as? JsonArray
        val metadata = d["characterMetadata"] as? JsonArray
        val characters = if (gameOptions.detrimentalCharacters && assignments != null) {
            assignments.mapIndexed { i, e ->
                val id = (e as? JsonPrimitive)?.intOrNull ?: -1
                val meta = (metadata?.getOrNull(i) as? JsonPrimitive)?.intOrNull ?: -1
                live.hanab.client.game.Characters.describe(id, meta, state.clueColors, suits)
            }
        } else emptyList()
        val stateWithOptions = state.copy(options = gameOptions, characters = characters)
        val shared = (d["sharedReplay"] as? JsonPrimitive)?.content == "true"
        val replay = if (shared) ReplayInfo(
            d["databaseID"]?.jsonPrimitive?.intOrNull ?: 0,
            d["sharedReplayLeader"]?.jsonPrimitive?.content.orEmpty(),
        ) else null
        _ui.update { it.copy(lastOfflineGame = null, replayDatabaseID = d["databaseID"]?.jsonPrimitive?.intOrNull ?: 0, game = stateWithOptions, joinedTableID = tableID, replay = replay, sharedSegment = null, terminateVote = false) }
        client.send("getGameInfo2", buildJsonObject { put("tableID", tableID) })
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private inline fun <reified T> ServerMessage.decode(): T? =
        data?.let { runCatching { HanabJson.decodeFromJsonElement<T>(it) }.getOrNull() }

    private fun handle(m: ServerMessage) {
        try {
            dispatch(m)
        } catch (e: Exception) {
            // One bad message must not stop the collector, or the whole UI freezes.
            android.util.Log.e("Hanab", "Failed handling ${m.command}: ${m.data}", e)
        }
    }

    private fun dispatch(m: ServerMessage) {
        if (m.command.startsWith("game") || m.command == "init" || m.command == "tableStart") {
            android.util.Log.d("Hanab", "<- ${m.command} ${m.data.toString().take(300)}")
        }
        when (m.command) {
            "welcome" -> m.decode<Welcome>()?.let { w ->
                val watching = w.disconSpectatingTable?.takeIf { it > 0 }
                _ui.update { s ->
                    var next = s.copy(me = w, loggedIn = true, loggingIn = false, reconnecting = false)
                    // After a reconnect the server may no longer have us at the table our screen shows:
                    // it removes a player from an unstarted table when the connection drops. It keeps
                    // our seat in a started game ("playing") and remembers a table we were watching.
                    val shown = s.game?.tableID ?: s.joinedTableID
                    if (shown != null && shown !in w.playingAtTables && shown != watching) {
                        next = next.copy(
                            joinedTableID = null, game = null, tableChat = emptyList(), replay = null,
                            sharedSegment = null, moves = emptyList(), draws = emptyList(),
                            error = "You were removed from your table when the connection dropped.",
                        )
                    }
                    next
                }
                // Rejoin a game we were already playing (e.g. after the app was killed).
                // The server ties a seat to the websocket that sat down, so we must take it over with
                // "tableReattend"; it answers with "tableStart", which loads the game.
                val playing = w.playingAtTables.firstOrNull()
                if (playing != null) {
                    client.send("tableReattend", buildJsonObject { put("tableID", playing) })
                } else if (watching != null) {
                    // Go back to the game we were watching (the website does the same).
                    client.send("tableSpectate", buildJsonObject {
                        put("tableID", watching)
                        put("shadowingPlayerIndex", w.disconShadowingSeat ?: -1)
                    })
                }
            }
            "userList" -> (m.data as? JsonArray)?.let { arr ->
                val users = arr.mapNotNull { runCatching { HanabJson.decodeFromJsonElement<User>(it) }.getOrNull() }
                _ui.update { it.copy(users = users.associateBy { u -> u.userID }) }
            }
            "user" -> m.decode<User>()?.let { u -> _ui.update { it.copy(users = it.users + (u.userID to u)) } }
            "userLeft" -> m.decode<IdOnly>()?.let { l -> _ui.update { it.copy(users = it.users - l.userID) } }
            "tableList" -> (m.data as? JsonArray)?.let { arr ->
                val tables = arr.mapNotNull { runCatching { HanabJson.decodeFromJsonElement<Table>(it) }.getOrNull() }
                _ui.update { it.copy(tables = tables.associateBy { t -> t.id }) }
            }
            "table" -> m.decode<Table>()?.let { t -> _ui.update { it.copy(tables = it.tables + (t.id to t)) } }
            "tableGone" -> m.decode<TableGone>()?.let { g -> _ui.update { it.copy(tables = it.tables - g.tableID) } }
            "chatList" -> m.decode<ChatList>()?.let { l ->
                // Lobby history and table history arrive in the same command, tagged by room.
                val lobby = l.list.filter(::isLobby)
                val table = l.list.filter(::isOurTable)
                _ui.update {
                    it.copy(
                        chat = if (lobby.isNotEmpty() || table.isEmpty()) lobby else it.chat,
                        tableChat = if (table.isNotEmpty()) table else it.tableChat,
                    )
                }
            }
            "chatPMHistory" -> m.decode<PmHistory>()?.let { h ->
                val me = _ui.value.me?.username
                h.list.groupBy { if (it.who.equals(me, ignoreCase = true)) it.recipient.orEmpty() else it.who.orEmpty() }
                    .forEach { (other, msgs) ->
                        if (other.isNotEmpty()) _ui.update { s -> s.copy(pms = s.pms + (other to mergePms(s.pms[other].orEmpty(), msgs))) }
                    }
            }
            "chat" -> m.decode<ChatMessage>()?.let { c ->
                if (!c.recipient.isNullOrEmpty()) addPm(c)
                else if (isLobby(c)) _ui.update { it.copy(chat = (it.chat + c).takeLast(300)) }
                else if (isOurTable(c)) _ui.update { it.copy(tableChat = (it.tableChat + c).takeLast(300)) }
            }
            "joined" -> (m.data as? JsonObject)?.get("tableID")?.jsonPrimitive?.intOrNull?.let { id ->
                // A different table (e.g. after "play again") means the old game screen must go.
                _ui.update {
                    if (it.game != null && it.game.tableID != id)
                        it.copy(joinedTableID = id, game = null, replay = null, sharedSegment = null, tableChat = emptyList())
                    else it.copy(joinedTableID = id)
                }
            }
            "finishOngoingGame" -> (m.data as? JsonObject)?.let { d ->
                _ui.update {
                    it.copy(replay = ReplayInfo(
                        d["databaseID"]?.jsonPrimitive?.intOrNull ?: 0,
                        d["sharedReplayLeader"]?.jsonPrimitive?.content.orEmpty(),
                    ))
                }
            }
            "replaySegment" -> (m.data as? JsonObject)?.get("segment")?.jsonPrimitive?.intOrNull?.let { seg ->
                _ui.update { it.copy(sharedSegment = seg) }
            }
            "cardIdentities" -> (m.data as? JsonObject)?.get("cardIdentities")?.let { arr ->
                val ids = (arr as? JsonArray)?.map { e ->
                    val o = e as? JsonObject
                    (o?.get("suitIndex")?.jsonPrimitive?.intOrNull ?: -1) to (o?.get("rank")?.jsonPrimitive?.intOrNull ?: -1)
                } ?: emptyList()
                _ui.update { s -> s.copy(game = s.game?.withIdentities(ids)) }
            }
            "offlineRecord" -> (m.data as? JsonObject)?.let { record ->
                // The offline table sends the game when it ends; keep it for the offline stats.
                val app = getApplication<Application>()
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { OfflineStore.save(app, record.toString()) } }
                record["startedAt"]?.jsonPrimitive?.longOrNull?.let { id -> _ui.update { it.copy(lastOfflineGame = id) } }
            }
            "left" -> _ui.update { it.copy(joinedTableID = null, game = null, tableChat = emptyList(), replay = null, sharedSegment = null) }
            "tableStart" -> (m.data as? JsonObject)?.let { d ->
                // When our own finished game turns into a shared replay the server repeats "tableStart" with
                // replay=true; we already show that game, so only load a replay we are not looking at yet
                // (one we just chose to watch from the lobby).
                val id = d["tableID"]?.jsonPrimitive?.intOrNull
                val isReplay = (d["replay"] as? JsonPrimitive)?.content == "true"
                if (id != null && (!isReplay || _ui.value.game?.tableID != id)) beginGame(id)
            }
            "init" -> (m.data as? JsonObject)?.let(::onInit)
            "gameActionList" -> (m.data as? JsonObject)?.get("list")?.let { list ->
                val actions = (list as JsonArray).mapNotNull { it as? JsonObject }
                _ui.update { s -> s.copy(game = s.game?.applyAll(actions)) }
                // Tell the server our UI is ready: until then we are marked "away" (red name on the
                // website) and receive no live game actions.
                _ui.value.game?.tableID?.let { id ->
                    client.send("loaded", buildJsonObject { put("tableID", id) })
                }
            }
            "gameAction" -> (m.data as? JsonObject)?.get("action")?.let { a ->
                (a as? JsonObject)?.let { act ->
                    val ev = moveEventOf(act)
                    val type = act["type"]?.jsonPrimitive?.content
                    // The sound depends on the position before the move (was the card clued, ...).
                    soundTracker.onAction(
                        _ui.value.game, type,
                        act["order"]?.jsonPrimitive?.intOrNull ?: -1,
                        (act["failed"] as? JsonPrimitive)?.content == "true",
                    )
                    val drawEv = if (type == "draw" && _ui.value.game != null) {
                        live.hanab.client.game.DrawEvent(
                            ++moveCounter,
                            act["playerIndex"]?.jsonPrimitive?.intOrNull ?: -1,
                            act["order"]?.jsonPrimitive?.intOrNull ?: -1,
                        )
                    } else null
                    _ui.update { s ->
                        s.copy(
                            game = s.game?.applySafe(act),
                            moves = if (ev != null) s.moves + ev else s.moves,
                            draws = if (drawEv != null) s.draws + drawEv else s.draws,
                        )
                    }
                    playSoundsFor(type)
                }
            }
            // Everyone's notes: sent to spectators and in replays (also when a game ends), and a live
            // per-card update while watching. Players only ever receive their own ("noteListPlayer").
            "noteList" -> (m.data as? JsonObject)?.get("notes")?.let { arr ->
                val me = _ui.value.me?.username
                val authors = (arr as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val texts = (o["notes"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                    Triple(name, texts, (o["isSpectator"] as? JsonPrimitive)?.content == "true")
                }
                _ui.update { s ->
                    var g = s.game?.withAllNotes(authors)
                    // Our own notes also drive the yellow tag on the card.
                    authors.firstOrNull { it.first.equals(me, ignoreCase = true) }?.let { mine -> g = g?.withNotes(mine.second) }
                    s.copy(game = g)
                }
            }
            "note" -> (m.data as? JsonObject)?.let { d ->
                val me = _ui.value.me?.username
                val order = d["order"]?.jsonPrimitive?.intOrNull ?: return@let
                val entries = (d["notes"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    NoteEntry(
                        o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        o["text"]?.jsonPrimitive?.content.orEmpty(),
                        (o["isSpectator"] as? JsonPrimitive)?.content == "true",
                    )
                }
                _ui.update { s ->
                    var g = s.game?.withCardNotes(order, entries)
                    entries.firstOrNull { it.name.equals(me, ignoreCase = true) }?.let { mine -> g = g?.withNote(order, mine.text) }
                    s.copy(game = g)
                }
            }
            "noteListPlayer" -> (m.data as? JsonObject)?.get("notes")?.let { n ->
                val list = (n as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                _ui.update { it.copy(game = it.game?.withNotes(list)) }
            }
            "voteChange" -> (m.data as? JsonObject)?.get("vote")?.let { v ->
                _ui.update { it.copy(terminateVote = (v as? JsonPrimitive)?.content == "true") }
            }
            "boot" -> _ui.update { it.copy(joinedTableID = null, game = null, tableChat = emptyList(), replay = null, sharedSegment = null) }
            "error" -> (m.data as? JsonObject)?.get("error")?.let { e ->
                _ui.update { it.copy(error = e.toString().trim('"'), loggingIn = false) }
            }
            "warning" -> (m.data as? JsonObject)?.get("warning")?.let { e ->
                _ui.update { it.copy(error = e.toString().trim('"')) }
            }
        }
    }

    private val soundTracker = MoveSoundTracker()

    /** Sound and vibration for a live action, as the website does at the start of each turn. */
    private fun playSoundsFor(type: String?) {
        val g = _ui.value.game ?: return
        when (type) {
            "turn" -> if (g.phase == live.hanab.client.game.Phase.Playing) {
                val ours = g.currentPlayer == g.ourIndex
                if (AppSettings.soundMode == SoundMode.All || (AppSettings.soundMode == SoundMode.OwnTurn && ours)) {
                    GameSounds.play(soundTracker.turnSound(g))
                }
                if (soundTracker.lastWasMisplay) GameSounds.vibrate(250)
                else if (ours && !g.spectating) GameSounds.vibrate(90)
            }
            "gameOver" -> {
                GameSounds.play(finishedSound(g))
                GameSounds.vibrate(400)
            }
        }
    }

    private var moveCounter = 0L

    private fun moveEventOf(a: JsonObject): MoveEvent? {
        val type = a["type"]?.jsonPrimitive?.content
        if (type != "play" && type != "discard") return null
        fun n(k: String) = a[k]?.jsonPrimitive?.intOrNull ?: -1
        return MoveEvent(
            id = ++moveCounter,
            play = type == "play",
            player = n("playerIndex"),
            order = n("order"),
            suit = n("suitIndex"),
            rank = n("rank"),
            failed = (a["failed"] as? JsonPrimitive)?.content == "true",
        )
    }

    /** Called by the UI once it has started animating these moves. */
    fun consumeDraws(upToId: Long) = _ui.update { s -> s.copy(draws = s.draws.filter { it.id > upToId }) }

    fun consumeMoves(upToId: Long) = _ui.update { s -> s.copy(moves = s.moves.filter { it.id > upToId }) }

    private fun isLobby(c: ChatMessage) = c.room == null || c.room == "lobby"
    private fun isOurTable(c: ChatMessage) = c.room != null && c.room == "table${_ui.value.joinedTableID}"

    companion object {
        /** Chat text arrives HTML-escaped. */
        fun plain(html: String): String =
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}

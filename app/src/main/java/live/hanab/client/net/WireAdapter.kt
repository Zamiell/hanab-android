package live.hanab.client.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import live.hanab.client.game.Variants
import live.hanab.client.protocol.*
// The app's own models (Models.kt, the same package) have these names too; these are the website's.
import live.hanab.client.protocol.ChatList
import live.hanab.client.protocol.Table
import live.hanab.client.protocol.User
import live.hanab.client.protocol.Welcome

/**
 * hanab.live's WebSocket protocol keeps changing (hanabi-live `server/protocol`), and its messages are read with the types
 * generated from the website's own contracts (`protocol/Protocol.kt`, see `scripts/protocol.ps1`). The rest of the app, and
 * the offline table, still read and write the shapes of the old server (`tableID`, `list`, `status`, ...); this turns the
 * typed messages of the new server into those, and the app's commands into typed ones.
 *
 * Every message of the server is named in [inbound] with no catch-all, so a message the website adds fails the build after
 * `scripts/protocol.ps1 bump`, and a change to a message's fields fails it where it is read.
 *
 * It keeps a little state: our name (a table does not say whether we are at it), who is in a table's pregame (a table
 * is started with their ids), the turn of the game (a clue carries it in the old form) and whether the game is timed
 * (an untimed clock counts up, and the app reads it as counting down from 0).
 */
class WireAdapter(
    private val variantName: (Int) -> String = { Variants.nameOfId(it) ?: "No Variant" },
    private val variantId: (String) -> Int = { Variants.idOfName(it) ?: 0 },
) {
    private var me = ""
    private var turn = 0
    private var timed = false
    private val pregamePlayers = HashMap<Long, List<UserId>>()
    private val reported = HashSet<String>()

    /** Whether we have voted to end the game (the vote is sent as a value now, and the app only toggles it). */
    private var voted = false

    /** The old-style messages for one message of the new server (none when the app has no use for it). */
    fun inbound(m: Inbound): List<ServerMessage> = when (m) {
        is InWelcome -> {
            me = m.data.username
            one("welcome", welcome(m.data))
        }
        is InUserList -> one("userList", JsonArray(m.data.users.map(::user)))
        is InUser -> one("user", user(m.data))
        is InUserRemoved -> one("userLeft", buildJsonObject { put("userID", m.data.userId) })
        is InTableList -> one("tableList", JsonArray(m.data.tables.map(::table)))
        is InTable -> one("table", table(m.data))
        is InTableRemoved -> one("tableGone", tableRef(m.data))
        is InTableJoined -> {
            // Entering a table, and the game starting, are one message now: the stage says which.
            val joined = ServerMessage("joined", buildJsonObject { put("tableID", m.data.tableId.toInt()) })
            if (m.data.stage == TableStage.Pregame) listOf(joined)
            else listOf(joined, ServerMessage("tableStart", buildJsonObject {
                put("tableID", m.data.tableId.toInt())
                put("replay", m.data.stage == TableStage.Replay)
            }))
        }
        is InTableLeft -> one("left", tableRef(m.data))
        is InTableBooted -> one("boot", tableRef(m.data))
        is InChat -> chat(m.data)?.let { one("chat", it) }.orEmpty()
        is InChatList -> one("chatList", buildJsonObject { put("list", JsonArray(m.data.messages.mapNotNull(::chat))) })
        is InGameState -> gameState(m.data)
        is InGameEvent -> event(m.data.event)?.let {
            one("gameAction", buildJsonObject { put("tableID", m.data.tableId.toInt()); put("action", it) })
        }.orEmpty()
        is InGameFinished -> listOf(
            ServerMessage("finishOngoingGame", buildJsonObject {
                put("tableID", m.data.tableId.toInt())
                put("databaseID", m.data.gameId ?: 0)
                put("sharedReplayLeader", m.data.sharedReplay.owner.username)
            }),
            cardIdentities(m.data.tableId, m.data.deck),
        )
        is InNote -> one("note", buildJsonObject {
            put("tableID", m.data.tableId.toInt())
            put("order", m.data.order)
            put("notes", JsonArray(m.data.notes.map { n ->
                buildJsonObject { put("name", n.username); put("text", n.text); put("isSpectator", n.spectator) }
            }))
        })
        is InNoteList -> one("noteList", noteList(m.data.tableId, m.data.notes))
        is InVotes -> {
            voted = m.data.voted
            one("voteChange", buildJsonObject { put("vote", m.data.voted) })
        }
        is InReplayAction -> when (val a = m.data.action) {
            is ReplayLeaderActionTurn ->
                one("replaySegment", buildJsonObject { put("tableID", m.data.tableId.toInt()); put("segment", a.turn) })
            else -> emptyList()
        }
        is InError -> one("error", buildJsonObject { put("error", m.data.message) })
        is InWarning -> one("warning", buildJsonObject { put("warning", m.data.message) })
        is InPregame -> {
            pregamePlayers[m.data.tableId] = m.data.players.map { it.userId }
            emptyList()
        }
        is InInvalid -> unreadable(m.command, m.error)
        // The rest the app has no use for (yet).
        is InUnknown, is InBlocked, is InFriends, is InChatDeleted, is InChatTyping, is InClock, is InConnections,
        is InGameHistoryAdded, is InIdleReset, is InIdleWarning, is InLobbyStatus, is InPause, is InServerRestart,
        is InSpectators, is InTableOwner, is InTableRestartDecliners, is InTableSuggestion -> emptyList()
    }

    /**
     * The old-style messages for the reply of the server to a request that has data. A reply does not name the table it is
     * about (the request did), so the caller says which table the request was for.
     */
    fun reply(r: Reply, tableId: Long? = null): List<ServerMessage> = when (r) {
        is ReplyChatPmHistory -> one("chatPMHistory", buildJsonObject {
            put("list", JsonArray(r.data.messages.mapNotNull(::chat)))
            put("hasMore", false)
        })
        // A game's setup is the answer to the request to load it now (it was a message of its own).
        is ReplyGameInitLoad -> one("init", init(r.data, tableId ?: 0L))
        is ReplyInvalid -> unreadable(r.command, r.error)
        // The app asks for neither of these.
        is ReplyGameHistory, is ReplySeedGames -> emptyList()
    }

    /** The website's protocol is not the one this app was built for. Say so once per message. */
    private fun unreadable(command: String, error: String): List<ServerMessage> =
        if (reported.add(command)) {
            one("error", buildJsonObject {
                put("error", "The website sent a \"$command\" message this app cannot read, so it has probably changed " +
                    "its protocol and the app needs an update (${error.lineSequence().first().take(160)}).")
            })
        } else emptyList()

    /** The commands of the server for one old-style command of the app. */
    fun outbound(command: String, d: JsonObject): List<Outbound> {
        val id = d.int("tableID")?.toLong() ?: 0L
        fun ref() = TableRef(id)
        return when (command) {
            "chat" -> listOf(OutChat(ChatRequest(
                tableId = d.str("room")?.removePrefix("table")?.toLongOrNull(),
                text = d.str("msg") ?: "",
            )))
            "chatPM" -> listOf(OutChatPm(ChatPmRequest(recipient = d.str("recipient") ?: "", text = d.str("msg") ?: "")))
            "chatPMHistoryGet" -> listOf(OutChatPmHistory(ChatPmHistoryRequest(limit = d.int("amount") ?: 50, recipient = d.str("username"))))
            "tableCreate" -> listOf(OutTableCreate(TableCreateRequest(
                password = d.str("password")?.takeIf { it.isNotEmpty() },
                settings = settings(d),
            )))
            "tableUpdate" -> listOf(OutTableUpdate(TableUpdateRequest(settings = settings(d), tableId = id)))
            "tableJoin" -> listOf(OutTableJoin(TableJoinRequest(password = d.str("password")?.takeIf { it.isNotEmpty() }, tableId = id)))
            "tableStart" -> listOf(OutTableStart(TableStartRequest(intendedPlayerIds = pregamePlayers[id].orEmpty(), tableId = id)))
            "tableLeave" -> listOf(OutTableLeave(ref()))
            "tableUnattend" -> listOf(OutTableUnattend(ref()))
            "tableReattend" -> listOf(OutTableReattend(ref()))
            "tableTerminate" -> listOf(OutTableTerminate(ref()))
            "tableVoteForTermination" -> listOf(OutTableTerminateVote(TableTerminateVoteRequest(tableId = id, voted = !voted)))
            "tableRestart" -> listOf(OutTableRestart(TableRestartRequest(startNow = d.bool("hidePregame"), tableId = id)))
            "tableSpectate" -> listOf(OutTableSpectate(TableSpectateRequest(
                shadowingPlayerIndex = d.int("shadowingPlayerIndex")?.takeIf { it >= 0 },
                tableId = id,
            )))
            "getGameInfo1" -> listOf(OutGameInitLoad(ref()))
            "getGameInfo2" -> listOf(OutGameStateLoad(ref()))
            "loaded" -> listOf(OutGameLoaded(ref()))
            "note" -> listOf(OutNote(NoteRequest(order = d.int("order") ?: 0, tableId = id, text = d.str("note") ?: "")))
            "replayAction" -> listOf(OutReplayAction(ReplayAction(action = ReplayLeaderActionTurn(d.int("segment") ?: 0), tableId = id)))
            "replayCreate" -> listOf(OutReplayCreate(ReplayCreateRequest(
                source = ReplaySourceSaved(d.int("databaseID") ?: 0),
                visibility = if (d.str("visibility") == "shared") ReplayVisibilityShared(joinExisting = false, spoilerFree = false) else ReplayVisibilitySolo,
            )))
            "action" -> listOf(OutPlayerAction(PlayerActionRequest(action = playerAction(d), tableId = id)))
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ messages of the server

    private fun one(command: String, data: JsonElement) = listOf(ServerMessage(command, data))

    private fun tableRef(r: TableRef) = buildJsonObject { put("tableID", r.tableId.toInt()) }

    private fun welcome(w: Welcome): JsonObject = buildJsonObject {
        put("userID", w.userId)
        put("username", w.username)
        put("playingAtTables", JsonArray(w.playingAtTables.map { JsonPrimitive(it.toInt()) }))
        w.spectatingAtTables.firstOrNull()?.let { s ->
            put("disconSpectatingTable", s.tableId.toInt())
            put("disconShadowingSeat", s.shadowingPlayerIndex ?: -1)
        }
        put("randomTableName", "${w.username}${ProtocolConstants.DEFAULT_TABLE_NAME_SUFFIX}")
    }

    private fun user(u: User): JsonObject = buildJsonObject {
        put("userID", u.userId)
        put("name", u.username)
        put("status", when (u.activity) {
            is ActivityLobby -> "Lobby"
            is ActivityPregame -> "Pre-Game"
            is ActivityPlaying -> "Playing"
            is ActivitySpectating -> "Spectating"
            is ActivitySoloReplay -> "Replay"
            is ActivitySharedReplay -> "Shared Replay"
        })
        when (val a = u.activity) {
            is ActivityPregame -> put("tableID", a.tableId.toInt())
            is ActivityPlaying -> put("tableID", a.tableId.toInt())
            is ActivitySpectating -> put("tableID", a.tableId.toInt())
            is ActivitySharedReplay -> put("tableID", a.tableId.toInt())
            is ActivityLobby, is ActivitySoloReplay -> {}
        }
        put("inactive", u.inactive)
    }

    private fun table(t: Table): JsonObject = buildJsonObject {
        val options = t.settings.options
        put("id", t.tableId.toInt())
        put("name", t.settings.name)
        put("joined", me.isNotEmpty() && (me in t.playerNames || t.spectators.any { it.username == me }))
        put("maxPlayers", t.settings.maxPlayers)
        put("numPlayers", t.playerNames.size)
        put("ownerID", t.owner.userId)
        put("passwordProtected", t.passwordProtected)
        put("players", JsonArray(t.playerNames.map { JsonPrimitive(it) }))
        put("progress", when (val p = t.phase) {
            is TablePhasePregame -> 0
            is TablePhasePlaying -> p.progress
            is TablePhaseSharedReplay -> p.progress
        })
        put("running", t.phase !is TablePhasePregame)
        put("sharedReplay", t.phase is TablePhaseSharedReplay)
        put("variant", variantName(options.variantId))
        put("timed", options.timeControl != null)
        put("options", legacyOptions(options))
        put("spectators", JsonArray(t.spectators.map { s -> buildJsonObject { put("name", s.username) } }))
    }

    private fun legacyOptions(o: GameOptions): JsonObject = buildJsonObject {
        put("variantName", variantName(o.variantId))
        put("timed", o.timeControl != null)
        put("speedrun", o.speedrun)
        put("cardCycle", false)
        put("deckPlays", o.deckPlays)
        put("emptyClues", o.emptyClues)
        put("oneExtraCard", o.oneExtraCard)
        put("oneLessCard", o.oneLessCard)
        put("allOrNothing", o.allOrNothing)
        put("detrimentalCharacters", o.detrimentalCharacters)
    }

    /** A message of the chat, or null for the kinds the app does not show (a suggestion of table settings). */
    private fun chat(c: Chat): JsonObject? {
        val author: ChatAuthor
        var recipient: String? = null
        when (val k = c.kind) {
            is ChatKindPublic -> author = k.author
            is ChatKindPrivate -> { author = k.author; recipient = k.recipient }
            is ChatKindSettingsSuggestion -> return null
        }
        return buildJsonObject {
            put("msg", c.text)
            when (author) {
                is ChatAuthorUser -> { put("who", author.username); put("source", "user") }
                is ChatAuthorDiscord -> { author.username?.let { put("who", it) }; put("source", "discord") }
                is ChatAuthorServer -> put("source", "server")
            }
            put("datetime", c.sentAt)
            c.chatId?.let { put("chatID", it.toInt()) }
            recipient?.let { put("recipient", it) }
            put("room", c.tableId?.let { "table${it.toInt()}" } ?: "lobby")
        }
    }

    private fun init(i: GameInit, tableId: TableId): JsonObject = buildJsonObject {
        timed = i.options.timeControl != null
        turn = 0
        val mode = i.mode
        put("tableID", tableId.toInt())
        put("playerNames", JsonArray(i.playerNames.map { JsonPrimitive(it) }))
        put("ourPlayerIndex", when (mode) {
            is ViewModePlaying -> mode.ourPlayerIndex
            is ViewModeShadowing -> mode.ourPlayerIndex
            is ViewModeSoloReplay -> mode.ourPlayerIndex ?: 0
            is ViewModeSharedReplay -> mode.ourPlayerIndex ?: 0
            is ViewModeSpectating -> 0
        })
        put("spectating", mode !is ViewModePlaying)
        put("replay", mode is ViewModeSoloReplay || mode is ViewModeSharedReplay)
        put("sharedReplay", mode is ViewModeSharedReplay)
        if (mode is ViewModeSharedReplay) put("sharedReplayLeader", mode.sharedReplay.owner.username)
        put("databaseID", when (mode) {
            is ViewModeSoloReplay -> mode.gameId ?: 0
            is ViewModeSharedReplay -> mode.gameId ?: 0
            else -> 0
        })
        put("options", legacyOptions(i.options))
        put("characterAssignments", JsonArray(i.characters.map { c ->
            JsonPrimitive(when (c) {
                is CharacterAssignmentPlain -> c.characterId
                is CharacterAssignmentColor -> c.characterId
                is CharacterAssignmentRank -> c.characterId
            })
        }))
        put("characterMetadata", JsonArray(i.characters.map { c ->
            JsonPrimitive(when (c) {
                is CharacterAssignmentPlain -> -1
                is CharacterAssignmentColor -> c.color
                is CharacterAssignmentRank -> c.rank
            })
        }))
    }

    /** What a game's state is, as the old server sent it in pieces: the actions, then the notes and, in a replay, every card. */
    private fun gameState(s: GameState): List<ServerMessage> {
        val tableId = s.tableId.toInt()
        val actions = s.events.mapNotNull(::event)
        val out = mutableListOf(ServerMessage("gameActionList", buildJsonObject {
            put("tableID", tableId)
            put("list", JsonArray(actions))
        }))
        when (val phase = s.phase) {
            is GamePhaseReplay -> out += cardIdentities(s.tableId, phase.deck)
            is GamePhasePlaying -> phase.votes?.let { v ->
                voted = v.voted
                out += ServerMessage("voteChange", buildJsonObject { put("vote", v.voted) })
            }
        }
        out += ServerMessage("noteList", noteList(s.tableId, s.notes))
        return out
    }

    private fun cardIdentities(tableId: TableId, deck: List<CardIdentity>) = ServerMessage("cardIdentities", buildJsonObject {
        put("tableID", tableId.toInt())
        put("cardIdentities", JsonArray(deck.map { id -> buildJsonObject { put("suitIndex", id.suitIndex); put("rank", id.rank) } }))
    })

    private fun noteList(tableId: TableId, notes: List<AuthorNotes>): JsonObject = buildJsonObject {
        put("tableID", tableId.toInt())
        put("notes", JsonArray(notes.map { n ->
            buildJsonObject {
                put("name", n.username)
                put("notes", JsonArray(n.notes.map { JsonPrimitive(it) }))
                put("isSpectator", n.spectator)
            }
        }))
    }

    /** One game event as the old server's game action. */
    private fun event(e: GameEvent): JsonObject? {
        fun card(type: String, player: Int, order: Int, id: CardIdentity?, failed: Boolean? = null) = buildJsonObject {
            put("type", type)
            put("playerIndex", player)
            put("order", order)
            put("suitIndex", id?.suitIndex ?: -1)
            put("rank", id?.rank ?: -1)
            failed?.let { put("failed", it) }
        }
        return when (e) {
            is GameEventDraw -> card("draw", e.playerIndex, e.order, e.identity)
            is GameEventPlay -> card("play", e.playerIndex, e.order, e.identity)
            is GameEventCardIdentity -> card("cardIdentity", e.playerIndex, e.order, e.identity)
            is GameEventDiscard -> card("discard", e.playerIndex, e.order, e.identity, failed = false)
            // A misplay is a failed discard in the old form, and the app counts the strike from it.
            is GameEventMisplay -> card("discard", e.playerIndex, e.order, e.identity, failed = true)
            is GameEventClue -> {
                val clue = e.clue
                buildJsonObject {
                    put("type", "clue")
                    put("clue", buildJsonObject {
                        // The server hides what a Cow & Pig or Duck clue says; those variants ignore it (they only
                        // say aloud that a clue was given), but the clue still costs a token and touches cards.
                        when (clue) {
                            is VisibleClueColor -> { put("type", 0); put("value", clue.color ?: 0) }
                            is VisibleClueRank -> { put("type", 1); put("value", clue.rank ?: 0) }
                            null -> { put("type", 1); put("value", 0) }
                        }
                    })
                    put("giver", e.playerIndex)
                    put("list", JsonArray(e.orders.map { JsonPrimitive(it) }))
                    put("target", e.receiverIndex)
                    put("turn", turn)
                }
            }
            is GameEventTurn -> {
                turn = e.turn
                buildJsonObject {
                    put("type", "turn")
                    put("num", e.turn)
                    put("currentPlayerIndex", e.currentPlayerIndex ?: -1)
                }
            }
            is GameEventGameOver -> buildJsonObject {
                put("type", "gameOver")
                put("endCondition", endCondition(e.endCondition))
                put("playerIndex", e.playerIndex ?: -1)
                put("votes", JsonArray(e.voters.map { JsonPrimitive(it) }))
            }
            is GameEventPlayerTimes -> buildJsonObject {
                put("type", "playerTimes")
                // Untimed: the server counts the time taken up; the app reads minus the time taken.
                put("playerTimes", JsonArray(e.playerTimesMs.map { JsonPrimitive(if (timed) it else -it) }))
                put("duration", e.durationMs)
            }
        }
    }

    // ------------------------------------------------------------------ commands of the app

    private fun settings(d: JsonObject): TableSettings {
        val o = d["options"] as? JsonObject
        return TableSettings(
            maxPlayers = d.int("maxPlayers") ?: ProtocolConstants.DEFAULT_MAX_PLAYERS,
            name = d.str("name") ?: "Table",
            options = GameOptions(
                variantId = variantId(o?.str("variantName") ?: ProtocolConstants.DEFAULT_VARIANT_NAME),
                speedrun = o?.bool("speedrun") ?: false,
                deckPlays = o?.bool("deckPlays") ?: false,
                emptyClues = o?.bool("emptyClues") ?: false,
                oneExtraCard = o?.bool("oneExtraCard") ?: false,
                oneLessCard = o?.bool("oneLessCard") ?: false,
                allOrNothing = o?.bool("allOrNothing") ?: false,
                detrimentalCharacters = o?.bool("detrimentalCharacters") ?: false,
            ),
        )
    }

    /** The old action (`type` 0 play, 1 discard, 2 colour clue, 3 rank clue; `target` a card or a player). */
    private fun playerAction(d: JsonObject): PlayerAction {
        val target = d.int("target") ?: 0
        val value = d.int("value") ?: 0
        return when (d.int("type")) {
            0 -> PlayerActionPlay(target)
            1 -> PlayerActionDiscard(target)
            2 -> PlayerActionClue(clue = ClueColor(value), receiverIndex = target)
            else -> PlayerActionClue(clue = ClueRankVariant(value), receiverIndex = target)
        }
    }

    companion object {
        /** The old server's numbers for how a game ended. */
        fun endCondition(c: EndCondition): Int = when (c) {
            EndCondition.Normal -> 1
            EndCondition.Strikeout -> 2
            EndCondition.Timeout -> 3
            EndCondition.TerminatedByPlayer -> 4
            EndCondition.SpeedrunFail -> 5
            EndCondition.IdleTimeout -> 6
            EndCondition.CharacterSoftlock -> 7
            EndCondition.AllOrNothingFail -> 8
            EndCondition.AllOrNothingSoftlock -> 9
            EndCondition.TerminatedByVote -> 10
        }

        /**
         * The website's export of a finished game (`/export/<id>`, a [ReplayGame]) as the analysis reads it: `players`, the
         * deck, and the moves as `type` 0 to 3 with a `target` and a `value`.
         */
        fun legacyExport(text: String): String {
            val game = ProtocolJson.decodeFromString(ReplayGame.serializer(), text)
            val moves = game.actions.orEmpty().mapNotNull { a ->
                when (a) {
                    is ReplayGameActionPlay -> buildJsonObject { put("type", 0); put("target", a.order); put("value", 0) }
                    is ReplayGameActionDiscard -> buildJsonObject { put("type", 1); put("target", a.order); put("value", 0) }
                    is ReplayGameActionClue -> buildJsonObject {
                        when (val c = a.clue) {
                            is ClueColor -> { put("type", 2); put("value", c.color) }
                            is ClueRankVariant -> { put("type", 3); put("value", c.rank) }
                        }
                        put("target", a.receiverIndex)
                    }
                    is ReplayGameActionGameOver -> null
                }
            }
            return buildJsonObject {
                put("players", JsonArray(game.playerNames.orEmpty().map { JsonPrimitive(it) }))
                put("deck", JsonArray(game.deck.orEmpty().map { id -> buildJsonObject { put("suitIndex", id.suitIndex); put("rank", id.rank) } }))
                put("actions", JsonArray(moves))
                game.seed?.let { put("seed", it) }
            }.toString()
        }

        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content == "true"
    }
}

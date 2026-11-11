package live.hanab.client.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * hanab.live's WebSocket protocol was rewritten (hanabi-live `server/protocol`): camelCase ids (`tableId`), a
 * `settings` object for a table, game events with a nested `identity`, no `status` or `strike` events, and different
 * command names. The rest of the app, and the offline table, still speak the messages of the old server, so this
 * turns the new server's messages into those, and the app's commands into the new ones.
 *
 * It keeps a little state: the users (the site tells a change of `inactive` by id only), the turn of the game (a clue
 * carries it in the old form) and whether the game is timed (an untimed clock counts up now, and the app reads it as
 * counting down from 0).
 */
class WireAdapter {
    private val users = HashMap<Int, JsonObject>()
    private var turn = 0
    private var timed = false

    /** The old-style messages for one message of the new server (none when the app has no use for it). */
    fun inbound(m: ServerMessage): List<ServerMessage> {
        val d = m.data
        val o = d as? JsonObject
        fun one(command: String, data: JsonElement?) = listOf(ServerMessage(command, data))
        return when (m.command) {
            "welcome" -> o?.let { one("welcome", welcome(it)) }
            "userList" -> (d as? JsonArray)?.let { arr ->
                users.clear()
                val list = arr.mapNotNull { (it as? JsonObject)?.let(::user) }
                list.forEach { u -> users[u.int("userID") ?: return@forEach] = u }
                one("userList", JsonArray(list))
            }
            "user" -> o?.let { one("user", user(it).also { u -> u.int("userID")?.let { id -> users[id] = u } }) }
            "userInactive" -> o?.let {
                val id = it.int("userId") ?: return@let null
                val old = users[id] ?: return@let null
                val next = JsonObject(old + ("inactive" to JsonPrimitive(it.bool("inactive"))))
                users[id] = next
                one("user", next)
            }
            "userLeft" -> o?.let {
                val id = it.int("userId") ?: return@let null
                users.remove(id)
                one("userLeft", buildJsonObject { put("userID", id) })
            }
            "tableList" -> (d as? JsonArray)?.let { arr -> one("tableList", JsonArray(arr.mapNotNull { (it as? JsonObject)?.let(::table) })) }
            "table" -> o?.let { one("table", table(it)) }
            "tableGone" -> o?.let { one("tableGone", withTableID(it)) }
            "joined", "left", "boot" -> o?.let { one(m.command, withTableID(it)) }
            "chat" -> o?.let { one("chat", chat(it)) }
            "chatList" -> o?.let {
                one("chatList", buildJsonObject { put("list", JsonArray(it.arr("messages").mapNotNull { c -> (c as? JsonObject)?.let(::chat) })) })
            }
            "chatPmHistory" -> o?.let {
                one("chatPMHistory", buildJsonObject {
                    put("list", JsonArray(it.arr("messages").mapNotNull { c -> (c as? JsonObject)?.let(::chat) }))
                    put("hasMore", false)
                })
            }
            "tableStarted" -> o?.let {
                one("tableStart", buildJsonObject {
                    put("tableID", it.int("tableId") ?: 0)
                    put("replay", it.str("stage") == "replay")
                })
            }
            "init" -> o?.let { one("init", init(it)) }
            "gameEventList" -> o?.let {
                val list = it.arr("events").mapNotNull { e -> (e as? JsonObject)?.let(::event) }
                one("gameActionList", buildJsonObject { put("tableID", it.int("tableId") ?: 0); put("list", JsonArray(list)) })
            }
            "gameEvent" -> o?.let {
                val action = (it["event"] as? JsonObject)?.let(::event) ?: return@let null
                one("gameAction", buildJsonObject { put("tableID", it.int("tableId") ?: 0); put("action", action) })
            }
            "noteList" -> o?.let {
                one("noteList", buildJsonObject {
                    put("tableID", it.int("tableId") ?: 0)
                    put("notes", JsonArray(it.arr("notes").mapNotNull { n ->
                        val p = n as? JsonObject ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", p.str("username") ?: "")
                            put("notes", p["notes"] ?: JsonArray(emptyList()))
                            put("isSpectator", p.bool("isSpectator"))
                        }
                    }))
                })
            }
            "note" -> o?.let {
                one("note", buildJsonObject {
                    put("tableID", it.int("tableId") ?: 0)
                    put("order", it.int("order") ?: 0)
                    put("notes", JsonArray(it.arr("notes").mapNotNull { n ->
                        val p = n as? JsonObject ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", p.str("username") ?: "")
                            put("text", p.str("text") ?: "")
                            put("isSpectator", p.bool("isSpectator"))
                        }
                    }))
                })
            }
            "noteListPlayer" -> o?.let {
                one("noteListPlayer", buildJsonObject { put("tableID", it.int("tableId") ?: 0); put("notes", it["notes"] ?: JsonArray(emptyList())) })
            }
            "cardIdentities" -> o?.let {
                one("cardIdentities", buildJsonObject {
                    put("tableID", it.int("tableId") ?: 0)
                    put("cardIdentities", JsonArray(it.arr("identities").map { i ->
                        val id = i as? JsonObject
                        buildJsonObject { put("suitIndex", id?.int("suitIndex") ?: -1); put("rank", id?.int("rank") ?: -1) }
                    }))
                })
            }
            "finishOngoingGame" -> o?.let {
                one("finishOngoingGame", buildJsonObject {
                    put("tableID", it.int("tableId") ?: 0)
                    put("databaseID", it.int("gameId") ?: 0)
                    put("sharedReplayLeader", (it["sharedReplay"] as? JsonObject)?.str("leader") ?: "")
                })
            }
            "replayAction" -> o?.let {
                val a = it["action"] as? JsonObject ?: return@let null
                if (a.str("type") != "turn") return@let null
                one("replaySegment", buildJsonObject { put("tableID", it.int("tableId") ?: 0); put("segment", a.int("turn") ?: 0) })
            }
            "voteChange" -> o?.let { one("voteChange", buildJsonObject { put("vote", it.bool("vote")) }) }
            "error" -> o?.let { one("error", buildJsonObject { put("error", it.str("message") ?: "") }) }
            "warning" -> o?.let { one("warning", buildJsonObject { put("warning", it.str("message") ?: "") }) }
            else -> null
        } ?: emptyList()
    }

    /** The new-style commands for one old-style command of the app. */
    fun outbound(command: String, data: JsonObject?): List<Pair<String, JsonObject?>> {
        val d = data ?: JsonObject(emptyMap())
        val id = d.int("tableID")
        fun ref(name: String) = listOf<Pair<String, JsonObject?>>(name to buildJsonObject { put("tableId", id ?: 0) })
        return when (command) {
            "chat" -> {
                val table = d.str("room")?.removePrefix("table")?.toIntOrNull()
                listOf("chat" to buildJsonObject {
                    put("text", d.str("msg") ?: "")
                    table?.let { put("tableId", it) }
                })
            }
            "chatPM" -> listOf("chatPm" to buildJsonObject {
                put("recipient", d.str("recipient") ?: "")
                put("text", d.str("msg") ?: "")
            })
            "chatPMHistoryGet" -> listOf("chatPmHistory" to buildJsonObject {
                put("amount", d.int("amount") ?: 50)
                d.str("username")?.let { put("username", it) }
            })
            "tableCreate" -> listOf("tableCreate" to buildJsonObject {
                put("settings", settings(d))
                d.str("password")?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
            })
            "tableUpdate" -> listOf("tableUpdate" to buildJsonObject {
                put("tableId", id ?: 0)
                put("settings", settings(d))
            })
            "tableJoin" -> listOf("tableJoin" to buildJsonObject {
                put("tableId", id ?: 0)
                d.str("password")?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
            })
            "tableStart" -> ref("tableStart")
            "tableLeave" -> ref("tableLeave")
            "tableUnattend" -> ref("tableUnattend")
            "tableReattend" -> ref("tableReattend")
            "tableTerminate" -> ref("tableTerminate")
            "tableVoteForTermination" -> ref("tableTerminateVote")
            "tableRestart" -> listOf("tableRestart" to buildJsonObject {
                put("tableId", id ?: 0)
                put("hidePregame", d.bool("hidePregame"))
            })
            "tableSpectate" -> listOf("tableSpectate" to buildJsonObject {
                put("tableId", id ?: 0)
                d.int("shadowingPlayerIndex")?.takeIf { it >= 0 }?.let { put("shadowingPlayerIndex", it) }
            })
            "getGameInfo1" -> ref("gameInitLoad")
            "getGameInfo2" -> ref("gameStateLoad")
            "loaded" -> ref("loaded")
            "note" -> listOf("note" to buildJsonObject {
                put("tableId", id ?: 0)
                put("order", d.int("order") ?: 0)
                put("text", d.str("note") ?: "")
            })
            "replayAction" -> listOf("replayAction" to buildJsonObject {
                put("tableId", id ?: 0)
                put("action", buildJsonObject { put("type", "turn"); put("turn", d.int("segment") ?: 0) })
            })
            "replayCreate" -> listOf("replayCreate" to buildJsonObject {
                put("source", buildJsonObject { put("type", "saved"); put("gameId", d.int("databaseID") ?: 0) })
                put("visibility", d.str("visibility") ?: "solo")
            })
            "action" -> listOf("playerAction" to buildJsonObject {
                put("tableId", id ?: 0)
                put("action", playerAction(d))
            })
            else -> listOf(command to data)
        }
    }

    // ------------------------------------------------------------------ inbound pieces

    private fun welcome(w: JsonObject): JsonObject = buildJsonObject {
        val name = w.str("username") ?: ""
        put("userID", w.int("userId") ?: 0)
        put("username", name)
        put("playingAtTables", JsonArray(w.arr("playingAtTables")))
        (w["spectatingAtTable"] as? JsonObject)?.let { s ->
            put("disconSpectatingTable", s.int("tableId") ?: 0)
            put("disconShadowingSeat", s.int("shadowingPlayerIndex") ?: -1)
        }
        put("randomTableName", "$name's table")
    }

    private fun user(u: JsonObject): JsonObject = buildJsonObject {
        val activity = u["activity"] as? JsonObject
        put("userID", u.int("userId") ?: 0)
        put("name", u.str("username") ?: "")
        put("status", when (activity?.str("type")) {
            "pregame" -> "Pre-Game"
            "playing" -> "Playing"
            "spectating" -> "Spectating"
            "replay" -> "Replay"
            "sharedReplay" -> "Shared Replay"
            else -> "Lobby"
        })
        activity?.int("tableId")?.let { put("tableID", it) }
        put("inactive", u.bool("inactive"))
    }

    private fun table(t: JsonObject): JsonObject = buildJsonObject {
        val s = t["settings"] as? JsonObject
        val options = s?.get("options") as? JsonObject
        val players = t.arr("playerNames")
        val phase = t.str("phase")
        put("id", t.int("tableId") ?: 0)
        put("name", s?.str("name") ?: "")
        put("joined", t.bool("joined"))
        put("maxPlayers", s?.int("maxPlayers") ?: 5)
        put("numPlayers", players.size)
        put("ownerID", t.int("ownerId") ?: 0)
        put("passwordProtected", t.bool("passwordProtected"))
        put("players", JsonArray(players))
        put("progress", t.int("progress") ?: 0)
        put("running", phase != "pregame")
        put("sharedReplay", phase == "sharedReplay")
        put("variant", options?.str("variantName") ?: "No Variant")
        put("timed", options?.get("timeControl") != null)
        put("options", legacyOptions(options))
        put("spectators", JsonArray(t.arr("spectators").map { sp ->
            buildJsonObject { put("name", (sp as? JsonObject)?.str("username") ?: "") }
        }))
    }

    private fun legacyOptions(o: JsonObject?): JsonObject = buildJsonObject {
        put("variantName", o?.str("variantName") ?: "No Variant")
        put("timed", o?.get("timeControl") != null)
        put("speedrun", o?.bool("speedrun") ?: false)
        put("cardCycle", false)
        put("deckPlays", o?.bool("deckPlays") ?: false)
        put("emptyClues", o?.bool("emptyClues") ?: false)
        put("oneExtraCard", o?.bool("oneExtraCard") ?: false)
        put("oneLessCard", o?.bool("oneLessCard") ?: false)
        put("allOrNothing", o?.bool("allOrNothing") ?: false)
        put("detrimentalCharacters", o?.bool("detrimentalCharacters") ?: false)
    }

    private fun chat(c: JsonObject): JsonObject = buildJsonObject {
        put("msg", c.str("text") ?: "")
        c.str("username")?.let { put("who", it) }
        put("source", c.str("source") ?: "user")
        put("datetime", c.str("sentAt") ?: "")
        c.int("chatId")?.let { put("chatID", it) }
        c.str("recipient")?.let { put("recipient", it) }
        put("room", c.int("tableId")?.let { "table$it" } ?: "lobby")
    }

    private fun init(i: JsonObject): JsonObject = buildJsonObject {
        val mode = i.str("mode")
        val options = i["options"] as? JsonObject
        val shared = i["sharedReplay"] as? JsonObject
        timed = options?.get("timeControl") != null
        turn = 0
        put("tableID", i.int("tableId") ?: 0)
        put("playerNames", JsonArray(i.arr("playerNames")))
        put("ourPlayerIndex", i.int("ourPlayerIndex") ?: 0)
        put("spectating", mode != "playing")
        put("replay", mode == "replay" || mode == "sharedReplay")
        put("sharedReplay", mode == "sharedReplay")
        shared?.str("leader")?.let { put("sharedReplayLeader", it) }
        put("databaseID", i.int("gameId") ?: 0)
        put("options", legacyOptions(options))
        val characters = i.arr("characters")
        put("characterAssignments", JsonArray(characters.map { JsonPrimitive((it as? JsonObject)?.int("characterId") ?: -1) }))
        put("characterMetadata", JsonArray(characters.map { JsonPrimitive((it as? JsonObject)?.int("metadata") ?: -1) }))
    }

    /** One game event as the old server's game action; null when it has no use here (a clue whose content is hidden). */
    private fun event(e: JsonObject): JsonObject? {
        val identity = e["identity"] as? JsonObject
        fun card(type: String) = buildJsonObject {
            put("type", type)
            put("playerIndex", e.int("playerIndex") ?: -1)
            put("order", e.int("order") ?: -1)
            put("suitIndex", identity?.int("suitIndex") ?: -1)
            put("rank", identity?.int("rank") ?: -1)
        }
        return when (val type = e.str("type")) {
            "draw", "play", "cardIdentity" -> card(type)
            "discard" -> JsonObject(card(type) + ("failed" to JsonPrimitive(e.bool("failed"))))
            "clue" -> {
                val clue = e["clue"] as? JsonObject ?: return null
                buildJsonObject {
                    put("type", "clue")
                    put("clue", buildJsonObject {
                        put("type", if (clue.str("type") == "color") 0 else 1)
                        put("value", clue.int("value") ?: 0)
                    })
                    put("giver", e.int("giver") ?: -1)
                    put("list", JsonArray(e.arr("orders")))
                    put("target", e.int("target") ?: -1)
                    put("turn", turn)
                }
            }
            "turn" -> {
                turn = e.int("turn") ?: turn
                buildJsonObject {
                    put("type", "turn")
                    put("num", turn)
                    put("currentPlayerIndex", e.int("currentPlayerIndex") ?: -1)
                }
            }
            "gameOver" -> buildJsonObject {
                put("type", "gameOver")
                put("endCondition", endCondition(e.str("endCondition")))
                put("playerIndex", e.int("playerIndex") ?: -1)
                put("votes", JsonArray(e.arr("votes")))
            }
            "playerTimes" -> buildJsonObject {
                put("type", "playerTimes")
                // Untimed: the new server counts the time taken up; the app reads minus the time taken.
                put("playerTimes", JsonArray(e.arr("playerTimesMs").map { t ->
                    val ms = (t as? JsonPrimitive)?.longOrNull ?: 0L
                    JsonPrimitive(if (timed) ms else -ms)
                }))
                put("duration", (e["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ outbound pieces

    private fun settings(d: JsonObject): JsonObject = buildJsonObject {
        put("name", d.str("name") ?: "Table")
        put("maxPlayers", d.int("maxPlayers") ?: 5)
        val o = d["options"] as? JsonObject
        put("options", buildJsonObject {
            put("variantName", o?.str("variantName") ?: "No Variant")
            put("speedrun", o?.bool("speedrun") ?: false)
            put("deckPlays", o?.bool("deckPlays") ?: false)
            put("emptyClues", o?.bool("emptyClues") ?: false)
            put("oneExtraCard", o?.bool("oneExtraCard") ?: false)
            put("oneLessCard", o?.bool("oneLessCard") ?: false)
            put("allOrNothing", o?.bool("allOrNothing") ?: false)
            put("detrimentalCharacters", o?.bool("detrimentalCharacters") ?: false)
        })
    }

    /** The old action (`type` 0 play, 1 discard, 2 colour clue, 3 rank clue; `target` a card or a player). */
    private fun playerAction(d: JsonObject): JsonObject = buildJsonObject {
        val target = d.int("target") ?: 0
        val value = d.int("value") ?: 0
        when (d.int("type")) {
            0 -> { put("type", "play"); put("order", target) }
            1 -> { put("type", "discard"); put("order", target) }
            2 -> { put("type", "clue"); put("target", target); put("clue", buildJsonObject { put("type", "color"); put("value", value) }) }
            else -> { put("type", "clue"); put("target", target); put("clue", buildJsonObject { put("type", "rank"); put("value", value) }) }
        }
    }

    companion object {
        /**
         * The website's export of a finished game (`/export/<id>`) now names its moves (`play`, `discard`, `clue` with a
         * named type); the analysis reads the old one (`players`, and moves with `type` 0 to 3 and a `target` and `value`).
         */
        fun legacyExport(text: String): String {
            val game = runCatching { HanabJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
            val moves = (game["actions"] as? JsonArray)?.mapNotNull { a ->
                val m = a as? JsonObject ?: return@mapNotNull null
                when (m.str("type")) {
                    "play" -> buildJsonObject { put("type", 0); put("target", m.int("order") ?: 0); put("value", 0) }
                    "discard" -> buildJsonObject { put("type", 1); put("target", m.int("order") ?: 0); put("value", 0) }
                    "clue" -> {
                        val clue = m["clue"] as? JsonObject ?: return@mapNotNull null
                        buildJsonObject {
                            put("type", if (clue.str("type") == "color") 2 else 3)
                            put("target", m.int("target") ?: 0)
                            put("value", clue.int("value") ?: 0)
                        }
                    }
                    else -> null
                }
            } ?: return text
            return JsonObject(game - "playerNames" + mapOf(
                "players" to (game["playerNames"] ?: game["players"] ?: JsonArray(emptyList())),
                "actions" to JsonArray(moves),
            )).toString()
        }

        /** The old server's numbers for how a game ended. */
        fun endCondition(name: String?): Int = when (name) {
            "normal" -> 1
            "strikeout" -> 2
            "timeout" -> 3
            "terminatedByPlayer" -> 4
            "speedrunFail" -> 5
            "idleTimeout" -> 6
            "characterSoftlock" -> 7
            "allOrNothingFail" -> 8
            "allOrNothingSoftlock" -> 9
            "terminatedByVote" -> 10
            else -> 0
        }

        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
        private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()
        private fun withTableID(o: JsonObject) = buildJsonObject { put("tableID", o.int("tableId") ?: 0) }
    }
}

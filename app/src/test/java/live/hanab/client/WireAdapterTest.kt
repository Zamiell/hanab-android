package live.hanab.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.game.GameState
import live.hanab.client.net.HanabJson
import live.hanab.client.net.ServerMessage
import live.hanab.client.net.WireAdapter
import live.hanab.client.protocol.InInvalid
import live.hanab.client.protocol.OutChat
import live.hanab.client.protocol.OutPlayerAction
import live.hanab.client.protocol.OutTableCreate
import live.hanab.client.protocol.OutTableRestart
import live.hanab.client.protocol.OutTableTerminateVote
import live.hanab.client.protocol.decodeReply
import live.hanab.client.protocol.OutTableSpectate
import live.hanab.client.protocol.OutTableStart
import live.hanab.client.protocol.PlayerActionClue
import live.hanab.client.protocol.PlayerActionDiscard
import live.hanab.client.protocol.PlayerActionPlay
import live.hanab.client.protocol.ClueColor
import live.hanab.client.protocol.ClueRankVariant
import live.hanab.client.protocol.decodeInbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The website's messages, read with the generated types, become the ones the app reads, and the other way round. */
class WireAdapterTest {
    private fun adapter() = WireAdapter(
        variantName = { id -> if (id == 1) "6 Suits" else "No Variant" },
        variantId = { name -> if (name == "6 Suits") 1 else 0 },
    )

    private fun inbound(command: String, json: String, adapter: WireAdapter = adapter()): List<ServerMessage> =
        adapter.inbound(decodeInbound(command, HanabJson.parseToJsonElement(json)))

    /** A reply does not say which table it is about: the request did. */
    private fun reply(command: String, json: String, adapter: WireAdapter = adapter(), table: Long = 1L): List<ServerMessage> =
        adapter.reply(decodeReply(command, HanabJson.parseToJsonElement(json))!!, table)

    private fun obj(text: String) = HanabJson.parseToJsonElement(text).jsonObject

    private val noVariant = """{"variantId":0,"speedrun":false,"deckPlays":false,"emptyClues":false,"oneExtraCard":false,"oneLessCard":false,"allOrNothing":false,"detrimentalCharacters":false}"""

    @Test fun welcomeBecomesTheOldOne() {
        val m = inbound(
            "welcome",
            """{"userId":110502,"username":"michiel-test","muted":false,"firstTimeUser":false,"settings":{},"friends":[],"blocked":[],"playingAtTables":[7],"spectatingAtTables":[{"tableId":9,"shadowingPlayerIndex":1}]}""",
        ).single()
        assertEquals("welcome", m.command)
        val d = m.data!!.jsonObject
        assertEquals(110502, d["userID"]!!.jsonPrimitive.int)
        assertEquals(listOf(7), d["playingAtTables"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(9, d["disconSpectatingTable"]!!.jsonPrimitive.int)
        assertEquals(1, d["disconShadowingSeat"]!!.jsonPrimitive.int)
        assertEquals("michiel-test's table", d["randomTableName"]!!.jsonPrimitive.content)
    }

    @Test fun usersAndTables() {
        val adapter = adapter()
        inbound("welcome", """{"userId":1,"username":"me","muted":false,"firstTimeUser":false,"settings":{},"friends":[],"blocked":[],"playingAtTables":[],"spectatingAtTables":[]}""", adapter)
        val users = inbound(
            "userList",
            """{"users":[{"userId":6,"username":"James","activity":{"type":"sharedReplay","tableId":8816},"hGroupConventions":true,"bot":false,"inactive":false}]}""",
            adapter,
        ).single()
        val james = (users.data as JsonArray).single().jsonObject
        assertEquals("James", james["name"]!!.jsonPrimitive.content)
        assertEquals("Shared Replay", james["status"]!!.jsonPrimitive.content)
        assertEquals(8816, james["tableID"]!!.jsonPrimitive.int)

        val table = inbound(
            "table",
            """{"tableId":8816,"owner":{"userId":2,"username":"sleepun"},"passwordProtected":true,"phase":{"type":"sharedReplay","gameId":5,"progress":62},"playerNames":["sleepun","me"],"settings":{"name":"Pretty Slide (#2)","maxPlayers":6,"options":{"variantId":1,"speedrun":false,"deckPlays":false,"emptyClues":false,"oneExtraCard":false,"oneLessCard":false,"allOrNothing":false,"detrimentalCharacters":false}},"spectators":[{"userId":6,"username":"James"}]}""",
            adapter,
        ).single()
        val t = table.data!!.jsonObject
        assertEquals(8816, t["id"]!!.jsonPrimitive.int)
        assertEquals("Pretty Slide (#2)", t["name"]!!.jsonPrimitive.content)
        assertEquals("6 Suits", t["variant"]!!.jsonPrimitive.content)
        assertEquals("true", t["sharedReplay"]!!.jsonPrimitive.content)
        assertEquals("true", t["running"]!!.jsonPrimitive.content)
        assertEquals("true", t["passwordProtected"]!!.jsonPrimitive.content)
        // We are one of its players.
        assertEquals("true", t["joined"]!!.jsonPrimitive.content)
        assertEquals(2, t["numPlayers"]!!.jsonPrimitive.int)
        assertEquals("James", t["spectators"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

        val pregame = inbound(
            "table",
            """{"tableId":3,"owner":{"userId":2,"username":"x"},"passwordProtected":false,"phase":{"type":"pregame"},"playerNames":["x"],"settings":{"name":"n","maxPlayers":5,"options":$noVariant},"spectators":[]}""",
            adapter,
        ).single().data!!.jsonObject
        assertEquals("false", pregame["running"]!!.jsonPrimitive.content)
        assertEquals("false", pregame["joined"]!!.jsonPrimitive.content)
    }

    @Test fun chatKeepsItsRoom() {
        val lobby = inbound("chat", """{"kind":{"type":"public","author":{"type":"user","username":"bob"}},"sentAt":"2026-11-11T07:08:44.337Z","text":"hi"}""").single().data!!.jsonObject
        assertEquals("lobby", lobby["room"]!!.jsonPrimitive.content)
        assertEquals("hi", lobby["msg"]!!.jsonPrimitive.content)
        assertEquals("bob", lobby["who"]!!.jsonPrimitive.content)
        val table = inbound("chat", """{"kind":{"type":"public","author":{"type":"user","username":"bob"}},"sentAt":"2026-11-11T07:08:44.337Z","tableId":5,"text":"yo"}""").single().data!!.jsonObject
        assertEquals("table5", table["room"]!!.jsonPrimitive.content)
        val pm = inbound("chat", """{"kind":{"type":"private","author":{"type":"user","username":"bob"},"recipient":"me"},"sentAt":"2026-11-11T07:08:44.337Z","text":"psst"}""").single().data!!.jsonObject
        assertEquals("me", pm["recipient"]!!.jsonPrimitive.content)
        val server = inbound("chat", """{"kind":{"type":"public","author":{"type":"server"}},"sentAt":"2026-11-11T07:08:44.337Z","text":"restart"}""").single().data!!.jsonObject
        assertEquals("server", server["source"]!!.jsonPrimitive.content)
        assertNull(server["who"])
    }

    @Test fun theGameStartsWithInitThenItsState() {
        val adapter = adapter()
        // Entering a table and the game starting are one message; its stage says which.
        val pregame = inbound("tableJoined", """{"tableId":8838,"stage":"pregame"}""", adapter)
        assertEquals(listOf("joined"), pregame.map { it.command })
        val start = inbound("tableJoined", """{"tableId":8838,"stage":"justStarted"}""", adapter)
        assertEquals(listOf("joined", "tableStart"), start.map { it.command })
        assertEquals("false", start[1].data!!.jsonObject["replay"]!!.jsonPrimitive.content)
        assertEquals("true", inbound("tableJoined", """{"tableId":8838,"stage":"replay"}""", adapter)[1].data!!.jsonObject["replay"]!!.jsonPrimitive.content)
        val init = reply(
            "gameInitLoad",
            """{"characters":[],"customDeal":false,"mode":{"type":"playing","ourPlayerIndex":2},"options":${noVariant.replace("\"variantId\":0", "\"variantId\":1")},"playerNames":["a","b","c"],"startedAt":"2026-11-11T07:09:51.884Z"}""",
            adapter, 8838L,
        ).single().data!!.jsonObject
        assertEquals("false", init["spectating"]!!.jsonPrimitive.content)
        assertEquals(2, init["ourPlayerIndex"]!!.jsonPrimitive.int)
        assertEquals("6 Suits", init["options"]!!.jsonObject["variantName"]!!.jsonPrimitive.content)
        assertEquals(8838, init["tableID"]!!.jsonPrimitive.int)

        val spectating = reply(
            "gameInitLoad",
            """{"characters":[],"customDeal":false,"mode":{"type":"spectating"},"options":$noVariant,"playerNames":["a","b"],"startedAt":"2026-11-11T07:09:51.884Z","tableId":1}""",
            adapter,
        ).single().data!!.jsonObject
        assertEquals("true", spectating["spectating"]!!.jsonPrimitive.content)
    }

    private fun game(vararg events: String): GameState {
        val adapter = adapter()
        reply("gameInitLoad", """{"characters":[],"customDeal":false,"mode":{"type":"playing","ourPlayerIndex":0},"options":$noVariant,"playerNames":["me","bob"],"startedAt":"2026-11-11T07:09:51.884Z","tableId":1}""", adapter)
        val state = inbound(
            "gameState",
            """{"events":[${events.joinToString(",")}],"notes":[],"phase":{"type":"replay","deck":[]},"spectators":[],"tableId":1}""",
            adapter,
        )
        val list = state.first { it.command == "gameActionList" }.data!!.jsonObject["list"]!!.jsonArray.map { it.jsonObject }
        assertTrue(list.none { it["type"]!!.jsonPrimitive.content == "status" || it["type"]!!.jsonPrimitive.content == "strike" })
        return GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        ).applyAll(list)
    }

    /** A misplay is an event of its own now (there is no "strike" event): it is a strike, and gives no clue back. */
    @Test fun aMisplayIsAStrike() {
        val g = game(
            """{"type":"draw","playerIndex":0,"order":0,"identity":{"suitIndex":0,"rank":3}}""",
            """{"type":"draw","playerIndex":0,"order":1,"identity":{"suitIndex":1,"rank":1}}""",
            """{"type":"draw","playerIndex":1,"order":2}""",
            """{"type":"draw","playerIndex":1,"order":3}""",
            """{"type":"turn","turn":0,"currentPlayerIndex":0}""",
            """{"type":"clue","playerIndex":0,"receiverIndex":1,"orders":[2],"clue":{"type":"rank","rank":1}}""",
            """{"type":"turn","turn":1,"currentPlayerIndex":1}""",
            """{"type":"misplay","playerIndex":1,"order":3,"identity":{"suitIndex":0,"rank":4}}""",
            """{"type":"turn","turn":2,"currentPlayerIndex":0}""",
        )
        assertEquals(1, g.strikes)
        // One clue given, none gained from the misplay.
        assertEquals(7, g.clues)
        assertEquals(2, g.turn)
    }

    @Test fun playsAndDiscardsMoveTheCluesAndTheScore() {
        val g = game(
            """{"type":"draw","playerIndex":0,"order":0}""",
            """{"type":"draw","playerIndex":0,"order":1}""",
            """{"type":"draw","playerIndex":1,"order":2,"identity":{"suitIndex":0,"rank":1}}""",
            """{"type":"clue","playerIndex":0,"receiverIndex":1,"orders":[2],"clue":{"type":"rank","rank":1}}""",
            """{"type":"play","playerIndex":1,"order":2,"identity":{"suitIndex":0,"rank":1}}""",
            """{"type":"clue","playerIndex":1,"receiverIndex":0,"orders":[],"clue":{"type":"color","color":0}}""",
            """{"type":"discard","playerIndex":0,"order":1,"identity":{"suitIndex":1,"rank":2}}""",
        )
        assertEquals(1, g.score)
        assertEquals(0, g.strikes)
        // 8, minus two clues, plus a discard.
        assertEquals(7, g.clues)
        assertEquals(25, g.maxScore)
    }

    /** In Cow & Pig and Duck the server hides what a clue says, but it is still a clue: it costs a token and touches cards. */
    @Test fun aClueWithItsContentHiddenStillCostsAToken() {
        val g = game(
            """{"type":"draw","playerIndex":1,"order":2,"identity":{"suitIndex":0,"rank":1}}""",
            """{"type":"clue","playerIndex":0,"receiverIndex":1,"orders":[2]}""",
        )
        assertEquals(7, g.clues)
    }

    @Test fun theBestReachableScoreFallsWhenEveryCopyIsDiscarded() {
        // All three copies of yellow 1 are discarded: the yellow stack can never start.
        val g = game(*(0..2).flatMap {
            listOf(
                """{"type":"draw","playerIndex":0,"order":$it,"identity":{"suitIndex":1,"rank":1}}""",
                """{"type":"discard","playerIndex":0,"order":$it,"identity":{"suitIndex":1,"rank":1}}""",
            )
        }.toTypedArray())
        assertEquals(20, g.maxScore)
    }

    @Test fun anUntimedClockCountsUpButTheAppReadsItDown() {
        val adapter = adapter()
        reply("gameInitLoad", """{"characters":[],"customDeal":false,"mode":{"type":"playing","ourPlayerIndex":0},"options":$noVariant,"playerNames":["a","b"],"startedAt":"2026-11-11T07:09:51.884Z","tableId":1}""", adapter)
        val times = inbound("gameEvent", """{"tableId":1,"event":{"type":"playerTimes","durationMs":4000,"playerTimesMs":[1500,2500]}}""", adapter).single()
        val action = times.data!!.jsonObject["action"]!!.jsonObject
        assertEquals(listOf("-1500", "-2500"), action["playerTimes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("4000", action["duration"]!!.jsonPrimitive.content)
    }

    @Test fun gameOverUsesTheOldNumbers() {
        val over = inbound("gameEvent", """{"tableId":1,"event":{"type":"gameOver","endCondition":"strikeout","voters":[]}}""").single()
        assertEquals(2, over.data!!.jsonObject["action"]!!.jsonObject["endCondition"]!!.jsonPrimitive.int)
    }

    @Test fun aReplyWithDataBecomesTheOldMessage() {
        val adapter = adapter()
        val reply = decodeReply("chatPmHistory", HanabJson.parseToJsonElement(
            """{"messages":[{"kind":{"type":"private","author":{"type":"user","username":"bob"},"recipient":"me"},"sentAt":"2026-11-11T07:08:44.337Z","text":"psst"}]}""",
        ))!!
        val m = adapter.reply(reply).single()
        assertEquals("chatPMHistory", m.command)
        assertEquals("psst", m.data!!.jsonObject["list"]!!.jsonArray.single().jsonObject["msg"]!!.jsonPrimitive.content)
        // A request with no reply data has nothing to read.
        assertNull(decodeReply("tableJoin", null))
    }

    @Test fun theVoteToEndTheGameIsSentAsAValue() {
        val adapter = adapter()
        fun vote() = (adapter.outbound("tableVoteForTermination", obj("""{"tableID":3}""")).single() as OutTableTerminateVote).data.voted
        assertTrue(vote())
        inbound("votes", """{"numVotesNeeded":2,"tableId":3,"voted":true}""", adapter)
        // We have voted: the app's toggle takes the vote back.
        assertEquals(false, vote())
    }

    @Test fun playAgainStartsTheNewGameAtOnce() {
        val restart = adapter().outbound("tableRestart", obj("""{"tableID":3,"hidePregame":true}""")).single() as OutTableRestart
        assertTrue(restart.data.startNow)
    }

    @Test fun aMessageThatDoesNotReadIsReportedOnce() {
        val adapter = adapter()
        val bad = decodeInbound("table", HanabJson.parseToJsonElement("""{"tableId":1}"""))
        assertTrue(bad is InInvalid)
        val first = adapter.inbound(bad).single()
        assertEquals("error", first.command)
        assertTrue(first.data!!.jsonObject["error"]!!.jsonPrimitive.content.contains("\"table\""))
        assertTrue(adapter.inbound(bad).isEmpty())
    }

    @Test fun commandsGoOutAsTypedMessages() {
        val a = adapter()
        val create = a.outbound("tableCreate", obj("""{"name":"My table","maxPlayers":4,"password":"pw","options":{"variantName":"6 Suits","timed":false,"speedrun":true,"cardCycle":false,"deckPlays":false}}""")).single() as OutTableCreate
        assertEquals("My table", create.data.settings.name)
        assertEquals(4, create.data.settings.maxPlayers)
        assertEquals(1, create.data.settings.options.variantId)
        assertTrue(create.data.settings.options.speedrun)
        assertEquals("pw", create.data.password)
        val wire = create.encode()
        assertEquals("tableCreate", wire.first)
        assertEquals(1, wire.second.jsonObject["settings"]!!.jsonObject["options"]!!.jsonObject["variantId"]!!.jsonPrimitive.int)

        val noPassword = a.outbound("tableCreate", obj("""{"name":"x","maxPlayers":2,"options":{"variantName":"No Variant"}}""")).single() as OutTableCreate
        assertNull(noPassword.data.password)
        assertNull(noPassword.encode().second.jsonObject["password"])

        assertEquals("gameInitLoad", a.outbound("getGameInfo1", obj("""{"tableID":3}""")).single().encode().first)
        assertEquals("gameStateLoad", a.outbound("getGameInfo2", obj("""{"tableID":3}""")).single().encode().first)
        assertEquals("gameLoaded", a.outbound("loaded", obj("""{"tableID":3}""")).single().encode().first)
        assertEquals("tableTerminateVote", a.outbound("tableVoteForTermination", obj("""{"tableID":3}""")).single().encode().first)

        val spectate = a.outbound("tableSpectate", obj("""{"tableID":3,"shadowingPlayerIndex":-1}""")).single() as OutTableSpectate
        assertNull(spectate.data.shadowingPlayerIndex)
        assertEquals(3L, spectate.data.tableId)

        val chat = a.outbound("chat", obj("""{"msg":"hello","room":"table7"}""")).single() as OutChat
        assertEquals(7L, chat.data.tableId)
        assertEquals("hello", chat.data.text)
        assertNull((a.outbound("chat", obj("""{"msg":"hello","room":"lobby"}""")).single() as OutChat).data.tableId)
        assertEquals("chatPm", a.outbound("chatPM", obj("""{"msg":"hi","recipient":"bob","room":"lobby"}""")).single().encode().first)
    }

    @Test fun aTableIsStartedWithTheIdsOfItsPlayers() {
        val adapter = adapter()
        inbound(
            "pregame",
            """{"owner":{"userId":1,"username":"a"},"passwordProtected":false,"players":[{"present":true,"userId":1,"username":"a","stats":{"numGames":1,"variant":{"bestScores":[],"numGames":1}}},{"present":true,"userId":2,"username":"b","stats":{"numGames":1,"variant":{"bestScores":[],"numGames":1}}}],"settings":{"name":"n","maxPlayers":5,"options":$noVariant},"tableId":4}""",
            adapter,
        )
        val start = adapter.outbound("tableStart", obj("""{"tableID":4}""")).single() as OutTableStart
        assertEquals(listOf(1, 2), start.data.intendedPlayerIds)
    }

    @Test fun actionsGoOutAsPlayerActions() {
        val a = adapter()
        fun action(type: Int, target: Int, value: Int) =
            (a.outbound("action", obj("""{"tableID":2,"type":$type,"target":$target,"value":$value}""")).single() as OutPlayerAction).data.action
        assertEquals(12, (action(0, 12, 0) as PlayerActionPlay).order)
        assertEquals(3, (action(1, 3, 0) as PlayerActionDiscard).order)
        val colour = action(2, 1, 4) as PlayerActionClue
        assertEquals(4, (colour.clue as ClueColor).color)
        assertEquals(1, colour.receiverIndex)
        assertEquals(5, ((action(3, 1, 5) as PlayerActionClue).clue as ClueRankVariant).rank)
    }

    @Test fun theExportIsReadAsBefore() {
        val text = """{"playerNames":["a","b"],"deck":[{"suitIndex":0,"rank":1}],"actions":[{"type":"clue","receiverIndex":1,"clue":{"type":"rank","rank":1}},{"type":"play","order":8},{"type":"discard","order":3},{"type":"clue","receiverIndex":0,"clue":{"type":"color","color":3}}],"options":{"variantId":0},"seed":"p2v0s1"}"""
        val old = obj(WireAdapter.legacyExport(text))
        assertEquals(listOf("a", "b"), old["players"]!!.jsonArray.map { it.jsonPrimitive.content })
        val moves = old["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(3, 0, 1, 2), moves.map { it["type"]!!.jsonPrimitive.int })
        assertEquals(listOf(1, 8, 3, 0), moves.map { it["target"]!!.jsonPrimitive.int })
        assertEquals(listOf(1, 0, 0, 3), moves.map { it["value"]!!.jsonPrimitive.int })
    }
}

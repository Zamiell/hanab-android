package live.hanab.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.game.GameState
import live.hanab.client.net.HanabJson
import live.hanab.client.net.WireAdapter
import live.hanab.client.net.unpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new server's messages (frames copied from hanab.live) become the ones the app reads, and the other way round. */
class WireAdapterTest {
    private fun inbound(frame: String, adapter: WireAdapter = WireAdapter()) = adapter.inbound(unpack(frame)!!)

    private fun obj(text: String) = HanabJson.parseToJsonElement(text).jsonObject

    @Test fun welcomeBecomesTheOldOne() {
        val m = inbound("""welcome {"userId":110502,"username":"michiel-test","muted":false,"firstTimeUser":false,"settings":{},"friends":[],"blocked":[],"playingAtTables":[7],"spectatingAtTable":{"tableId":9,"shadowingPlayerIndex":1}}""").single()
        assertEquals("welcome", m.command)
        val d = m.data!!.jsonObject
        assertEquals(110502, d["userID"]!!.jsonPrimitive.int)
        assertEquals(listOf(7), d["playingAtTables"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(9, d["disconSpectatingTable"]!!.jsonPrimitive.int)
        assertEquals(1, d["disconShadowingSeat"]!!.jsonPrimitive.int)
        assertEquals("michiel-test's table", d["randomTableName"]!!.jsonPrimitive.content)
    }

    @Test fun usersAndTables() {
        val adapter = WireAdapter()
        val users = inbound("""userList [{"userId":6,"username":"James","activity":{"type":"sharedReplay","tableId":8816},"hGroupConventions":true,"bot":false,"inactive":false}]""", adapter).single()
        val james = (users.data as JsonArray).single().jsonObject
        assertEquals("James", james["name"]!!.jsonPrimitive.content)
        assertEquals("Shared Replay", james["status"]!!.jsonPrimitive.content)
        assertEquals(8816, james["tableID"]!!.jsonPrimitive.int)
        // A change of "inactive" names only the user.
        val inactive = inbound("""userInactive {"userId":6,"inactive":true}""", adapter).single()
        assertEquals("James", inactive.data!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("true", inactive.data!!.jsonObject["inactive"]!!.jsonPrimitive.content)

        val table = inbound("""table {"gameId":2063357,"tableId":8816,"joined":false,"settings":{"name":"Pretty Slide (#2)","maxPlayers":6,"options":{"variantName":"No Variant","speedrun":false,"deckPlays":false,"emptyClues":false,"oneExtraCard":false,"oneLessCard":false,"allOrNothing":false,"detrimentalCharacters":false}},"ownerId":71192,"passwordProtected":true,"playerNames":["sleepun","dmihcle"],"progress":62,"phase":"sharedReplay","spectators":[{"username":"James"}]}""").single()
        val t = table.data!!.jsonObject
        assertEquals(8816, t["id"]!!.jsonPrimitive.int)
        assertEquals("Pretty Slide (#2)", t["name"]!!.jsonPrimitive.content)
        assertEquals("true", t["sharedReplay"]!!.jsonPrimitive.content)
        assertEquals("true", t["running"]!!.jsonPrimitive.content)
        assertEquals("true", t["passwordProtected"]!!.jsonPrimitive.content)
        assertEquals(2, t["numPlayers"]!!.jsonPrimitive.int)
        assertEquals("James", t["spectators"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun chatKeepsItsRoom() {
        val lobby = inbound("""chat {"text":"hi","source":"user","sentAt":"2026-11-11T07:08:44.337Z","username":"bob"}""").single().data!!.jsonObject
        assertEquals("lobby", lobby["room"]!!.jsonPrimitive.content)
        assertEquals("hi", lobby["msg"]!!.jsonPrimitive.content)
        assertEquals("bob", lobby["who"]!!.jsonPrimitive.content)
        val table = inbound("""chat {"text":"yo","source":"user","sentAt":"2026-11-11T07:08:44.337Z","username":"bob","tableId":5}""").single().data!!.jsonObject
        assertEquals("table5", table["room"]!!.jsonPrimitive.content)
        val pm = inbound("""chat {"text":"psst","source":"user","sentAt":"2026-11-11T07:08:44.337Z","username":"bob","recipient":"me"}""").single().data!!.jsonObject
        assertEquals("me", pm["recipient"]!!.jsonPrimitive.content)
    }

    @Test fun theGameStartsWithInitThenItsEvents() {
        val adapter = WireAdapter()
        assertEquals("tableStart", inbound("""tableStarted {"tableId":8838,"stage":"inProgress"}""", adapter).single().command)
        val init = inbound("""init {"tableId":8838,"playerNames":["Perlpilz","chamster","Tailz"],"mode":"spectating","hasCustomSeed":false,"seed":"p3v0s292","startedAt":"2026-11-11T07:09:51.884Z","options":{"variantName":"No Variant","speedrun":false},"characters":[],"pauseQueued":false}""", adapter).single().data!!.jsonObject
        assertEquals("true", init["spectating"]!!.jsonPrimitive.content)
        assertEquals("No Variant", init["options"]!!.jsonObject["variantName"]!!.jsonPrimitive.content)
        assertEquals(8838, init["tableID"]!!.jsonPrimitive.int)
    }

    /** A misplay is a failed discard now (there is no "strike" event): it is a strike, and gives no clue back. */
    @Test fun aMisplayIsAStrike() {
        val adapter = WireAdapter()
        val events = """
            {"type":"draw","playerIndex":0,"order":0,"identity":{"suitIndex":0,"rank":3}},
            {"type":"draw","playerIndex":0,"order":1,"identity":{"suitIndex":1,"rank":1}},
            {"type":"draw","playerIndex":1,"order":2},
            {"type":"draw","playerIndex":1,"order":3},
            {"type":"turn","turn":0,"currentPlayerIndex":0},
            {"type":"clue","clue":{"type":"rank","value":1},"giver":0,"orders":[2],"target":1},
            {"type":"turn","turn":1,"currentPlayerIndex":1},
            {"type":"discard","failed":true,"playerIndex":1,"order":3,"identity":{"suitIndex":0,"rank":4}},
            {"type":"turn","turn":2,"currentPlayerIndex":0}
        """.trimIndent().lines().joinToString("")
        val list = inbound("""gameEventList {"tableId":1,"events":[$events]}""", adapter).single()
        assertEquals("gameActionList", list.command)
        val actions = list.data!!.jsonObject["list"]!!.jsonArray.map { it.jsonObject }
        assertTrue(actions.none { it["type"]!!.jsonPrimitive.content == "status" || it["type"]!!.jsonPrimitive.content == "strike" })
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        ).applyAll(actions)
        assertEquals(1, g.strikes)
        // One clue given, none gained from the misplay.
        assertEquals(7, g.clues)
        assertEquals(2, g.turn)
    }

    @Test fun playsAndDiscardsMoveTheCluesAndTheScore() {
        val adapter = WireAdapter()
        val events = """
            {"type":"draw","playerIndex":0,"order":0},
            {"type":"draw","playerIndex":0,"order":1},
            {"type":"draw","playerIndex":1,"order":2,"identity":{"suitIndex":0,"rank":1}},
            {"type":"clue","clue":{"type":"rank","value":1},"giver":0,"orders":[2],"target":1},
            {"type":"play","playerIndex":1,"order":2,"identity":{"suitIndex":0,"rank":1}},
            {"type":"clue","clue":{"type":"color","value":0},"giver":1,"orders":[],"target":0},
            {"type":"discard","failed":false,"playerIndex":0,"order":1,"identity":{"suitIndex":1,"rank":2}}
        """.trimIndent().lines().joinToString("")
        val actions = inbound("""gameEventList {"tableId":1,"events":[$events]}""", adapter).single().data!!.jsonObject["list"]!!.jsonArray.map { it.jsonObject }
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        ).applyAll(actions)
        assertEquals(1, g.score)
        assertEquals(0, g.strikes)
        // 8, minus two clues, plus a discard.
        assertEquals(7, g.clues)
        assertEquals(25, g.maxScore)
    }

    @Test fun theBestReachableScoreFallsWhenEveryCopyIsDiscarded() {
        val adapter = WireAdapter()
        // Both yellow 2s... all three copies of yellow 1 are discarded: the yellow stack can never start.
        val discards = (0..2).joinToString(",") {
            """{"type":"draw","playerIndex":0,"order":$it,"identity":{"suitIndex":1,"rank":1}},{"type":"discard","failed":false,"playerIndex":0,"order":$it,"identity":{"suitIndex":1,"rank":1}}"""
        }
        val actions = inbound("""gameEventList {"tableId":1,"events":[$discards]}""", adapter).single().data!!.jsonObject["list"]!!.jsonArray.map { it.jsonObject }
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        ).applyAll(actions)
        assertEquals(20, g.maxScore)
    }

    @Test fun anUntimedClockCountsUpButTheAppReadsItDown() {
        val adapter = WireAdapter()
        inbound("""init {"tableId":1,"playerNames":["a","b"],"mode":"playing","hasCustomSeed":false,"startedAt":"2026-11-11T07:09:51.884Z","options":{"variantName":"No Variant"},"characters":[],"pauseQueued":false}""", adapter)
        val times = inbound("""gameEvent {"tableId":1,"event":{"type":"playerTimes","playerTimesMs":[1500,2500],"durationMs":4000}}""", adapter).single()
        val action = times.data!!.jsonObject["action"]!!.jsonObject
        assertEquals(listOf("-1500", "-2500"), action["playerTimes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("4000", action["duration"]!!.jsonPrimitive.content)
    }

    @Test fun gameOverUsesTheOldNumbers() {
        val over = inbound("""gameEvent {"tableId":1,"event":{"type":"gameOver","endCondition":"strikeout","playerIndex":1}}""").single()
        assertEquals(2, over.data!!.jsonObject["action"]!!.jsonObject["endCondition"]!!.jsonPrimitive.int)
    }

    @Test fun commandsGoOutInTheNewForm() {
        val a = WireAdapter()
        val create = a.outbound("tableCreate", obj("""{"name":"My table","maxPlayers":4,"password":"pw","options":{"variantName":"6 Suits","timed":false,"speedrun":true,"cardCycle":false,"deckPlays":false}}""")).single()
        assertEquals("tableCreate", create.first)
        val settings = create.second!!["settings"]!!.jsonObject
        assertEquals("My table", settings["name"]!!.jsonPrimitive.content)
        assertEquals(4, settings["maxPlayers"]!!.jsonPrimitive.int)
        val options = settings["options"]!!.jsonObject
        assertEquals("6 Suits", options["variantName"]!!.jsonPrimitive.content)
        assertEquals("true", options["speedrun"]!!.jsonPrimitive.content)
        assertNull(options["timed"])
        assertNull(options["cardCycle"])
        assertEquals("pw", create.second!!["password"]!!.jsonPrimitive.content)

        val noPassword = a.outbound("tableCreate", obj("""{"name":"x","maxPlayers":2,"options":{"variantName":"No Variant"}}""")).single()
        assertNull(noPassword.second!!["password"])

        assertEquals("gameInitLoad" to 3, a.outbound("getGameInfo1", obj("""{"tableID":3}""")).single().let { it.first to it.second!!["tableId"]!!.jsonPrimitive.int })
        assertEquals("gameStateLoad", a.outbound("getGameInfo2", obj("""{"tableID":3}""")).single().first)
        assertEquals("tableTerminateVote", a.outbound("tableVoteForTermination", obj("""{"tableID":3}""")).single().first)

        val spectate = a.outbound("tableSpectate", obj("""{"tableID":3,"shadowingPlayerIndex":-1}""")).single().second!!
        assertNull(spectate["shadowingPlayerIndex"])

        val chat = a.outbound("chat", obj("""{"msg":"hello","room":"table7"}""")).single()
        assertEquals("chat", chat.first)
        assertEquals(7, chat.second!!["tableId"]!!.jsonPrimitive.int)
        assertEquals("hello", chat.second!!["text"]!!.jsonPrimitive.content)
        assertNull(a.outbound("chat", obj("""{"msg":"hello","room":"lobby"}""")).single().second!!["tableId"])
        assertEquals("chatPm", a.outbound("chatPM", obj("""{"msg":"hi","recipient":"bob","room":"lobby"}""")).single().first)
    }

    @Test fun actionsGoOutAsPlayerActions() {
        val a = WireAdapter()
        fun action(type: Int, target: Int, value: Int) =
            a.outbound("action", obj("""{"tableID":2,"type":$type,"target":$target,"value":$value}""")).single().second!!["action"]!!.jsonObject
        val play = action(0, 12, 0)
        assertEquals("play", play["type"]!!.jsonPrimitive.content)
        assertEquals(12, play["order"]!!.jsonPrimitive.int)
        assertEquals("discard", action(1, 3, 0)["type"]!!.jsonPrimitive.content)
        val colour = action(2, 1, 4)
        assertEquals("clue", colour["type"]!!.jsonPrimitive.content)
        assertEquals("color", colour["clue"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(4, colour["clue"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals("rank", action(3, 1, 5)["clue"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun theExportIsReadAsBefore() {
        val text = """{"gameId":9,"playerNames":["a","b"],"deck":[{"suitIndex":0,"rank":1}],"actions":[{"type":"clue","target":1,"clue":{"type":"rank","value":1}},{"type":"play","order":8},{"type":"discard","order":3},{"type":"clue","target":0,"clue":{"type":"color","value":3}}],"options":{"variantName":"No Variant"}}"""
        val old = obj(WireAdapter.legacyExport(text))
        assertEquals(listOf("a", "b"), old["players"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(old.containsKey("playerNames"))
        val moves = old["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(3, 0, 1, 2), moves.map { it["type"]!!.jsonPrimitive.int })
        assertEquals(listOf(1, 8, 3, 0), moves.map { it["target"]!!.jsonPrimitive.int })
        assertEquals(listOf(1, 0, 0, 3), moves.map { it["value"]!!.jsonPrimitive.int })
    }

    @Test fun theSiteVersionIsTheHashOfItsMainScript() {
        val html = """<script src="/public/js/bundles/main.5d5aaee6b08749676ef9280a40448cc78aa99890.min.js"></script>"""
        assertEquals("5d5aaee6b08749676ef9280a40448cc78aa99890", live.hanab.client.net.parseSiteVersion(html))
        assertNull(live.hanab.client.net.parseSiteVersion("<html>no script</html>"))
    }
}

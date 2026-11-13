package live.hanab.client

import live.hanab.client.game.Card
import live.hanab.client.game.START_RANK
import kotlinx.serialization.json.JsonObject
import live.hanab.client.game.GameState
import live.hanab.client.game.NoteEntry
import live.hanab.client.net.HanabJson
import live.hanab.client.net.unpack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameStateTest {
    private fun live(frame: String): JsonObject {
        val m = unpack(frame)!!
        assertEquals("gameAction", m.command)
        return (m.data as JsonObject)["action"] as JsonObject
    }

    private fun start() = GameState(
        tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
        variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
    ).applyAll(
        (0..3).map { HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":0,"order":$it,"suitIndex":-1,"rank":-1}""") as JsonObject } +
        (4..7).map { HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":$it,"suitIndex":0,"rank":1}""") as JsonObject }
    )

    @Test fun livePlayThenDraw() {
        var g = start()
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"play","playerIndex":0,"order":2,"suitIndex":0,"rank":1}}"""))
        assertFalse(2 in g.hands[0])
        assertEquals(1, g.stacks[0])
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"draw","playerIndex":0,"order":8,"suitIndex":-1,"rank":-1}}"""))
        assertEquals(listOf(8, 3, 1, 0), g.hands[0])
    }

    @Test fun liveClueUpdatesOwnHand() {
        var g = start()
        g = g.apply(live("""gameAction {"tableID":1,"action":{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[1,3],"target":0,"ignoreNegative":false}}"""))
        assertEquals(setOf(5), g.cards[1]!!.possibleRanks)
        assertEquals(setOf(1, 2, 3, 4), g.cards[0]!!.possibleRanks)
    }

    @Test fun deadCards() {
        fun d(order: Int, rank: Int) = HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":$order,"suitIndex":0,"rank":$rank,"failed":false}""") as JsonObject
        fun draw(order: Int, rank: Int) = HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":$order,"suitIndex":0,"rank":$rank}""") as JsonObject
        var g = start().applyAll(listOf(draw(20, 2), draw(21, 2), draw(22, 3), draw(23, 1)))
        // red 1 not played yet: red 3 is alive; discarding both red 2s kills it.
        assertFalse(g.isDead(g.cards[22]!!))
        g = g.applyAll(listOf(d(20, 2), d(21, 2)))
        assert(g.isDead(g.cards[22]!!))
        // a played red 1 is dead
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"play","playerIndex":1,"order":23,"suitIndex":0,"rank":1}""") as JsonObject)
        assert(g.isDead(g.cards[23]!!))
    }
    @Test fun inferenceRemovesExhaustedIdentities() {
        // We hold orders 0..3 (unknown). Bob (player 1) holds red 1s at 4..7 in start(); put two more
        // red 1s... the third copy: discard one red 1 and Bob holds two => all 3 red 1s are visible to us.
        var g = start()
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":4,"suitIndex":0,"rank":1,"failed":false}""") as JsonObject)
        // Seen red 1s: 4 (discarded), 5, 6, 7 => 4 copies > 3, so red 1 is impossible for our cards.
        // Clue one of our cards as red (touches order 1): direct = red 1..5, inferred loses red 1.
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"list":[1],"target":0,"ignoreNegative":false}""") as JsonObject)
        val k = g.knowledgeOf(0).getValue(1)
        assert(0 to 1 in k.direct)
        assert(0 to 1 !in k.inferred)
        assertEquals(setOf(2, 3, 4, 5), k.inferredRanks)
    }
    @Test fun criticalCards() {
        var g = start()
        assert(g.isCriticalIdentity(0, 5))          // 5s have a single copy
        assert(!g.isCriticalIdentity(0, 2))         // two copies still around
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"draw","playerIndex":1,"order":30,"suitIndex":0,"rank":2}""") as JsonObject)
        g = g.apply(HanabJson.parseToJsonElement("""{"type":"discard","playerIndex":1,"order":30,"suitIndex":0,"rank":2,"failed":false}""") as JsonObject)
        assert(g.isCriticalIdentity(0, 2))          // one red 2 gone: the other is critical
        assert(!g.isCriticalIdentity(0, 1) )        // three red 1s, none discarded
    }
    @Test fun replayToEarlierTurn() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        var g = start()
        g = g.apply(j("""{"type":"turn","num":0,"currentPlayerIndex":0}"""))
        g = g.apply(j("""{"type":"play","playerIndex":0,"order":2,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":8,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        assertEquals(2, g.turnStops.size)
        val past = g.replayTo(g.turnStops[0])
        assert(2 in past.hands[0])        // before the play, the card is still in hand
        assertEquals(0, past.stacks[0])
        assert(past.readOnly && !past.ourTurn)
        assertFalse(2 in g.hands[0])      // live state unchanged
        assertEquals(1, g.stacks[0])
        assertEquals(2, g.remaining(0, 1)) // one red 1 played of three
    }
    @Test fun rainbowIsTouchedByEveryColour() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Yellow"),
            live.hanab.client.game.SuitInfo("Rainbow", colors = emptyList(), allColors = true),
        )
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Rainbow (3 Suits)", suits = infos.map { it.name }, suitInfos = infos,
        )
        assertEquals(listOf("Red", "Yellow"), g.clueColors)        // Rainbow adds no colour of its own
        assert(g.touchesColor(2, 3, 0) && g.touchesColor(2, 3, 1))    // Rainbow: touched by red and yellow
        assert(g.touchesColor(0, 3, 0) && !g.touchesColor(0, 3, 1))
        // Our card 0 is touched by a red clue: it is Red or Rainbow, never Yellow.
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":1,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        assertEquals(setOf(0, 2), g.cards[0]!!.possibleSuits)
        // Card 1 was not touched by red, so it is neither Red nor Rainbow: it must be Yellow.
        assertEquals(setOf(1), g.cards[1]!!.possibleSuits)
    }
    @Test fun turnOrderAndFinalScore() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        val g = GameState(
            tableID = 1, playerNames = listOf("a", "b", "c", "me", "e"), ourIndex = 3, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // After "me" (3) come e (4), a (0), b (1), c (2): top to bottom, with me at the bottom.
        assertEquals(listOf(4, 0, 1, 2), g.othersInTurnOrder)
        var s = g.apply(j("""{"type":"status","clues":4,"score":17,"maxScore":25}"""))
        assertEquals(17, s.finalScore)                       // in progress: progress counts
        s = s.apply(j("""{"type":"gameOver","endCondition":2,"playerIndex":0,"votes":null}"""))
        assertEquals(0, s.finalScore)                        // strikeout: score is zero
        val won = g.apply(j("""{"type":"status","clues":4,"score":25,"maxScore":25}"""))
            .apply(j("""{"type":"gameOver","endCondition":1,"playerIndex":0,"votes":null}"""))
        assertEquals(25, won.finalScore)                     // normal ending keeps the score
    }
    @Test fun blackSuitHasOneCopyOfEachRank() {
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Black", oneOfEach = true),
        )
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Black (2 Suits)", suits = listOf("Red", "Black"), suitInfos = infos,
        )
        assertEquals(15, g.deckSize)                         // 10 red + 5 black
        assertEquals(1, g.copies(1, 1))                      // one black 1, not three
        assertEquals(3, g.copies(0, 1))                      // red 1s unchanged
        assert(g.isCriticalIdentity(1, 1) && g.isCriticalIdentity(1, 3))   // every black card is critical
        assert(!g.isCriticalIdentity(0, 3))
    }
    @Test fun denominatorIsPerfectScoreAfterTheGame() {
        fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject
        var g = GameState(
            tableID = 1, playerNames = listOf("a", "me"), ourIndex = 1, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // A critical card was discarded: the best reachable score fell to 24 while the game is on.
        g = g.apply(j("""{"type":"status","clues":4,"score":24,"maxScore":24}"""))
        assertEquals(24, g.scoreDenominator)                 // in progress: show what is still reachable
        assertEquals(25, g.perfectScore)
        g = g.apply(j("""{"type":"gameOver","endCondition":1,"playerIndex":0,"votes":null}"""))
        assertEquals(25, g.scoreDenominator)                 // finished: out of the true perfect score
        assertEquals("Out of cards", g.endText)              // 24/25 is not a perfect game
    }
    private fun j(s: String) = HanabJson.parseToJsonElement(s) as JsonObject

    /** Two cards that could each only be red 5 or yellow 5 hold both 5s between them. */
    @Test fun deductionFindsPigeonholes() {
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "2 Suits", suits = listOf("Red", "Yellow"),
        )
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":-1,"rank":-1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":1,"suitIndex":-1,"rank":-1}"""))
        // A 5 clue touches both: each is a red 5 or a yellow 5.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[0,1],"target":0,"ignoreNegative":false}"""))
        // A new card, no clues at all: any of the 10 identities by clues alone.
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":2,"suitIndex":-1,"rank":-1}"""))
        val k = g.knowledgeOf(0).getValue(2)
        assertEquals(setOf(1, 2, 3, 4, 5), k.directRanks)
        // Both 5s are taken by the two clued cards, so the new card cannot be a 5.
        assertEquals(setOf(1, 2, 3, 4), k.inferredRanks)
        // The two clued cards themselves stay red-5-or-yellow-5.
        assertEquals(setOf(0 to 5, 1 to 5), g.knowledgeOf(0).getValue(0).inferred)
    }

    /** Watching a game: the player in the observed slot has deductions too (so the toggle works there). */
    @Test fun spectatorCanSeeWhatTheObservedPlayerKnows() {
        var g = GameState(
            tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = true,
            variantName = "2 Suits", suits = listOf("Red", "Yellow"),
        )
        g = g.apply(j("""{"type":"draw","playerIndex":0,"order":0,"suitIndex":0,"rank":5}"""))   // a spectator sees red 5
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":1,"suitIndex":1,"rank":5}"""))   // and yellow 5
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":5},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        val k = g.knowledgeOf(0).getValue(0)
        // Player a was told "5", and can see b's yellow 5: their card must be the red 5.
        assertEquals(setOf(0 to 5), k.inferred)
    }
    @Test fun everyonesNotesAreKeptPerCard() {
        val g = GameState(
            tableID = 1, playerNames = listOf("a", "b"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
        )
        // The server's "noteList": one entry per person, each with a text per card order.
        val all = g.withAllNotes(
            listOf(
                Triple("a", listOf("", "r5", ""), false),
                Triple("b", listOf("chop", "", ""), false),
                Triple("watcher", listOf("", "r5?", "dead"), true),
            ),
        )
        assertEquals(listOf("b"), all.allNotes[0]!!.map { it.name })
        assertEquals(setOf("a", "watcher"), all.allNotes[1]!!.map { it.name }.toSet())
        assertEquals(true, all.allNotes[2]!!.single().isSpectator)
        // A live per-card update replaces that card's notes only; blank ones are dropped.
        val updated = all.withCardNotes(1, listOf(NoteEntry("a", "", false), NoteEntry("watcher", "no, y5", true)))
        assertEquals(listOf("watcher"), updated.allNotes[1]!!.map { it.name })
        assertEquals(listOf("b"), updated.allNotes[0]!!.map { it.name })
        // A change to one author's note keeps the other authors' notes on the card.
        val one = all.withCardNote(1, NoteEntry("watcher", "no, y5", true))
        assertEquals(setOf("a" to "r5", "watcher" to "no, y5"), one.allNotes[1]!!.map { it.name to it.text }.toSet())
        assertEquals(listOf("a"), one.withCardNote(1, NoteEntry("watcher", "", true)).allNotes[1]!!.map { it.name })
    }
    /** Turn 1 (no clues or plays yet) can be stepped back to: the server sends no "turn" action for it. */
    @Test fun startingPositionIsAStop() {
        var g = start()   // the deal: draws only
        g = g.apply(j("""{"type":"status","clues":8,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[4],"target":1,"ignoreNegative":false}"""))
        g = g.apply(j("""{"type":"status","clues":7,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        g = g.apply(j("""{"type":"play","playerIndex":1,"order":4,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":8,"suitIndex":0,"rank":2}"""))
        g = g.apply(j("""{"type":"status","clues":7,"score":1,"maxScore":10}"""))
        g = g.apply(j("""{"type":"turn","num":2,"currentPlayerIndex":0}"""))
        // stops: the start, after move 1, after move 2
        assertEquals(3, g.turnStops.size)
        val start = g.replayTo(g.turnStops[0])
        assertEquals(0, start.turn)                              // "Turn 1"
        assertEquals(8, start.clues)                             // no clue given yet
        assertEquals(0, start.stacks[0])                         // nothing played
        assertEquals(null, start.lastClue)                       // and no clue bubble
        assertEquals(4, start.hands[1].size)                     // everybody still holds their dealt cards
        assertEquals(1, g.replayTo(g.turnStops[1]).turn)         // after the first move
    }
    @Test fun cardCyclingMovesTheGiversChopToTheFront() {
        var g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "No Variant", suits = listOf("Red", "Yellow", "Green", "Blue", "Purple"),
            options = live.hanab.client.game.GameOptions(cardCycle = true),
        )
        // bob holds orders 4..7, newest first: [7, 6, 5, 4]; his oldest (the chop) is 4.
        for (o in 0..3) g = g.apply(j("""{"type":"draw","playerIndex":0,"order":$o,"suitIndex":-1,"rank":-1}"""))
        for (o in 4..7) g = g.apply(j("""{"type":"draw","playerIndex":1,"order":$o,"suitIndex":0,"rank":1}"""))
        assertEquals(listOf(7, 6, 5, 4), g.hands[1])
        // bob clues me: his chop (4) goes to the newest slot.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":1,"list":[0],"target":0,"ignoreNegative":false}"""))
        assertEquals(listOf(4, 7, 6, 5), g.hands[1])
        assertEquals(listOf(3, 2, 1, 0), g.hands[0])             // the receiver's hand is untouched
        // I clue bob's oldest card (5 is now the oldest): it counts as clued, so the chop is 6.
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[5],"target":1,"ignoreNegative":false}"""))
        // my own chop is 1 (the card 0 was clued): it moves to the front of MY hand
        assertEquals(listOf(1, 3, 2, 0), g.hands[0])
    }

    @Test fun characterTextGetsItsRandomValue() {
        val colors = listOf("Red", "Yellow", "Green")
        val suits = listOf("Red", "Yellow", "Green", "Blue")
        assertEquals("Can only clue numbers and green", live.hanab.client.game.fillCharacterText("Can only clue numbers and [random color]", 2, colors, suits))
        assertEquals("Cannot clue 4s", live.hanab.client.game.fillCharacterText("Cannot clue [random number]s", 4, colors, suits).replace("Cannot clue 4s", "Cannot clue 4s"))
        assertEquals("Only Blue", live.hanab.client.game.fillCharacterText("Only [random suit]", 3, colors, suits))
        assertEquals("No placeholder", live.hanab.client.game.fillCharacterText("No placeholder", 1, colors, suits))
    }

    @Test fun tableOptionsSerialiseTheServersNames() {
        val json = live.hanab.client.net.TableOpts(variantName = "Rainbow (5 Suits)", cardCycle = true, emptyClues = true).toJson()
        assertEquals("\"Rainbow (5 Suits)\"", json["variantName"].toString())
        assertEquals("true", json["cardCycle"].toString())
        assertEquals("true", json["emptyClues"].toString())
        assertEquals("false", json["speedrun"].toString())
        assertEquals("false", json["timed"].toString())
        assertEquals(listOf("Card cycling", "Empty clues"), live.hanab.client.net.TableOpts(cardCycle = true, emptyClues = true).activeLabels)
    }
    /** Stepping one turn forward in a replay finds the play / discard to animate. */
    @Test fun movesBetweenStopsFindsThePlayOrDiscard() {
        var g = start()
        g = g.apply(j("""{"type":"status","clues":8,"score":0,"maxScore":10}"""))
        g = g.apply(j("""{"type":"clue","clue":{"type":1,"value":1},"giver":0,"list":[4],"target":1,"ignoreNegative":false}"""))
        g = g.apply(j("""{"type":"turn","num":1,"currentPlayerIndex":1}"""))
        g = g.apply(j("""{"type":"play","playerIndex":1,"order":4,"suitIndex":0,"rank":1}"""))
        g = g.apply(j("""{"type":"draw","playerIndex":1,"order":8,"suitIndex":0,"rank":2}"""))
        g = g.apply(j("""{"type":"turn","num":2,"currentPlayerIndex":0}"""))
        g = g.apply(j("""{"type":"discard","playerIndex":0,"order":0,"suitIndex":1,"rank":3,"failed":true}"""))
        g = g.apply(j("""{"type":"turn","num":3,"currentPlayerIndex":1}"""))
        assertEquals(4, g.turnStops.size)                                   // the start, then after each of 3 moves
        assertEquals(emptyList<Any>(), g.movesBetweenStops(0, 1))           // a clue: nothing flies
        val play = g.movesBetweenStops(1, 2).single()
        assertEquals(true, play.play); assertEquals(4, play.order); assertEquals(0, play.suit); assertEquals(1, play.rank)
        val miss = g.movesBetweenStops(2, 3).single()
        assertEquals(false, miss.play); assertEquals(true, miss.failed); assertEquals(0, miss.order)
        assertEquals(emptyList<Any>(), g.movesBetweenStops(0, 2))           // a jump
        assertEquals(emptyList<Any>(), g.movesBetweenStops(2, 1))           // going back
    }
    private fun twoSuits(rules: live.hanab.client.game.VariantRules, name: String) = GameState(
        tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
        variantName = name, suits = listOf("Red", "Yellow"),
        suitInfos = listOf(live.hanab.client.game.SuitInfo("Red"), live.hanab.client.game.SuitInfo("Yellow")), rules = rules,
    )

    @Test fun rainbowOnesAreTouchedByEveryColour() {
        val g = twoSuits(live.hanab.client.game.VariantRules(specialRank = 1, rankTouchedByAllColors = true), "Rainbow-Ones (2 Suits)")
        assert(g.touchesColor(0, 1, 0) && g.touchesColor(0, 1, 1))      // a red 1 is touched by red and by yellow
        assert(g.touchesColor(0, 2, 0) && !g.touchesColor(0, 2, 1))     // other ranks as usual
    }

    @Test fun whiteOnesAreTouchedByNoColour() {
        val g = twoSuits(live.hanab.client.game.VariantRules(specialRank = 1, rankTouchedByNoColors = true), "White-Ones (2 Suits)")
        assert(!g.touchesColor(0, 1, 0) && !g.touchesColor(1, 1, 1))
        assert(g.touchesColor(0, 2, 0))
    }

    @Test fun scarceOnesHaveTwoCopiesAndCriticalRanksOne() {
        val scarce = twoSuits(live.hanab.client.game.VariantRules(scarceOnes = true), "Scarce Ones (2 Suits)")
        assertEquals(2, scarce.copies(0, 1))
        assertEquals(2, scarce.copies(0, 2))
        assertEquals(18, scarce.deckSize)                               // 2 suits of (2 + 2 + 2 + 2 + 1)
        val critical = twoSuits(live.hanab.client.game.VariantRules(criticalRank = 4), "Critical Four (2 Suits)")
        assertEquals(1, critical.copies(0, 4))
        assertEquals(3, critical.copies(0, 1))
        assertEquals(18, critical.deckSize)                             // 2 suits of (3 + 2 + 2 + 1 + 1)
        assert(critical.isCriticalIdentity(0, 4))
    }

    @Test fun clueStarvedCountsCluesAtTwiceTheirValue() {
        val g = twoSuits(live.hanab.client.game.VariantRules(clueStarved = true), "Clue Starved (2 Suits)")
        assertEquals(16, g.clues)
        assertEquals(16, g.maxClues)
        assertEquals("8", g.cluesShown)
        val after = g.apply(j("""{"type":"status","clues":15,"score":0,"maxScore":10}"""))
        assertEquals("7.5", after.cluesShown)
        assert(after.canClue)
        val one = g.apply(j("""{"type":"status","clues":1,"score":0,"maxScore":10}"""))
        assert(!one.canClue)                                            // half a clue is not enough to give one
        assert(g.copy(clues = 2).canClue)
    }

    @Test fun aPrismCardIsTouchedByTheColourThatGoesWithItsRank() {
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Yellow"),
            live.hanab.client.game.SuitInfo("Prism", colors = emptyList(), prism = true),
        )
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Prism (3 Suits)", suits = infos.map { it.name }, suitInfos = infos,
        )
        assertEquals(listOf("Red", "Yellow"), g.clueColors)             // Prism adds no colour of its own
        assert(g.touchesColor(2, 1, 0) && !g.touchesColor(2, 1, 1))     // a prism 1 is Red
        assert(g.touchesColor(2, 2, 1) && !g.touchesColor(2, 2, 0))     // a prism 2 is Yellow
        assert(g.touchesColor(2, 3, 0))                                 // a 3 wraps round to Red
        assert(g.touchesColor(2, 5, 0))
        assert(g.touchesColor(0, 1, 0) && !g.touchesColor(0, 2, 1))    // other suits as usual
    }

    @Test fun synesthesiaAddsTheColourOfTheRankToEveryCard() {
        val infos = listOf(live.hanab.client.game.SuitInfo("Red"), live.hanab.client.game.SuitInfo("Yellow"))
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Synesthesia (2 Suits)", suits = infos.map { it.name }, suitInfos = infos,
            rules = live.hanab.client.game.VariantRules(synesthesia = true, clueRanks = emptyList()),
        )
        // The colour for a rank: 1 red, 2 yellow, 3 red again, and so on.
        assert(g.touchesColor(0, 1, 0) && !g.touchesColor(0, 1, 1))     // red 1: its own red, and the 1's red only
        assert(g.touchesColor(0, 2, 0) && g.touchesColor(0, 2, 1))      // red 2: its own red and the 2's yellow
        assert(g.touchesColor(1, 1, 0) && g.touchesColor(1, 1, 1))      // yellow 1: its own yellow and the 1's red
        assert(g.touchesColor(1, 3, 0) && g.touchesColor(1, 3, 1))      // yellow 3: its own yellow and the 3's red
        assert(g.touchesColor(0, 3, 0) && !g.touchesColor(0, 3, 1))     // red 3: red twice over, never yellow
    }

    @Test fun blindVariantsTouchNothingButAllowTheClue() {
        val color = twoSuits(live.hanab.client.game.VariantRules(colorCluesTouchNothing = true), "Color Blind (2 Suits)")
        assert(!color.touchesColor(0, 1, 0) && color.touchesRank(0, 1, 1))
        assert(color.clueAllowed(isColor = true, touchesSomething = false))     // a colour clue may touch nothing
        assert(!color.clueAllowed(isColor = false, touchesSomething = false))   // a rank clue may not
        val number = twoSuits(live.hanab.client.game.VariantRules(rankCluesTouchNothing = true), "Number Blind (2 Suits)")
        assert(!number.touchesRank(0, 1, 1) && number.touchesColor(0, 1, 0))
        assert(number.clueAllowed(isColor = false, touchesSomething = false))
        assert(!number.clueAllowed(isColor = true, touchesSomething = false))
    }

    @Test fun alternatingCluesForbidTheSameKindTwiceInARow() {
        val g = twoSuits(live.hanab.client.game.VariantRules(alternatingClues = true), "Alternating Clues (2 Suits)")
        assert(g.clueAllowed(true, true) && g.clueAllowed(false, true))         // the first clue may be either
        val afterColor = g.copy(lastClueWasColor = true)
        assert(!afterColor.clueAllowed(true, true) && afterColor.clueAllowed(false, true))
        val afterRank = g.copy(lastClueWasColor = false)
        assert(afterRank.clueAllowed(true, true) && !afterRank.clueAllowed(false, true))
        // It follows the clues actually given, whoever gave them.
        val clue = j("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"target":0,"list":[],"turn":0}""")
        assertEquals(true, g.apply(clue).lastClueWasColor)
        // Without the rule nothing is forbidden.
        assert(twoSuits(live.hanab.client.game.VariantRules(), "x").copy(lastClueWasColor = true).clueAllowed(true, true))
    }

    @Test fun anOrangeCardIsPlayedByDiscardingIt() {
        val infos = listOf(live.hanab.client.game.SuitInfo("Red"), live.hanab.client.game.SuitInfo("Orange", inverted = true))
        val g = GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "Orange (2 Suits)", suits = infos.map { it.name }, suitInfos = infos,
        )
        assert(g.hasInverted && g.invertedSuit(1) && !g.invertedSuit(0))
        val orange1 = setOf(1 to 1)
        val orange3 = setOf(1 to 3)
        val red3 = setOf(0 to 3)
        // Discarding the Orange card that is next plays it: fine. Discarding another one would be a misplay.
        assertEquals(null, g.discardWarning(orange1))
        assert(g.discardWarning(orange3)!!.contains("plays it"))
        // Playing an Orange card discards it: the website never warns about unplayable ones, we warn about losing a good one.
        assertEquals(null, g.playWarning(orange3))
        assert(g.playWarning(orange1)!!.contains("discards it"))
        assert(g.playWarning(red3)!!.contains("cannot be played"))
        assertEquals(null, g.playWarning(setOf(0 to 1)))
        assert(g.discardWarning(setOf(0 to 1))!!.contains("played right now"))
        // The Orange card is not marked as playable, because the way to play it is to discard it.
        assert(!g.isPlayable(live.hanab.client.game.Card(order = 0, suit = 1, rank = 1, possible = orange1)))
    }

    @Test fun cowAndPigAndDuckClueTellTheCardsNothing() {
        val clue = j("""{"type":"clue","clue":{"type":0,"value":0},"giver":1,"target":0,"list":[0],"turn":0}""")
        val draws = (0..1).map { j("""{"type":"draw","playerIndex":0,"order":$it,"suitIndex":-1,"rank":-1}""") }
        val cow = twoSuits(live.hanab.client.game.VariantRules(cowAndPig = true), "Cow & Pig (2 Suits)").applyAll(draws).apply(clue)
        assertEquals("Moo", cow.lastClue!!.sound)
        assert(cow.cards.getValue(0).clued)                                      // the card is marked...
        assertEquals(10, cow.cards.getValue(0).possible.size)                    // ...but nothing is ruled out
        assert(cow.log.last().text.contains("moos"))
        val rank = j("""{"type":"clue","clue":{"type":1,"value":3},"giver":1,"target":0,"list":[0],"turn":0}""")
        assertEquals("Oink", cow.apply(rank).lastClue!!.sound)
        val duck = twoSuits(live.hanab.client.game.VariantRules(duck = true), "Duck (2 Suits)").applyAll(draws).apply(rank)
        assertEquals("Quack", duck.lastClue!!.sound)
        // A normal variant narrows the possibilities as before.
        val plain = twoSuits(live.hanab.client.game.VariantRules(), "x").applyAll(draws).apply(clue)
        assertEquals(null, plain.lastClue!!.sound)
        assertEquals(5, plain.cards.getValue(0).possible.size)               // only the red ones are left
    }

    @Test fun throwItInAHolePutsHiddenPlaysInTheHoleAndHidesTheScore() {
        val g = twoSuits(live.hanab.client.game.VariantRules(throwItInAHole = true), "Throw It in a Hole (2 Suits)")
            .applyAll((0..3).map { j("""{"type":"draw","playerIndex":${it % 2},"order":$it,"suitIndex":-1,"rank":-1}""") })
        assert(g.hidesScore)
        val played = g.apply(j("""{"type":"play","playerIndex":1,"order":1,"suitIndex":-1,"rank":-1}"""))
        assertEquals(listOf(1), played.hole)
        assertEquals(listOf(0, 0), played.stacks)
        val missed = played.apply(j("""{"type":"discard","playerIndex":0,"order":2,"suitIndex":-1,"rank":-1,"failed":true}"""))
        assertEquals(listOf(1, 2), missed.hole)
        assertEquals(emptyList<Int>(), missed.discards)
        // An ordinary discard still goes to the pile, and nothing warns about a play.
        val discarded = missed.apply(j("""{"type":"discard","playerIndex":1,"order":3,"suitIndex":-1,"rank":-1,"failed":false}"""))
        assertEquals(listOf(3), discarded.discards)
        assertEquals(null, g.playWarning(setOf(0 to 3)))
        // The score shows once the game is over.
        assert(!discarded.copy(phase = live.hanab.client.game.Phase.Over).hidesScore)
    }

    private fun directional(upOrDown: Boolean, reversedSuit: Boolean): GameState {
        val infos = listOf(
            live.hanab.client.game.SuitInfo("Red"),
            live.hanab.client.game.SuitInfo("Yellow Reversed", colors = listOf("Yellow"), reversed = reversedSuit, base = "Yellow"),
        )
        return GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "x", suits = infos.map { it.name }, suitInfos = infos,
            rules = live.hanab.client.game.VariantRules(upOrDown = upOrDown),
        )
    }

    private fun play(g: GameState, suit: Int, rank: Int, order: Int) = g
        .apply(j("""{"type":"draw","playerIndex":0,"order":$order,"suitIndex":$suit,"rank":$rank}"""))
        .apply(j("""{"type":"play","playerIndex":0,"order":$order,"suitIndex":$suit,"rank":$rank}"""))

    @Test fun aReversedSuitIsPlayedFromFiveDown() {
        var g = directional(upOrDown = false, reversedSuit = true)
        assertEquals(listOf(1), g.nextRanks(0))                          // a normal suit starts at 1
        assertEquals(listOf(5), g.nextRanks(1))                          // the reversed one at 5
        g = play(g, 1, 5, 0)
        assertEquals(listOf(4), g.nextRanks(1))
        assertEquals(5, g.stackTop(1))
        g = play(g, 1, 4, 1)
        assertEquals(listOf(3), g.nextRanks(1))
        assertEquals(2, g.stacks[1])                                     // two cards on the stack
        assert(g.isDeadIdentity(1, 5) && g.isDeadIdentity(1, 4) && !g.isDeadIdentity(1, 3))
        // Losing every copy of a 3 (there are two) kills the 2 and the 1 behind it.
        val lost = g.copy(discards = listOf(10, 11), cards = g.cards +
            mapOf(10 to Card(10, 1, 3, setOf(1 to 3)), 11 to Card(11, 1, 3, setOf(1 to 3))))
        assert(lost.isDeadIdentity(1, 2) && lost.isDeadIdentity(1, 1))
        // Copies: one 1, two each of 2 to 4 and three 5s.
        val info = live.hanab.client.game.SuitInfo("Reversed", reversed = true)
        val rules = live.hanab.client.game.VariantRules()
        assertEquals(listOf(1, 2, 2, 2, 3), (1..5).map { GameState.copiesOf(info, it, rules) })
        assertEquals(10, (1..5).sumOf { GameState.copiesOf(info, it, rules) })
    }

    @Test fun anUpOrDownStackRunsEitherWayFromItsFirstCards() {
        var g = directional(upOrDown = true, reversedSuit = false)
        assertEquals(listOf(1, 5, START_RANK).sorted(), g.nextRanks(0).sorted())
        g = play(g, 0, 1, 0)
        assertEquals(listOf(2), g.nextRanks(0))                          // started with a 1: it goes up
        assert(g.isDeadIdentity(0, 1) && !g.isDeadIdentity(0, 5))
        g = play(g, 0, 2, 1)
        assertEquals(listOf(3), g.nextRanks(0))
        // A START card allows a 2 (up) or a 4 (down).
        var s = play(directional(true, false), 0, START_RANK, 0)
        assertEquals(listOf(2, 4), s.nextRanks(0).sorted())
        s = play(s, 0, 4, 1)
        assertEquals(listOf(3), s.nextRanks(0))                          // now it runs down
        assertEquals(4, s.stackTop(0))
        // Starting at 5 runs down.
        assertEquals(listOf(4), play(directional(true, false), 0, 5, 0).nextRanks(0))
        // A full stack accepts nothing.
        var done = directional(true, false)
        listOf(5, 4, 3, 2, 1).forEachIndexed { i, r -> done = play(done, 0, r, i) }
        assertEquals(emptyList<Int>(), done.nextRanks(0))
        // The deck: one 1, two of 2 to 4, one 5 and one START per suit.
        val info = live.hanab.client.game.SuitInfo("Red")
        val rules = live.hanab.client.game.VariantRules(upOrDown = true)
        assertEquals(listOf(1, 2, 2, 2, 1, 1), (listOf(1, 2, 3, 4, 5, START_RANK)).map { GameState.copiesOf(info, it, rules) })
        assertEquals(9 * 2, GameState.deckCards(listOf(info, info), rules))
        assertEquals(listOf(1, 2, 3, 4, 5, START_RANK), g.ranks)
    }

    private fun sudoku(suits: Int, stackSize: Int): GameState {
        val infos = (0 until suits).map { live.hanab.client.game.SuitInfo("S$it") }
        return GameState(
            tableID = 1, playerNames = listOf("me", "bob"), ourIndex = 0, spectating = false,
            variantName = "x", suits = infos.map { it.name }, suitInfos = infos,
            rules = live.hanab.client.game.VariantRules(sudoku = true, stackSize = stackSize),
        )
    }

    @Test fun aSudokuStackStartsAnywhereAndWrapsRound() {
        var g = sudoku(suits = 5, stackSize = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), g.nextRanks(0))              // any rank may start a stack
        g = play(g, 0, 3, 0)
        assertEquals(listOf(4), g.nextRanks(0))
        assertEquals(listOf(1, 2, 4, 5), g.nextRanks(1))                 // another stack may not start on a 3
        g = play(g, 0, 4, 1); g = play(g, 0, 5, 2)
        assertEquals(listOf(1), g.nextRanks(0))                          // wraps round from 5 to 1
        g = play(g, 0, 1, 3); g = play(g, 0, 2, 4)
        assertEquals(emptyList<Int>(), g.nextRanks(0))                   // five cards: finished
        assertEquals(5, g.stacks[0])
        // The last stack to start has only the one rank left.
        var last = sudoku(suits = 5, stackSize = 5)
        listOf(1, 2, 3, 4).forEachIndexed { i, r -> last = play(last, i, r, i) }
        assertEquals(listOf(5), last.nextRanks(4))
        // Two copies of every card, so ten a suit.
        assertEquals(10, g.copies(0, 1) * 5)
        assertEquals(50, g.totalCards)
    }

    @Test fun fourSuitSudokuHasNoFivesAndStacksOfFour() {
        val g = sudoku(suits = 4, stackSize = 4)
        assertEquals(listOf(1, 2, 3, 4), g.ranks)
        assertEquals(16, g.perfectScore)
        assertEquals(32, g.deckSize)                                     // 4 suits of 4 ranks, two copies of each
        val p = play(g, 0, 3, 0)
        assertEquals(listOf(4), p.nextRanks(0))
        val wrapped = play(play(p, 0, 4, 1), 0, 1, 2)
        assertEquals(listOf(2), wrapped.nextRanks(0))                    // 3, 4, 1, then 2 (never a 5)
        assertEquals(listOf(1, 2, 4), p.nextRanks(1))
    }

    @Test fun colorMuteOffersNoColourClues() {
        val g = twoSuits(live.hanab.client.game.VariantRules(clueColorsOverride = emptyList()), "Color Mute (2 Suits)")
        assertEquals(emptyList<String>(), g.clueColors)
    }
}

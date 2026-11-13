package live.hanab.client.game

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What we know about one card. suit/rank are -1 while unknown (e.g. cards in our own hand). */
data class Card(
    val order: Int,
    val suit: Int = -1,
    val rank: Int = -1,
    /** (suit, rank) identities still possible given the clues this card has received. */
    val possible: Set<Pair<Int, Int>>,
    val clued: Boolean = false,
    /** Some clue (touching it or not) has been given to the hand this card is in; it helps deduction. */
    val clueApplied: Boolean = false,
) {
    val possibleSuits: Set<Int> get() = possible.mapTo(HashSet()) { it.first }
    val possibleRanks: Set<Int> get() = possible.mapTo(HashSet()) { it.second }
}

data class LogLine(val text: String)

/** The special options of this game that change how it is played. */
data class GameOptions(
    val emptyClues: Boolean = false,
    val cardCycle: Boolean = false,
    val deckPlays: Boolean = false,
    val allOrNothing: Boolean = false,
    val detrimentalCharacters: Boolean = false,
    val speedrun: Boolean = false,
    /** A timed game: the times at the end are what each player has left, not what they used. */
    val timed: Boolean = false,
)

/** One person's note on a card. Notes are per person: players and spectators each keep their own. */
/** The rank of the START card of Up or Down variants. */
const val START_RANK = 7

/** How a card's rank is written: its number, or S for the START card. */
fun cardRankText(rank: Int): String = if (rank == START_RANK) "S" else "$rank"

data class NoteEntry(val name: String, val text: String, val isSpectator: Boolean)

/** The most recent clue: which cards it touched and what was said. Shown as a bubble for one turn. */
data class ClueMark(
    val orders: Set<Int>,
    /** Colour name for a colour clue, else null. */
    val color: String?,
    /** Rank for a rank clue, else null. */
    val rank: Int?,
    /** What to show for a rank clue: the rank, or "Odds" / "Evens". */
    val rankLabel: String? = null,
    val giver: Int,
    val target: Int,
    /** Turn number when the clue was given; the bubble lasts through the next turn. */
    val turn: Int,
    /** Cow & Pig / Duck: "Moo", "Oink" or "Quack" is shown instead of the colour or rank. */
    val sound: String? = null,
)

/** A card that was just drawn live; the UI flies it in from the deck. */
data class DrawEvent(val id: Long, val player: Int, val order: Int)

/** A play or discard that just happened live; the UI animates it and then consumes it. */
data class MoveEvent(
    val id: Long,
    val play: Boolean,
    val player: Int,
    val order: Int,
    val suit: Int,
    val rank: Int,
    val failed: Boolean,
)

/** Possible (suit, rank) identities for one card. [inferred] is always a subset of [direct]. */
data class Knowledge(val direct: Set<Pair<Int, Int>>, val inferred: Set<Pair<Int, Int>>) {
    val directSuits get() = direct.map { it.first }.toSortedSet()
    val directRanks get() = direct.map { it.second }.toSortedSet()
    val inferredSuits get() = inferred.map { it.first }.toSortedSet()
    val inferredRanks get() = inferred.map { it.second }.toSortedSet()

    /** True when only deduction (not a clue) pins the identity down. */
    val solvedByInference get() = inferred.size == 1 && direct.size > 1
}

enum class Phase { Playing, Over }

/**
 * Immutable snapshot of a game, rebuilt by feeding it the server's `gameAction` stream.
 * Supports plain-suit variants (every suit has exactly one colour and rank clues touch only that rank).
 */
data class GameState(
    val tableID: Int,
    val playerNames: List<String>,
    val ourIndex: Int,
    val spectating: Boolean,
    val variantName: String,
    val suits: List<String>,
    /** Clue behaviour per suit; defaults to plain suits (touched by their own colour and by ranks). */
    val suitInfos: List<SuitInfo> = suits.map { SuitInfo(it) },
    /** The variant's own rules (special ranks, copy counts, clue starved). */
    val rules: VariantRules = VariantRules(),
    val cards: Map<Int, Card> = emptyMap(),
    /** Card orders per player, newest card first. */
    val hands: List<List<Int>> = playerNames.map { emptyList() },
    /** Highest rank played per suit (0 = nothing played). */
    val stacks: List<Int> = suits.map { 0 },
    /** The ranks played on each stack, in order (the stacks of Reversed and Up or Down variants run both ways). */
    val stackRanks: List<List<Int>> = suits.map { emptyList() },
    val discards: List<Int> = emptyList(),
    /** Clues as the server counts them: at twice their value in a Clue Starved variant (see [clueCost]). */
    val clues: Int = if (rules.clueStarved) 16 else 8,
    val strikes: Int = 0,
    val score: Int = 0,
    val maxScore: Int = suits.size * rules.stackSize,
    val turn: Int = 0,
    val currentPlayer: Int = 0,
    val cardsDrawn: Int = 0,
    val deckSize: Int = deckCards(suitInfos, rules),
    val phase: Phase = Phase.Playing,
    val log: List<LogLine> = emptyList(),
    /** Our private notes, keyed by card order. */
    val notes: Map<Int, String> = emptyMap(),
    /** Every action applied so far, in order; lets us rebuild any earlier position. */
    val actions: List<JsonObject> = emptyList(),
    /** True for a rebuilt past position: no moves may be made from it. */
    val readOnly: Boolean = false,
    val options: GameOptions = GameOptions(),
    /** Each player's detrimental character (null = none), by player index. */
    val characters: List<CharacterInfo?> = emptyList(),
    val lastClue: ClueMark? = null,
    /** Whether the last clue given in the game was a colour clue (null = none yet); Alternating Clues needs it. */
    val lastClueWasColor: Boolean? = null,
    /** Throw It in a Hole: the cards played or misplayed face down (their orders), which are on no stack and in no discard pile. */
    val hole: List<Int> = emptyList(),
    /** Everyone's notes by card order, as the server sends them to spectators and in replays. */
    val allNotes: Map<Int, List<NoteEntry>> = emptyMap(),
    /** True identities of every card (index = card order), sent once the game is a replay. */
    val identities: List<Pair<Int, Int>> = emptyList(),
    /** After the game ends every card, including our own, is visible. */
    val revealAll: Boolean = false,
    /** EndCondition value from the server (1 = normal, 2 = strikeout, 4/10 = terminated, ...). */
    val endCondition: Int? = null,
    /** Each player's time at the end of the game, in milliseconds (the server sends it after the last turn). */
    val playerTimes: List<Long> = emptyList(),
    /** How long the whole game took, in milliseconds. */
    val durationMs: Long = 0L,
) {
    init {
        // The symbols of an Ambiguous suit depend on the variant it is played in (see Variants.pipsOf).
        Variants.useVariant(variantName)
    }

    val numPlayers get() = playerNames.size

    /**
     * A time from the server as the website writes it. In a timed game it is what the player has left; in an untimed
     * one the server counts down from 0 into the negatives, so what the player took is the same number without the sign.
     */
    fun clockOf(ms: Long): String = formatClock(if (options.timed) ms else -ms)

    /** Player [p]'s time at the end of the game, once the server has sent it. */
    fun timeOf(p: Int): String? = playerTimes.getOrNull(p)?.let { clockOf(it) }

    /** The other players in the order they will act after us; the last one acts just before us. */
    val othersInTurnOrder: List<Int> get() = (1 until numPlayers).map { (ourIndex + it) % numPlayers }

    /** Number of cards in the whole deck (the last one, by order, is what a bottom-deck blind play plays). */
    val totalCards: Int get() = deckCards(suitInfos, rules)

    /** The most clues you can hold, in the server's counting (see [clues]). */
    val maxClues: Int get() = if (rules.clueStarved) 16 else 8

    /** What giving a clue costs, in the server's counting. */
    val clueCost: Int get() = if (rules.clueStarved) 2 else 1

    /** Whether a clue can be given now. */
    val canClue: Boolean get() = clues >= clueCost

    /** The clues to show: half clues count in Clue Starved variants (7.5). */
    val cluesShown: String get() = if (rules.clueStarved) (clues / 2).toString() + if (clues % 2 == 1) ".5" else "" else "$clues"

    /** With the "bottom-deck blind play" option, the last card may be played blind on your turn. */
    val deckPlayable: Boolean get() = options.deckPlays && deckSize == 1 && ourTurn

    /** The best score the variant allows: 5 per suit. (The server's `maxScore` shrinks as critical cards are lost.) */
    val perfectScore: Int get() = suits.size * rules.stackSize

    /**
     * What to print after the "/": while playing, the best score still reachable (it drops when a
     * critical card is discarded, which is useful to see); once the game is over, the true perfect score.
     */
    val scoreDenominator: Int get() = if (phase == Phase.Over) perfectScore else maxScore

    /** Any ending other than a normal finish (strikeout, timeout, terminated, ...) scores zero. */
    val finalScore: Int get() = if (phase == Phase.Over && (endCondition ?: 1) > 1) 0 else score

    /** Short human text for how the game ended. */
    val endText: String get() = when (endCondition) {
        1 -> if (score == perfectScore) "Perfect score" else "Out of cards"
        2 -> "Three strikes"
        3 -> "Time ran out"
        4 -> "Terminated by a player"
        5 -> "Speedrun failed"
        7 -> "Character softlock"
        8 -> "All or nothing failed"
        9 -> "All or nothing softlock"
        6 -> "Idle timeout"
        10 -> "Terminated by vote"
        null -> "Game over"
        else -> "Game over"
    }

    /** Colours offered as colour clues, in the order the server numbers them (Rainbow adds none). */
    val clueColors: List<String> by lazy {
        rules.clueColorsOverride ?: suitInfos.filter { !it.allColors }.flatMap { it.colors }.distinct()
    }

    /**
     * May this kind of clue be given? Alternating Clues forbids the kind given last. [touchesSomething]: the clue
     * touches a card; a clue that touches nothing is still allowed with the empty-clues option and for the kind of
     * clue that touches nothing in Color Blind / Number Blind.
     */
    fun clueAllowed(isColor: Boolean, touchesSomething: Boolean): Boolean =
        (!rules.alternatingClues || lastClueWasColor != isColor) &&
            (touchesSomething || options.emptyClues ||
                (if (isColor) rules.colorCluesTouchNothing else rules.rankCluesTouchNothing))

    private val allIdentities: Set<Pair<Int, Int>> by lazy {
        suits.indices.flatMap { s -> ranks.map { r -> s to r } }.toSet()
    }

    /** The ranks a card can have: 1 to 5, and the START card in Up or Down. */
    val ranks: List<Int> get() = ranksOf(rules)

    /** Whether any stack runs from 5 down, or either way (Reversed suits, Up or Down). */
    val directional: Boolean by lazy { rules.upOrDown || rules.sudoku || suitInfos.any { it.reversed } }

    /** The orders of rank the stack of suit [s] can still be played in (every full run that agrees with what is on it). */
    private fun runs(s: Int): List<List<Int>> {
        val played = stackRanks.getOrElse(s) { emptyList() }
        val all = when {
            rules.sudoku -> sudokuRuns(s, played)
            rules.upOrDown -> listOf(
                listOf(1, 2, 3, 4, 5), listOf(5, 4, 3, 2, 1), listOf(START_RANK, 2, 3, 4, 5), listOf(START_RANK, 4, 3, 2, 1),
            )
            suitInfos.getOrNull(s)?.reversed == true -> listOf(listOf(5, 4, 3, 2, 1))
            else -> listOf(listOf(1, 2, 3, 4, 5))
        }
        return all.filter { it.take(played.size) == played }
    }

    /**
     * Sudoku: a started stack runs on from where it began, wrapping round from the top rank to 1; a stack not started yet
     * may begin on any rank that no other stack began on.
     */
    private fun sudokuRuns(s: Int, played: List<Int>): List<List<Int>> {
        val n = rules.stackSize
        fun run(start: Int) = (0 until n).map { (start - 1 + it) % n + 1 }
        val first = played.firstOrNull()
        if (first != null) return listOf(run(first))
        val taken = stackRanks.withIndex().filter { it.index != s }.mapNotNull { it.value.firstOrNull() }.toSet()
        return ranks.filter { it !in taken }.map(::run)
    }

    /** The ranks that can be played on suit [s] right now: the next one up, or down, or any way a new stack can start. */
    fun nextRanks(s: Int): List<Int> {
        if (s !in stacks.indices) return emptyList()
        if (!directional) return listOf(stacks[s] + 1)
        val played = stackRanks[s].size
        return runs(s).mapNotNull { it.getOrNull(played) }.distinct()
    }

    /** The rank on top of suit [s]'s stack (0 = empty). */
    fun stackTop(s: Int): Int = if (directional) stackRanks.getOrNull(s)?.lastOrNull() ?: 0 else stacks.getOrElse(s) { 0 }

    /** The rank on top of each stack, as shown. */
    val stackTops: List<Int> get() = stacks.indices.map(::stackTop)

    /** Whether a card of this suit and rank is already on the stack. */
    private fun onStack(suit: Int, rank: Int): Boolean =
        if (directional) rank in stackRanks[suit] else stacks[suit] >= rank

    /**
     * Does a colour clue (index into [clueColors]) touch a card of this suit and rank? The same order of rules as the
     * website's isCardTouchedByClueColor.
     */
    fun touchesColor(suit: Int, rank: Int, colorIdx: Int): Boolean {
        if (rules.colorCluesTouchNothing) return false
        val info = suitInfos.getOrNull(suit) ?: return false
        if (info.allColors) return true
        if (info.noColors) return false
        // Synesthesia: also touched by the colour that goes with the rank.
        if (rules.synesthesia && !info.noRanks && colorIdx == prismIndex(rank)) return true
        // Rainbow-Ones and White-Ones: the special rank is touched by every colour, or by none.
        if (rank == rules.specialRank) {
            if (rules.rankTouchedByAllColors) return true
            if (rules.rankTouchedByNoColors) return false
        }
        // Prism: touched by the colour that goes with the rank.
        if (info.prism) return colorIdx == prismIndex(rank)
        return clueColors.getOrNull(colorIdx) in info.colors
    }

    /** The clue colour that goes with a rank in Prism and Synesthesia: the first colour for a 1, then in turn and round. */
    fun prismIndex(rank: Int): Int =
        if (clueColors.isEmpty()) -1 else if (rank == START_RANK) clueColors.size - 1 else (rank - 1) % clueColors.size

    /**
     * Does a rank clue ([clueRank] is the value sent: the rank, or 1 / 2 for odds / evens) touch a card of this suit
     * and rank? The same order of rules as the website's isCardTouchedByClueRank.
     */
    fun touchesRank(suit: Int, rank: Int, clueRank: Int): Boolean {
        if (rules.rankCluesTouchNothing) return false
        val info = suitInfos.getOrNull(suit) ?: return false
        if (info.allRanks) return true
        if (info.noRanks) return false
        if (rules.funnels) return rank <= clueRank
        if (rules.chimneys) return rank >= clueRank
        if (rules.oddsAndEvens) return if (clueRank == 1) rank in listOf(1, 3, 5) else rank in listOf(2, 4)
        if (rank == rules.specialRank) {
            if (rules.rankTouchedByAllRanks) return true
            if (rules.rankTouchedByNoRanks) return false
            // Deceptive: the rank that touches the card depends on its suit.
            if (rules.rankDeceptive) return clueRank == rules.clueRanks.getOrNull(suit % rules.clueRanks.size)
        }
        return rank == clueRank
    }

    /** What a clue is announced as in Cow & Pig ("Moo" for a colour, "Oink" for a rank) and Duck ("Quack"), else null. */
    fun clueSound(isColor: Boolean): String? = when {
        rules.cowAndPig -> if (isColor) "Moo" else "Oink"
        rules.duck -> "Quack"
        else -> null
    }

    /** How a rank clue is called: its rank, or "Odds" / "Evens". */
    fun rankClueLabel(clueRank: Int): String =
        if (rules.oddsAndEvens) (if (clueRank == 1) "Odds" else "Evens") else "$clueRank"

    val ourTurn get() = !readOnly && !spectating && phase == Phase.Playing && currentPlayer == ourIndex
    val handSize get() = if (numPlayers <= 3) 5 else 4

    fun apply(a: JsonObject): GameState = applyOne(a).let { next ->
        if (next === this) this else next.copy(actions = actions + a)
    }

    /** The hand after card cycling: its oldest unclued card (the chop) moves to the newest slot (the front). */
    private fun cycleChop(hand: List<Int>, cards: Map<Int, Card>): List<Int> {
        val chop = hand.indexOfLast { cards[it]?.clued != true }
        // -1: every card is clued; 0: the chop already is the newest card. Either way nothing moves.
        if (chop <= 0) return hand
        return listOf(hand[chop]) + hand.filterIndexed { i, _ -> i != chop }
    }

    /**
     * Positions worth stopping at when stepping back, as a number of actions to replay.
     *  - the starting position: the initial deal, before any clue, play or discard. The server sends no
     *    "turn" action for it, so it is found as the point just before the first move;
     *  - then the position right after each "turn" action (the server sends one after every move).
     * Stop number k is therefore the game after k moves, which is also the shared replay's segment k.
     */
    val turnStops: List<Int> by lazy {
        val firstMove = actions.indexOfFirst {
            val type = it["type"]?.jsonPrimitive?.content
            type == "clue" || type == "play" || type == "discard"
        }
        val afterTurns = actions.mapIndexedNotNull { i, a -> if (a["type"]?.jsonPrimitive?.content == "turn") i + 1 else null }
        ((if (firstMove >= 0) listOf(firstMove) else emptyList()) + afterTurns).distinct()
    }

    /**
     * The plays and discards between stop [from] and the next stop [to] (numbers as in [turnStops]), as
     * animation events (id 0; the caller numbers them). Only a single step forward has a move to show:
     * anything else (a jump, or going back) returns nothing, so those positions just appear.
     */
    fun movesBetweenStops(from: Int, to: Int): List<MoveEvent> {
        val stops = turnStops
        if (from !in stops.indices || to != from + 1 || to !in stops.indices) return emptyList()
        return actions.subList(stops[from], stops[to]).mapNotNull { a ->
            val type = a["type"]?.jsonPrimitive?.content
            if (type != "play" && type != "discard") return@mapNotNull null
            fun n(key: String) = a[key]?.jsonPrimitive?.intOrNull ?: -1
            MoveEvent(
                id = 0, play = type == "play", player = n("playerIndex"), order = n("order"),
                suit = n("suitIndex"), rank = n("rank"), failed = a["failed"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }

    /** The game as it was after the first [k] actions (read-only). */
    fun replayTo(k: Int): GameState {
        val blank = GameState(
            tableID, playerNames, ourIndex, spectating, variantName, suits,
            suitInfos = suitInfos, rules = rules, options = options, characters = characters,
        )
        val past = blank.applyAll(actions.take(k)).copy(actions = actions, notes = notes, allNotes = allNotes, readOnly = true)
        return if (revealAll) past.withIdentities(identities) else past
    }

    /** Reveal all cards using the server's list of identities (sent when a game becomes a replay). */
    fun withIdentities(list: List<Pair<Int, Int>>): GameState = copy(
        identities = list,
        revealAll = true,
        cards = cards.mapValues { (o, c) ->
            val id = list.getOrNull(o)
            if (c.suit < 0 && id != null && id.first >= 0) c.copy(suit = id.first, rank = id.second) else c
        },
    )

    /** Copies of (suit, rank) not yet played or discarded. */
    fun remaining(suit: Int, rank: Int): Int =
        copies(suit, rank) - (if (onStack(suit, rank)) 1 else 0) - (discardCounts[suit to rank] ?: 0)

    /** Of those, how many are not visible to us in anyone's hand (so could be in the deck or our hand). */
    fun unseen(suit: Int, rank: Int): Int {
        val inHands = hands.flatten().mapNotNull { cards[it] }.count { it.suit == suit && it.rank == rank }
        return remaining(suit, rank) - inHands
    }

    private fun applyOne(a: JsonObject): GameState {
        fun int(key: String) = a[key]?.jsonPrimitive?.intOrNull ?: -1
        return when (a["type"]?.jsonPrimitive?.content) {
            "draw" -> {
                val p = int("playerIndex"); val o = int("order")
                if (cards.containsKey(o)) return this // already applied (duplicate delivery)
                val card = Card(o, int("suitIndex"), int("rank"), possible = allIdentities)
                copy(
                    cards = cards + (o to card),
                    hands = hands.mapIndexed { i, h -> if (i == p) listOf(o) + h else h },
                    deckSize = deckSize - 1,
                )
            }
            "play" -> {
                val p = int("playerIndex"); val o = int("order"); val s = int("suitIndex"); val r = int("rank")
                val newRanks = if (s in stacks.indices && r > 0) stackRanks.mapIndexed { i, v -> if (i == s) v + r else v } else stackRanks
                val newStacks = if (s !in stacks.indices) stacks
                else stacks.mapIndexed { i, v -> if (i != s) v else if (directional) newRanks[s].size else r }
                // Throw It in a Hole: the server hides what was played, and the card goes in the hole, not on a stack.
                val hidden = rules.throwItInAHole && s < 0
                // Completing a stack gives a clue back (the new server sends no status; the old one did, and sets these too).
                val completed = s in newStacks.indices && newStacks[s] >= rules.stackSize && stacks.getOrElse(s) { 0 } < rules.stackSize
                removeFromHand(p, o).copy(
                    cards = cards.reveal(o, s, r),
                    stacks = newStacks,
                    stackRanks = newRanks,
                    hole = if (hidden) hole + o else hole,
                    clues = if (completed) minOf(maxClues, clues + 1) else clues,
                    score = newStacks.sum(),
                ).withReachableScore().logged("${name(p)} played ${cardName(s, r)}")
            }
            "discard" -> {
                val p = int("playerIndex"); val o = int("order"); val s = int("suitIndex"); val r = int("rank")
                val failed = a["failed"]?.jsonPrimitive?.booleanOrNull ?: false
                // An unknown misplay counts as a play in Throw It in a Hole.
                val hidden = rules.throwItInAHole && failed && s < 0
                // A misplay is a failed discard: that is a strike. Any other discard gives a clue back.
                removeFromHand(p, o).copy(
                    cards = cards.reveal(o, s, r),
                    discards = if (hidden) discards else discards + o,
                    hole = if (hidden) hole + o else hole,
                    strikes = if (failed) strikes + 1 else strikes,
                    clues = if (failed) clues else minOf(maxClues, clues + 1),
                ).withReachableScore().logged("${name(p)} ${if (hidden) "played" else if (failed) "misplayed" else "discarded"} ${cardName(s, r)}")
            }
            "clue" -> {
                val clue = a["clue"]!!.jsonObject
                val isColor = clue["type"]!!.jsonPrimitive.int == 0
                val value = clue["value"]!!.jsonPrimitive.int
                // Go encodes an empty list as null.
                val touched = (a["list"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet()
                val target = int("target"); val giver = int("giver")
                val newCards = cards.mapValues { (o, c) ->
                    if (o !in hands.getOrElse(target) { emptyList() }) c
                    else {
                        val hit = o in touched
                        // Keep identities that agree with whether this card was touched (not when the clue is only said
                        // aloud, as in Cow & Pig and Duck).
                        val kept = if (rules.cowAndPig || rules.duck) c.possible else c.possible.filter { (s, r) ->
                            (if (isColor) touchesColor(s, r, value) else touchesRank(s, r, value)) == hit
                        }.toSet()
                        c.copy(possible = kept.ifEmpty { c.possible }, clued = c.clued || hit, clueApplied = true)
                    }
                }
                val what = if (isColor) clueColors.getOrElse(value) { "?" } else rankClueLabel(value)
                val sound = clueSound(isColor)
                copy(
                    cards = newCards,
                    clues = maxOf(0, clues - clueCost),
                    lastClueWasColor = isColor,
                    // Card cycling: the giver's oldest unclued card moves to their newest slot.
                    hands = if (options.cardCycle) hands.mapIndexed { i, h -> if (i == giver) cycleChop(h, newCards) else h } else hands,
                    lastClue = ClueMark(
                        orders = touched,
                        color = if (isColor) clueColors.getOrNull(value) else null,
                        rank = if (isColor) null else value,
                        rankLabel = if (isColor) null else rankClueLabel(value),
                        giver = giver, target = target, turn = turn, sound = sound,
                    ),
                ).logged(
                    if (sound != null) "${name(giver)} ${sound.lowercase()}s at ${name(target)} (${touched.size} card${if (touched.size == 1) "" else "s"})"
                    else "${name(giver)} told ${name(target)} $what (${touched.size} card${if (touched.size == 1) "" else "s"})",
                )
            }
            "status" -> copy(clues = int("clues"), score = int("score"), maxScore = int("maxScore"))
            // The old server announced a strike before the failed discard; the discard counts it now.
            "strike" -> this
            // A card whose identity the server tells us (the new server sends this for the deck when a game ends).
            "cardIdentity" -> copy(cards = cards.reveal(int("order"), int("suitIndex"), int("rank")))
            "turn" -> copy(turn = int("num"), currentPlayer = int("currentPlayerIndex"))
            "gameOver" -> copy(phase = Phase.Over, endCondition = int("endCondition")).logged("Game over")
            // After the last turn: what each player used (or, in a timed game, had left). Seen one step on in a replay.
            "playerTimes" -> copy(
                playerTimes = (a["playerTimes"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.content.toLongOrNull() ?: 0L }.orEmpty(),
                durationMs = a["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
            else -> this
        }
    }

    /**
     * The best score still reachable: for each stack, how far it can still go before a rank with no copy left (not on the
     * stack and not in the discard pile) stands in the way. The old server sent this as `maxScore`; the new one does not.
     */
    private fun withReachableScore(): GameState {
        var total = 0
        for (s in stacks.indices) {
            val lines = if (directional) runs(s) else listOf((1..rules.stackSize).toList())
            val played = if (directional) stackRanks.getOrElse(s) { emptyList() }.size else stacks[s]
            total += lines.maxOfOrNull { line ->
                var reach = played
                while (reach < line.size && remaining(s, line[reach]) > 0) reach++
                reach
            } ?: played
        }
        return copy(maxScore = total)
    }

    /** Never throws: a malformed action is shown in the log instead of killing the update stream. */
    fun applySafe(a: JsonObject): GameState =
        try { apply(a) } catch (e: Exception) { logged("[error] ${a["type"]}: ${e.message}") }

    fun applyAll(actions: List<JsonObject>) = actions.fold(this) { s, a -> s.applySafe(a) }

    private fun removeFromHand(p: Int, o: Int) =
        copy(hands = hands.mapIndexed { i, h -> if (i == p) h - o else h })

    private fun Map<Int, Card>.reveal(o: Int, s: Int, r: Int): Map<Int, Card> {
        val c = this[o] ?: return this
        return this + (o to c.copy(suit = s, rank = r))
    }

    private fun logged(t: String) = copy(log = (log + LogLine(t)).takeLast(60))
    fun name(p: Int) = playerNames.getOrElse(p) { "?" }
    fun cardName(s: Int, r: Int) = if (s < 0 || r < 0) "a card" else "${suits.getOrElse(s) { "?" }} ${if (r == START_RANK) "START" else r}"

    /** Playable-now check used for hints in the UI (an Orange card is not marked: it goes on its stack by being discarded). */
    fun isPlayable(c: Card) = c.suit in stacks.indices && !invertedSuit(c.suit) && c.rank in nextRanks(c.suit)

    /** Orange: the server discards a card of this suit when it is played and plays it when it is discarded. */
    fun invertedSuit(suit: Int) = suitInfos.getOrNull(suit)?.inverted == true

    /** Whether any suit swaps plays and discards: then discarding is allowed even with every clue token. */
    val hasInverted: Boolean get() = suitInfos.any { it.inverted }

    /** Throw It in a Hole hides the score and the strikes from the players until the game is over. */
    val hidesScore: Boolean get() = rules.throwItInAHole && phase == Phase.Playing && !spectating && !readOnly

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** What to ask before discarding a card that could be any of [ids], or null if it is fine. Discarding an Orange card plays it. */
    fun discardWarning(ids: Set<Pair<Int, Int>>): String? {
        if (ids.isEmpty() || rules.throwItInAHole) return null
        if (ids.all { invertedSuit(it.first) }) {
            return if (ids.any { (s, r) -> r in nextRanks(s) }) null
            else cap("${describe(ids)} is an Orange card, so discarding it plays it, and it cannot be played right now. Discard it anyway?")
        }
        if (ids.any { invertedSuit(it.first) }) return null
        return when {
            allCritical(ids) -> cap("${describe(ids)} is the last copy of a card you still need. Discard it anyway?")
            allPlayableNow(ids) -> cap("${describe(ids)} can be played right now. Discard it anyway?")
            else -> null
        }
    }

    /** What to ask before playing a card that could be any of [ids], or null if it is fine. Playing an Orange card discards it. */
    fun playWarning(ids: Set<Pair<Int, Int>>): String? {
        if (ids.isEmpty() || rules.throwItInAHole) return null
        if (ids.all { invertedSuit(it.first) }) return when {
            allCritical(ids) -> cap("${describe(ids)} is the last copy of a card you still need, and playing an Orange card discards it. Play it anyway?")
            allPlayableNow(ids) -> cap("${describe(ids)} can go on its stack, but playing an Orange card discards it. Play it anyway?")
            else -> null
        }
        // An Orange card counts as playable: the website never warns about it.
        if (ids.any { (s, r) -> invertedSuit(s) || (s in stacks.indices && r in nextRanks(s)) }) return null
        return cap("${describe(ids)} cannot be played right now. Play it anyway?")
    }

    /** Every deduction for this position, computed once on first use (see [Deduction]). */
    private val deduced: Map<Int, Set<Pair<Int, Int>>> by lazy {
        val d = Deduction(this)
        d.run().mapValues { (_, ids) -> ids.mapTo(HashSet()) { d.toPair(it) } }
    }

    /**
     * What player [p] can work out about each card in their hand, from where we sit.
     *  - direct: identities not ruled out by the clues that touched / missed the card.
     *  - inferred: what is left after also checking that the other cards they can use could actually
     *    hold the remaining copies, the way the website does (see [Deduction]).
     * For our own hand this is what we can deduce; for a partner, what they can deduce, using our best
     * deduction for the cards in our hand that they can see and we cannot.
     */
    fun knowledgeOf(p: Int): Map<Int, Knowledge> =
        hands.getOrElse(p) { emptyList() }.mapNotNull { o ->
            cards[o]?.let { c -> o to Knowledge(c.possible, deduced[o] ?: c.possible) }
        }.toMap()

    /** True if at least one of [ids] could be played on its stack right now. */
    fun anyPlayableNow(ids: Set<Pair<Int, Int>>) = ids.any { (s, r) -> s in stacks.indices && r in nextRanks(s) }

    /** True if every one of [ids] is playable on its stack right now. */
    fun allPlayableNow(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> s in stacks.indices && r in nextRanks(s) }

    /** "Red 3", or "a card" if [ids] is not a single identity. */
    fun describe(ids: Set<Pair<Int, Int>>): String =
        ids.singleOrNull()?.let { (s, r) -> "${suits.getOrElse(s) { "?" }} $r" } ?: "this card"

    /** Replace everyone's notes (the server's full "noteList"). */
    fun withAllNotes(authors: List<Triple<String, List<String>, Boolean>>): GameState {
        val byCard = HashMap<Int, MutableList<NoteEntry>>()
        for ((name, texts, isSpectator) in authors) {
            texts.forEachIndexed { order, text ->
                if (text.isNotBlank()) byCard.getOrPut(order) { ArrayList() } += NoteEntry(name, text, isSpectator)
            }
        }
        return copy(allNotes = byCard)
    }

    /** Replace everyone's notes on one card. */
    fun withCardNotes(order: Int, entries: List<NoteEntry>): GameState {
        val shown = entries.filter { it.text.isNotBlank() }
        return copy(allNotes = if (shown.isEmpty()) allNotes - order else allNotes + (order to shown))
    }

    /** Replace one author's note on one card (the server's live "note" update to spectators). */
    fun withCardNote(order: Int, entry: NoteEntry): GameState =
        withCardNotes(order, allNotes[order].orEmpty().filter { it.name != entry.name } + entry)

    fun withNotes(list: List<String>) =
        copy(notes = list.mapIndexedNotNull { order, t -> if (t.isBlank()) null else order to t }.toMap())

    fun withNote(order: Int, text: String) =
        copy(notes = if (text.isBlank()) notes - order else notes + (order to text.trim()))

    /** How many copies of each (suit, rank) are in the discard pile. */
    val discardCounts: Map<Pair<Int, Int>, Int> by lazy {
        discards.mapNotNull { cards[it] }.filter { it.suit >= 0 && it.rank >= 0 }
            .groupingBy { it.suit to it.rank }.eachCount()
    }

    /** Copies of a card in the deck (see [copiesOf]). */
    fun copies(suit: Int, rank: Int): Int = copiesOf(suitInfos.getOrNull(suit), rank, rules)

    /**
     * A (suit, rank) is dead if it is already played, or if every copy of a lower rank in that suit
     * that is still needed has been discarded (so the stack can never reach it).
     */
    fun isDeadIdentity(suit: Int, rank: Int): Boolean {
        if (suit !in stacks.indices || rank !in ranks) return false
        if (directional) {
            // Dead unless some run the stack can still follow reaches it without a rank that has no copies left in between.
            if (rank in stackRanks[suit]) return true
            val played = stackRanks[suit].size
            return runs(suit).none { run ->
                val rest = run.drop(played)
                val at = rest.indexOf(rank)
                at >= 0 && rest.take(at).none { r -> (discardCounts[suit to r] ?: 0) >= copies(suit, r) }
            }
        }
        if (rank <= stacks[suit]) return true
        return (stacks[suit] + 1 until rank).any { r -> (discardCounts[suit to r] ?: 0) >= copies(suit, r) }
    }

    /** Last remaining copy of a still-needed card: discarding it caps the maximum score. */
    fun isCriticalIdentity(suit: Int, rank: Int): Boolean =
        suit in stacks.indices && !isDeadIdentity(suit, rank) &&
            copies(suit, rank) - (discardCounts[suit to rank] ?: 0) <= 1

    /** Critical if every identity in [ids] is critical. */
    fun allCritical(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> isCriticalIdentity(s, r) }

    /** Safe to discard if every identity in [ids] is dead. */
    fun allDead(ids: Set<Pair<Int, Int>>) = ids.isNotEmpty() && ids.all { (s, r) -> isDeadIdentity(s, r) }

    /** True only when every identity the card could still be is dead (works for clued own cards). */
    fun isDead(c: Card): Boolean {
        val ids = if (c.suit >= 0 && c.rank >= 0) setOf(c.suit to c.rank) else c.possible
        return ids.isNotEmpty() && ids.all { (s, r) -> isDeadIdentity(s, r) }
    }

    companion object {
        fun totalCards(numSuits: Int) = numSuits * 10

        /**
         * Copies of a card in the deck: 3/2/2/2/1 by rank; 1 for a one-of-each suit (Black) or the variant's critical
         * rank; 2 for the ones when they are scarce.
         */
        fun copiesOf(suit: SuitInfo?, rank: Int, rules: VariantRules): Int = when {
            suit?.oneOfEach == true || rank == rules.criticalRank -> 1
            rank == START_RANK -> if (rules.upOrDown) 1 else 0
            rules.sudoku -> 2
            rank == 1 && (rules.upOrDown || suit?.reversed == true) -> 1
            rank == 1 && rules.scarceOnes -> 2
            rank == 5 && suit?.reversed == true -> 3
            else -> when (rank) { 1 -> 3; 5 -> 1; else -> 2 }
        }

        /** The ranks cards have: 1 to the stack size, and the START card in Up or Down. */
        fun ranksOf(rules: VariantRules): List<Int> =
            if (rules.upOrDown) listOf(1, 2, 3, 4, 5, START_RANK) else (1..rules.stackSize).toList()

        /** Cards in the whole deck. */
        fun deckCards(suitInfos: List<SuitInfo>, rules: VariantRules): Int =
            suitInfos.sumOf { suit -> ranksOf(rules).sumOf { rank -> copiesOf(suit, rank, rules) } }
    }
}

/** A time in milliseconds as the website writes it: 4:05, or 1:02:09 from an hour on. */
fun formatClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 500) / 1000
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

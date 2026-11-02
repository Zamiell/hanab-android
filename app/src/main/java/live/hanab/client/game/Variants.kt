package live.hanab.client.game

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How one suit reacts to clues, from hanab.live's suits.json.
 *  colors      clue colours that touch it (a plain suit: its own colour; Rainbow: none listed)
 *  allColors   touched by every colour clue (Rainbow)
 *  noColors    touched by no colour clue (White)
 *  allRanks    touched by every rank clue (Pink)
 *  noRanks     touched by no rank clue (Brown)
 *  prism       touched by the colour that goes with the card's rank (Prism)
 *  fills       display colours as ARGB ints; more than one means a gradient (Rainbow)
 */
data class SuitInfo(
    val name: String,
    val colors: List<String> = listOf(name),
    val allColors: Boolean = false,
    val noColors: Boolean = false,
    val allRanks: Boolean = false,
    val noRanks: Boolean = false,
    val fills: List<Int> = emptyList(),
    /** Key from suits.json naming the symbol ("auto" = the suit's own name). */
    val pip: String = "auto",
    /** Only one copy of each rank exists (Black): every card of the suit is critical. */
    val oneOfEach: Boolean = false,
    /** Alternative fills for colour-blind players (only some colours define one). */
    val fillsColorblind: List<Int> = emptyList(),
    /** A prism suit: a card is touched by the clue colour that goes with its rank (1 = the first colour, then round). */
    val prism: Boolean = false,
    /** Orange: attempting to play a card of the suit discards it, and attempting to discard it plays it. */
    val inverted: Boolean = false,
    /** Reversed suits are played 5, 4, 3, 2, 1 and have one 1 and three 5s. */
    val reversed: Boolean = false,
    /** The suit this one is the reversed version of (its own name for any other suit): its symbol is the same. */
    val base: String = name,
)

/**
 * The rules a variant adds on top of its suits, from hanab.live's variants.json (the ones we model).
 *  specialRank / rankTouchedByAllColors / rankTouchedByNoColors   Rainbow-Ones, White-Ones: cards of that rank are
 *                                                                 touched by every colour clue, or by none
 *  criticalRank   every card of that rank has a single copy (Critical Four)
 *  scarceOnes     two copies of each one instead of three
 *  clueStarved    discards give half a clue; the server counts clues at twice their value (a clue costs 2, up to 16)
 *  clueRanks, rankTouchedBy..., oddsAndEvens, funnels, chimneys   which rank clues exist and what they touch
 */
data class VariantRules(
    val specialRank: Int? = null,
    val rankTouchedByAllColors: Boolean = false,
    val rankTouchedByNoColors: Boolean = false,
    val criticalRank: Int? = null,
    val scarceOnes: Boolean = false,
    val clueStarved: Boolean = false,
    /** The rank clues on offer, by clue value: 1 to 5, except Pink-Ones has no 1 and Odds and Evens has just 1 and 2. */
    val clueRanks: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Pink-Ones: the special rank is touched by every rank clue. Brown-Ones: by none. Deceptive-Ones: by one per suit. */
    val rankTouchedByAllRanks: Boolean = false,
    val rankTouchedByNoRanks: Boolean = false,
    val rankDeceptive: Boolean = false,
    /** Rank clues are "odds" (1) and "evens" (2). */
    val oddsAndEvens: Boolean = false,
    /** A rank clue also touches every lower rank (Funnels) or every higher rank (Chimneys). */
    val funnels: Boolean = false,
    val chimneys: Boolean = false,
    /** Synesthesia: every card is also touched by the colour that goes with its rank, and there are no rank clues. */
    val synesthesia: Boolean = false,
    /** Color Blind / Number Blind: that kind of clue can be given (even with an empty hand match) but touches nothing. */
    val colorCluesTouchNothing: Boolean = false,
    val rankCluesTouchNothing: Boolean = false,
    /** Two clues in a row may not be of the same kind (colour, then rank, then colour...). */
    val alternatingClues: Boolean = false,
    /** The colour clues on offer when the variant names them (Color Mute: none), else null for the colours of the suits. */
    val clueColorsOverride: List<String>? = null,
    /** Cow & Pig and Duck: a clue is announced as "moo" / "oink" / "quack" and tells the cards nothing (it is said aloud). */
    val cowAndPig: Boolean = false,
    val duck: Boolean = false,
    /** Throw It in a Hole: played cards go face down, and the score and strikes stay hidden until the game is over. */
    val throwItInAHole: Boolean = false,
    /** Up or Down: a stack starts with a 1, a 5 or a START card and runs up or down from there. */
    val upOrDown: Boolean = false,
    /** Sudoku: a stack may start on any rank (a different one each) and wraps round from the top rank to 1. */
    val sudoku: Boolean = false,
    /** Cards in a stack: 5, or 4 in the four-suit Sudoku variants (no 5s). */
    val stackSize: Int = 5,
) {
    /**
     * Whether the bot library plays this variant. It reads which ranks Pink-, Brown- and Deceptive-Ones touch (and never gave
     * a rank clue the variant lacks in 198 test games), but not Odds and Evens, Funnels, Chimneys, Synesthesia or the clue restrictions.
     */
    val botCanPlay: Boolean
        get() = clueRanks.isNotEmpty() && !oddsAndEvens && !funnels && !chimneys && !synesthesia &&
            !colorCluesTouchNothing && !rankCluesTouchNothing && !alternatingClues && clueColorsOverride == null &&
            !cowAndPig && !duck && !throwItInAHole && !upOrDown && !sudoku
}

/** Suits and variants from the bundled copies of hanab.live's suits.json and variants.json. */
object Variants {
    private var suitInfos: Map<String, SuitInfo> = emptyMap()
    private var suitsByVariant: Map<String, List<String>> = emptyMap()
    private var supportedNames: List<String> = emptyList()
    private var namesById: Map<Int, String> = emptyMap()

    /** Suits we cannot model yet (none at the moment). */
    private val unsupportedSuits = mutableSetOf<String>()

    /** Variant keys that are pure metadata, and the special rules we model; any other key is a rule we do not implement. */
    private val plainKeys = setOf("id", "newID", "name", "suits")
    private val modelledKeys = plainKeys + setOf(
        "specialRank", "specialRankAllClueColors", "specialRankNoClueColors", "criticalRank", "scarceOnes", "clueStarved",
        "clueRanks", "specialRankAllClueRanks", "specialRankNoClueRanks", "specialRankDeceptive", "oddsAndEvens", "funnels", "chimneys",
        "synesthesia", "colorCluesTouchNothing", "rankCluesTouchNothing", "alternatingClues", "clueColors", "cowAndPig", "duck",
        "throwItInAHole", "upOrDown", "sudoku", "stackSize",
    )
    private var rulesByVariant: Map<String, VariantRules> = emptyMap()

    fun load(context: Context) {
        if (suitInfos.isNotEmpty()) return
        fun read(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        parse(read("colors.json"), read("suits.json"), read("variants.json"))
    }

    /** Builds the suit and variant tables from the JSON texts (apart from [load] so a test can run it). */
    fun parse(colorsText: String, suitsText: String, variantsText: String) {
        if (suitInfos.isNotEmpty()) return
        // Plain suits (Red, Blue, ...) carry no fill of their own: it comes from the colour of the same name.
        val colorFills = Json.parseToJsonElement(colorsText).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val n = o.str("name") ?: return@mapNotNull null
            val c = o.str("fill")?.let(::parseHex) ?: return@mapNotNull null
            n to c
        }.toMap()
        val colorFillsCb = Json.parseToJsonElement(colorsText).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val n = o.str("name") ?: return@mapNotNull null
            val c = o.str("fillColorblind")?.let(::parseHex) ?: return@mapNotNull null
            n to c
        }.toMap()
        val infos = mutableMapOf<String, SuitInfo>()
        for (el in Json.parseToJsonElement(suitsText).jsonArray) {
            val o = el.jsonObject
            val name = o.str("name") ?: continue
            val allColors = o.flag("allClueColors")
            val noColors = o.flag("noClueColors")
            val colors = (o["clueColors"] as? JsonArray)?.map { it.jsonPrimitive.content }
                ?: if (allColors || noColors || o.flag("prism")) emptyList() else listOf(name)
            var fills = ((o["fillColors"] as? JsonArray)?.map { it.jsonPrimitive.content }
                ?: listOfNotNull(o.str("fill"))).mapNotNull(::parseHex)
            val own = fills.isNotEmpty()
            if (fills.isEmpty()) fills = listOfNotNull(colorFills[name] ?: colors.firstNotNullOfOrNull { colorFills[it] })
            val fillsCb = if (own) emptyList()
            else listOfNotNull(colorFillsCb[name] ?: colors.firstNotNullOfOrNull { colorFillsCb[it] })
            infos[name] = SuitInfo(
                name, colors, allColors, noColors, o.flag("allClueRanks"), o.flag("noClueRanks"), fills,
                o.str("pip") ?: "auto",
                o.flag("oneOfEach"),
                fillsCb,
                prism = o.flag("prism"),
                inverted = o.flag("inverted"),
            )
        }
        // Every suit also exists reversed (the website adds " Reversed" to a suit's name), played from 5 down to 1.
        for ((n, i) in infos.toMap()) infos["$n Reversed"] = i.copy(name = "$n Reversed", reversed = true, base = i.base)
        suitInfos = infos

        val byName = mutableMapOf<String, List<String>>()
        val rules = mutableMapOf<String, VariantRules>()
        val byId = mutableMapOf<Int, String>()
        val ok = mutableListOf<String>()
        for (el in Json.parseToJsonElement(variantsText).jsonArray) {
            val o = el.jsonObject
            val name = o.str("name") ?: continue
            val suits = (o["suits"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: continue
            byName[name] = suits
            (o["id"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { byId[it] = name }
            if (o.keys.all { it in modelledKeys } && suits.all { it in infos && it !in unsupportedSuits }) {
                ok += name
                rules[name] = VariantRules(
                    specialRank = (o["specialRank"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    rankTouchedByAllColors = o.flag("specialRankAllClueColors"),
                    rankTouchedByNoColors = o.flag("specialRankNoClueColors"),
                    criticalRank = (o["criticalRank"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    scarceOnes = o.flag("scarceOnes"),
                    clueStarved = o.flag("clueStarved"),
                    clueRanks = (o["clueRanks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                        ?: listOf(1, 2, 3, 4, 5),
                    rankTouchedByAllRanks = o.flag("specialRankAllClueRanks"),
                    rankTouchedByNoRanks = o.flag("specialRankNoClueRanks"),
                    rankDeceptive = o.flag("specialRankDeceptive"),
                    oddsAndEvens = o.flag("oddsAndEvens"),
                    funnels = o.flag("funnels"),
                    chimneys = o.flag("chimneys"),
                    synesthesia = o.flag("synesthesia"),
                    colorCluesTouchNothing = o.flag("colorCluesTouchNothing"),
                    rankCluesTouchNothing = o.flag("rankCluesTouchNothing"),
                    alternatingClues = o.flag("alternatingClues"),
                    clueColorsOverride = (o["clueColors"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    cowAndPig = o.flag("cowAndPig"),
                    duck = o.flag("duck"),
                    throwItInAHole = o.flag("throwItInAHole"),
                    upOrDown = o.flag("upOrDown"),
                    sudoku = o.flag("sudoku"),
                    stackSize = (o["stackSize"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 5,
                )
            }
        }
        suitsByVariant = byName
        rulesByVariant = rules
        namesById = byId
        supportedNames = ok
    }

    fun suits(variant: String): List<String>? = suitsByVariant[variant]

    /** The variant the website numbers [id] (the "v0" of a seed like p3v0s258), if there is one. */
    fun nameOfId(id: Int): String? = namesById[id]

    fun info(suit: String): SuitInfo = suitInfos[suit] ?: SuitInfo(suit)

    /** The symbols an automatic pip picks from, in order (the website's AUTO_PIP_PRIORITY). */
    private val autoPips = listOf("hanabiRed1", "hanabiYellow2", "hanabiGreen3", "hanabiBlue4", "hanabiWhite5", "hanabiAltWhite6")

    /**
     * The symbol of each suit in [variant], by suit name. A suit with "auto" as its symbol (Tomato, Mahogany... of the
     * Ambiguous variants) takes the first one in [autoPips] that no earlier suit of the variant uses, as the website does.
     */
    fun pipsOf(variant: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = mutableListOf<String>()
        for (suit in suits(variant).orEmpty()) {
            val key = info(suit).pip.let { p -> if (p == "auto") autoPips.firstOrNull { it !in used } ?: p else p }
            used += key
            out[suit] = key
        }
        return out
    }

    @Volatile private var currentName: String? = null
    @Volatile private var currentPips: Map<String, String> = emptyMap()

    /** Makes [variant] the one whose symbols [pipFile] looks up (the game on screen). */
    fun useVariant(variant: String) {
        if (variant == currentName) return
        currentPips = pipsOf(variant)
        currentName = variant
    }

    /** Asset file (in assets/pips) holding the symbol for [suit], or null if there is none. */
    fun pipFile(suit: String): String? {
        val key = currentPips[suit] ?: info(suit).pip
        val file = when (key) {
            "auto" -> info(suit).base.lowercase()
            "hanabiRed1" -> "red"
            "hanabiYellow2" -> "yellow"
            "hanabiGreen3" -> "green"
            "hanabiBlue4" -> "blue"
            "hanabiWhite5" -> "purple"
            "hanabiAltWhite6" -> "teal"
            "katakanaHe" -> "black"
            "katakanaNu" -> "brown"
            "rainbow" -> "rainbow"
            "hashtag" -> "pink"
            "moon" -> "white"
            "infinity" -> "omni"
            "emptySet" -> "null"
            "triangle" -> "prism"
            "unoReverse" -> "orange"
            else -> return null
        }
        return "pips/$file.svg"
    }

    fun isSupported(variant: String) = variant in supportedNames

    /** The special rules of [variant] (none for a plain one). */
    fun rules(variant: String): VariantRules = rulesByVariant[variant] ?: VariantRules()

    /**
     * Names hanab-bot-rs says it cannot play (it warns "may crash or behave nonsensically"): a variant whose name
     * contains one of these is left out of offline play. Copied from the bot's `UNSUPPORTED_VARIANTS`.
     */
    private val botUnsupported = listOf(
        "Blind", "Mute", "Ambiguous", "Dual-Color", "Synesthesia", "Mix", "Funnels", "Chimneys", "Matryoshka",
        "Alternating", "Odds", "Cow", "Duck", "Reversed", "Throw", "Up or Down", "Sudoku",
    )

    /** What we can both show and the bots can play: the variants offered for offline games. */
    fun botSupported(): List<String> =
        supportedNames.filter { n -> botUnsupported.none { n.contains(it) } && rules(n).botCanPlay }

    /** Whether the bot can go through a game of [variant] (the same variants it can play). */
    fun canAnalyze(variant: String): Boolean = variant in botSupportedSet

    private val botSupportedSet: Set<String> by lazy { botSupported().toSet() }

    /**
     * The variants in [among] that match [query], best first. Case, hyphens, "&" and brackets do not matter and every
     * word of the query must be somewhere in the name, so "rainbow" finds "Rainbow-Ones (5 Suits)" and
     * "rainbow ones 5" finds just that one. Names that start with the query come first, then names with a word that
     * does; within those the order is the one in [among]. A blank query matches everything.
     */
    fun search(query: String, among: List<String>): List<String> {
        val words = words(query)
        if (words.isEmpty()) return among
        val joined = words.joinToString(" ")
        fun rank(name: String): Int {
            val w = words(name)
            return when {
                w.joinToString(" ").startsWith(joined) -> 0
                w.any { it.startsWith(words[0]) } -> 1
                else -> 2
            }
        }
        return among.filter { name -> val have = words(name).joinToString(" "); words.all { it in have } }
            .sortedBy { rank(it) }
    }

    private fun words(s: String): List<String> =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

    /** Everything we can play, for the table-creation search. */
    fun allSupported(): List<String> = supportedNames

    /** Quick picks shown as chips in the create-table dialog. */
    val quick = listOf("No Variant", "6 Suits", "Rainbow (5 Suits)", "Rainbow (6 Suits)", "Black (6 Suits)", "4 Suits", "3 Suits")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true

    private fun parseHex(s: String): Int? {
        val h = s.removePrefix("#")
        if (h.length != 6) return null
        return h.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }
}

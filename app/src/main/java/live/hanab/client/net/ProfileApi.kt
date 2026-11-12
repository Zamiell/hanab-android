package live.hanab.client.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import live.hanab.client.protocol.GameHistoryEntry
import live.hanab.client.protocol.GamePage
import live.hanab.client.protocol.ProtocolJson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One finished game in a player's history, as the site's `/api/v1/history/<name>` lists it (a [GameHistoryEntry]). */
data class HistoryRow(
    val id: Int = 0,
    val numPlayers: Int = 0,
    val score: Int = 0,
    /** The site's variant id; see [ProfileApi.variantNames]. */
    val variant: Int = 0,
    /** Everyone at the table. */
    val playerNames: List<String> = emptyList(),
    /** Like "2026-10-25T01:44:52.123Z". */
    val finishedAt: String = "",
) {
    val date: String get() = finishedAt.substringBefore("T")
    fun others(me: String): List<String> = playerNames.filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }

    companion object {
        fun of(g: GameHistoryEntry) = HistoryRow(g.gameId, g.playerNames.size, g.score, g.options.variantId, g.playerNames, g.finishedAt)
    }
}

data class HistoryPage(val total: Int, val rows: List<HistoryRow>)

/** A player's best score in a variant at one table size; [max] is the most that variant allows. */
data class BestScore(val score: Int, val max: Int)

/** One row of the stats grid: how a player did in one variant. */
data class VariantRow(
    val variant: String,
    val games: Int,
    /** Best scores for 2 to 6 players, in that order. */
    val best: List<BestScore>,
    /** Null when there is no finished game to average. */
    val average: Double?,
    val strikeoutRate: Double?,
)

/** What the website's profile page ("/scores/<name>") shows: the facts at the top and the grid of variants. */
data class ProfilePage(val facts: List<Pair<String, String>>, val rows: List<VariantRow>)

/** The site says this (HTTP 404) when the name is not a player. */
class NoSuchPlayer : Exception("That player does not exist.")

/**
 * The public, read-only side of hanab.live (no sign-in needed). Game history comes from its JSON API; the stats
 * only exist in the website's server-rendered profile page, so [parseScores] reads that.
 */
object ProfileApi {
    const val HISTORY_PAGE_SIZE = 20

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var variants: Map<Int, String>? = null

    private fun get(url: String, json: Boolean = true): String {
        val request = Request.Builder().url(url).apply { if (json) header("Accept", "application/json") }.build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) throw NoSuchPlayer()
            if (!resp.isSuccessful) error("The server answered ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** The website's export of a finished game (players, deck and every action), as text. */
    suspend fun export(id: Long): String = withContext(Dispatchers.IO) { WireAdapter.legacyExport(get("https://$BASE_HOST/export/$id", json = false)) }

    /** Variant id to name, fetched once. */
    suspend fun variantNames(): Map<Int, String> {
        variants?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            HanabJson.parseToJsonElement(get("https://$BASE_HOST/api/v1/variants")).jsonObject
                .mapNotNull { (id, name) -> id.toIntOrNull()?.let { it to name.jsonPrimitive.content } }
                .toMap()
        }
        variants = loaded
        return loaded
    }

    /** The stats page of [name]. */
    suspend fun scores(name: String): ProfilePage = withContext(Dispatchers.IO) {
        val url = "https://$BASE_HOST/scores".toHttpUrl().newBuilder().addPathSegment(name).build()
        parseScores(get(url.toString(), json = false)) ?: error("The profile page was not in the expected format")
    }

    /**
     * One page ([HISTORY_PAGE_SIZE] games, counting from page 0) of [name]'s games, newest first, optionally only
     * those with this many players and/or this variant.
     */
    suspend fun history(name: String, page: Int, players: Int? = null, variantId: Int? = null): HistoryPage =
        withContext(Dispatchers.IO) {
            val url = "https://$BASE_HOST/api/v1/history".toHttpUrl().newBuilder()
                .addPathSegment(name)
                .addQueryParameter("offset", (page * HISTORY_PAGE_SIZE).toString())
                .addQueryParameter("limit", HISTORY_PAGE_SIZE.toString())
                .apply {
                    if (players != null) addQueryParameter("filter[numPlayers]", players.toString())
                    if (variantId != null) addQueryParameter("filter[variantId]", variantId.toString())
                }
                .build()
            val page = ProtocolJson.decodeFromString(GamePage.serializer(), get(url.toString()))
            HistoryPage(page.total.toInt(), page.games.map(HistoryRow::of))
        }
}

private val FACT = Regex("""<li>\s*<span class="stat-description"\s*>(.*?)</span\s*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
private val ROW = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
private val CELL = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
private val TAG = Regex("""<[^>]+>""")
private val SCORE = Regex("""(\d+)\s*/\s*(\d+)""")

/** The text of a bit of HTML: tags gone, entities decoded, whitespace collapsed. */
private fun text(html: String): String =
    TAG.replace(html, " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()

/** Reads the website's profile page; null if it does not have the facts and the grid. */
fun parseScores(html: String): ProfilePage? {
    val table = html.indexOf("<table class=\"history\"")
    if (table < 0) return null
    val facts = FACT.findAll(html.substring(0, table)).map { text(it.groupValues[1]).trimEnd(':') to text(it.groupValues[2]) }.toList()
    val body = html.substring(table).substringAfter("<tbody>", "").substringBefore("</tbody>")
    val rows = ROW.findAll(body).mapNotNull { row ->
        val cells = CELL.findAll(row.groupValues[1]).map { text(it.groupValues[1]) }.toList()
        // The grid has the total and the five best scores; older pages also had the average and the strikeout rate.
        if (cells.size < 7) return@mapNotNull null
        // A table size nobody played shows a dash: no score yet, out of what the other sizes show.
        val found = cells.subList(2, 7).map { c -> SCORE.find(c)?.let { BestScore(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } }
        val max = found.firstNotNullOfOrNull { it?.max } ?: 0
        VariantRow(
            variant = cells[0],
            games = cells[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null,
            best = found.map { it ?: BestScore(0, max) },
            average = cells.getOrNull(7)?.toDoubleOrNull(),
            strikeoutRate = cells.getOrNull(8)?.removeSuffix("%")?.toDoubleOrNull(),
        )
    }.toList()
    return ProfilePage(facts, rows)
}

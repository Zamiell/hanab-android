package live.hanab.client.net

import android.content.Context
import live.hanab.client.OfflineGame
import live.hanab.client.OfflineStore
import live.hanab.client.game.Variants
import java.time.Instant
import java.time.ZoneId

/** One line of a game list. */
data class GameLine(
    val id: Long,
    val date: String,
    val players: Int,
    val variant: String,
    val score: Int,
    /** The most the variant allows, if known. */
    val max: Int?,
    val others: List<String>,
    /** A word of extra detail, e.g. the bots' convention offline. */
    val note: String? = null,
    /** The conventions the bots played, for a game played offline. */
    val convention: String? = null,
)

data class GameLines(val total: Int, val lines: List<GameLine>)

/** Where a profile's stats and games come from: the website, or the games played offline on this phone. */
interface ProfileSource {
    /** True if the stats can change while the app runs (a game finishes), so they are read again each time. */
    val changes: Boolean get() = false

    suspend fun scores(): ProfilePage

    /** Variant names to search when filtering the games. */
    suspend fun variantNames(): List<String>

    /** One page ([ProfileApi.HISTORY_PAGE_SIZE] games, from page 0), newest first. */
    suspend fun games(page: Int, players: Int?, variant: String?): GameLines
}

class OnlineSource(private val name: String) : ProfileSource {
    override suspend fun scores() = ProfileApi.scores(name)

    override suspend fun variantNames() = ProfileApi.variantNames().values.toList()

    override suspend fun games(page: Int, players: Int?, variant: String?): GameLines {
        val names = ProfileApi.variantNames()
        val variantId = variant?.let { v -> names.entries.firstOrNull { it.value == v }?.key }
        val result = ProfileApi.history(name, page, players, variantId)
        return GameLines(
            result.total,
            result.rows.map { row ->
                val variantName = names[row.variant] ?: "Variant ${row.variant}"
                GameLine(
                    row.id.toLong(), row.date, row.numPlayers, variantName, row.score,
                    Variants.perfectScore(variantName), row.others(name),
                )
            },
        )
    }
}

class OfflineSource(private val context: Context) : ProfileSource {
    override val changes = true

    override suspend fun scores() = offlineScores(OfflineStore.all(context))

    override suspend fun variantNames() = OfflineStore.all(context).map { it.variant }.distinct().sorted()

    override suspend fun games(page: Int, players: Int?, variant: String?): GameLines {
        val matching = OfflineStore.all(context).filter { (players == null || it.players == players) && (variant == null || it.variant == variant) }
        val size = ProfileApi.HISTORY_PAGE_SIZE
        return GameLines(
            matching.size,
            matching.drop(page * size).take(size).map { g ->
                GameLine(g.id, dateOf(g.id), g.players, g.variant, g.score, g.perfectScore, g.others(), listOf(g.convention, g.seed).filter { it.isNotEmpty() }.joinToString(" · "), g.convention)
            },
        )
    }
}

internal fun dateOf(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** "6 days, 23 hours, and 27 minutes", like the website's. */
internal fun durationText(ms: Long): String {
    val minutes = ms / 60_000
    val parts = listOf(minutes / 1440 to "day", minutes / 60 % 24 to "hour", minutes % 60 to "minute")
        .filter { it.first > 0 }
        .map { (n, unit) -> "$n $unit" + if (n == 1L) "" else "s" }
    return when (parts.size) {
        0 -> "less than a minute"
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts[0]}, ${parts[1]}, and ${parts[2]}"
    }
}

/** The same facts and grid the website's profile has, worked out from the games played offline. */
fun offlineScores(games: List<OfflineGame>): ProfilePage {
    val perfect = games.count { it.perfect }
    val facts = buildList {
        add("Games played" to "${games.size}")
        add("Time spent playing" to if (games.isEmpty()) "-" else durationText(games.sumOf { it.durationMs }))
        add("Perfect games" to if (games.isEmpty()) "0" else "$perfect (${"%.1f".format(java.util.Locale.US, perfect * 100.0 / games.size)}%)")
        games.minOfOrNull { it.id }?.let { add("First game" to dateOf(it)) }
    }
    val rows = games.groupBy { it.variant }.map { (variant, g) ->
        val max = g.first().perfectScore
        VariantRow(
            variant = variant,
            games = g.size,
            best = (2..6).map { n -> BestScore(g.filter { it.players == n }.maxOfOrNull { it.score } ?: 0, max) },
            average = g.map { it.score }.average(),
            strikeoutRate = g.count { it.struckOut } * 100.0 / g.size,
        )
    }.sortedByDescending { it.games }
    return ProfilePage(facts, rows)
}

package live.hanab.client

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.net.HanabJson
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What is kept to list and total up a finished offline game; the moves live in a file of their own. */
@Serializable
data class OfflineGame(
    /** When it started (epoch milliseconds); also names its file. */
    val id: Long,
    val variant: String,
    val convention: String,
    /** Everyone at the table, in seat order. */
    val names: List<String>,
    /** The seat that was ours. */
    val human: Int,
    val score: Int,
    val maxScore: Int,
    /** 1 played out, 2 struck out. */
    val endCondition: Int,
    val durationMs: Long,
    /** The seed the way the website names it, like "p3v0s50"; empty for games kept before seeds. */
    val seed: String = "",
) {
    val players: Int get() = names.size
    val struckOut: Boolean get() = endCondition == 2
    /** What the variant allows (the game's own [maxScore] shrinks when a critical card is lost, which is not a perfect game). */
    val perfectScore: Int get() = live.hanab.client.game.Variants.perfectScore(variant) ?: maxScore
    val perfect: Boolean get() = score == perfectScore
    fun others(): List<String> = names.filterIndexed { i, _ -> i != human }
}

/**
 * Finished offline games on this phone. Each game is a gzipped file (every move, so it can be replayed) and a
 * line in an index of summaries, so the lists and stats do not have to open every game.
 */
object OfflineStore {
    private val lock = Any()
    private var cache: List<OfflineGame>? = null

    private fun dir(context: Context) = File(context.filesDir, "offline").apply { mkdirs() }
    private fun index(context: Context) = File(dir(context), "index.jsonl")

    /** Keeps the record the offline table sends when a game ends (see `offlineRecord`). */
    fun save(context: Context, recordJson: String) {
        val o = HanabJson.parseToJsonElement(recordJson).jsonObject
        val game = OfflineGame(
            id = o.getValue("startedAt").jsonPrimitive.content.toLong(),
            variant = o.getValue("variant").jsonPrimitive.content,
            convention = o.getValue("convention").jsonPrimitive.content,
            names = (o.getValue("names") as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
            human = o.getValue("human").jsonPrimitive.content.toInt(),
            score = o.getValue("score").jsonPrimitive.content.toInt(),
            maxScore = o.getValue("maxScore").jsonPrimitive.content.toInt(),
            endCondition = o.getValue("endCondition").jsonPrimitive.content.toInt(),
            durationMs = o.getValue("durationMs").jsonPrimitive.content.toLong(),
            seed = o["seed"]?.jsonPrimitive?.content.orEmpty(),
        )
        synchronized(lock) {
            GZIPOutputStream(File(dir(context), "${game.id}.json.gz").outputStream()).bufferedWriter().use { it.write(recordJson) }
            index(context).appendText(HanabJson.encodeToString(game) + "\n")
            cache = (listOf(game) + all(context)).distinctBy { it.id }
        }
    }

    /** Every kept game, newest first. */
    fun all(context: Context): List<OfflineGame> = synchronized(lock) {
        cache ?: index(context).takeIf { it.exists() }?.readLines().orEmpty()
            .mapNotNull { line -> runCatching { HanabJson.decodeFromString<OfflineGame>(line) }.getOrNull() }
            .distinctBy { it.id }
            .sortedByDescending { it.id }
            .also { cache = it }
    }

    /** The full record of a game, as the table sent it. */
    fun record(context: Context, id: Long): String? = synchronized(lock) {
        val file = File(dir(context), "$id.json.gz")
        if (!file.exists()) null else GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
    }
}

private val SEED_NAME = Regex("""^p\d+v\d+s(.*)$""")

/**
 * The seed to play when none is chosen, found as the website does: the lowest number (1, 2, 3, ...) that has not
 * been played yet at this table size in this variant.
 */
fun nextUnplayedSeed(games: List<OfflineGame>, players: Int, variant: String): String {
    val played = games.filter { it.players == players && it.variant == variant }
        .mapNotNull { SEED_NAME.find(it.seed)?.groupValues?.get(1) }
        .toSet()
    return generateSequence(1) { it + 1 }.first { it.toString() !in played }.toString()
}

package live.hanab.client

/**
 * The offline table: a small hanab.live "server" with bots (hanab-bot-rs), written in Rust and built from
 * native/offline. It is packaged for 64-bit ARM phones, and for x86_64 emulators in debug builds; [available] is false anywhere else.
 */
object NativeOffline {
    /** The table sizes offline games come in, you included (the same as MIN_PLAYERS and MAX_PLAYERS in native/offline). */
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 5

    val available: Boolean = try {
        System.loadLibrary("hanab_offline")
        true
    } catch (_: Throwable) {
        false
    }

    /** How many threads the bot's endgame search may use from now on (1 to 4); fewer saves battery. */
    external fun setThreads(threads: Int)

    /** Starts a table. Returns `{"handle": n}` or `{"error": "..."}`. */
    external fun start(variantsJson: String, suitsJson: String, configJson: String): String

    /** Starts a replay of a game the table sent as an `offlineRecord`. Returns `{"handle": n}` or `{"error": "..."}`. */
    external fun startReplay(recordJson: String): String

    /**
     * Starts the bot going through a finished game (the website's export or an offline record), on threads of its
     * own, and returns a handle (0 if it could not start). Ask how it is doing with [analyzePoll].
     */
    external fun analyzeStart(variantsJson: String, suitsJson: String, gameJson: String, variant: String, convention: String): Long

    /** `progress <moves done> <moves in all>` while it runs, then `result <json>` (`{"players", "comments"}` or `{"error"}`). */
    external fun analyzePoll(handle: Long): String

    /** Stops an analysis between moves. */
    external fun analyzeCancel(handle: Long)

    /** Passes one command of the app (`getGameInfo1`, `action`, ...) to the table. */
    external fun send(handle: Long, command: String, dataJson: String)

    /** The next message of the table, waiting up to [timeoutMs]: null if none yet, "" once the table is done. */
    external fun poll(handle: Long, timeoutMs: Long): String?

    external fun close(handle: Long)
}

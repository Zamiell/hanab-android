package live.hanab.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SoundMode(val label: String) { Off("Off"), OwnTurn("Only my turn"), All("All turns") }
enum class AnimSpeed(val label: String, val factor: Float) { Off("Off", 0f), Fast("Fast", 0.5f), Normal("Normal", 1f) }
enum class OutlineStyle(val label: String, val factor: Float, val maxPx: Float) {
    Off("Off", 0f, 0f), Thin("Thin", 0.02f, 1.2f), Normal("Normal", 0.03f, 2f), Bold("Bold", 0.045f, 3f)
}

/**
 * User preferences, kept in SharedPreferences. Each property is Compose state, so the UI reacts
 * the moment a setting changes.
 */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    var pips by mutableStateOf(true); private set
    var outline by mutableStateOf(OutlineStyle.Normal); private set
    var animSpeed by mutableStateOf(AnimSpeed.Normal); private set
    var soundMode by mutableStateOf(SoundMode.All); private set
    var volume by mutableStateOf(50); private set
    var haptics by mutableStateOf(true); private set
    var keepAwake by mutableStateOf(true); private set

    /** Open the clue panel by itself at the start of your turn. Off by default: the Clue button opens it. */
    var autoClue by mutableStateOf(false); private set

    /** Show tables in variants this app cannot play (dimmed, with no Join / Watch). Hidden by default. */
    var showUnsupported by mutableStateOf(false); private set

    /** The faint fireworks picture behind every screen. */
    var backdrop by mutableStateOf(true); private set

    /** Cards drawn with the app's own flat style (the default is hanab.live's card fronts). */
    var simplifiedCards by mutableStateOf(false); private set

    /** The last table password typed when creating or joining a table (people usually reuse one). */
    var lastPassword by mutableStateOf(""); private set

    fun load(context: Context) {
        prefs = context.getSharedPreferences("hanab_ui", Context.MODE_PRIVATE)
        pips = prefs.getBoolean("pips", true)
        outline = enumOf("outline", OutlineStyle.Normal)
        animSpeed = enumOf("animSpeed", AnimSpeed.Normal)
        soundMode = enumOf("soundMode", SoundMode.All)
        volume = prefs.getInt("volume", 50)
        haptics = prefs.getBoolean("haptics", true)
        keepAwake = prefs.getBoolean("keepAwake", true)
        backdrop = prefs.getBoolean("backdrop", true)
        autoClue = prefs.getBoolean("autoClue", false)
        simplifiedCards = prefs.getBoolean("simplifiedCards", false)
        showUnsupported = prefs.getBoolean("showUnsupported", false)
        lastPassword = prefs.getString("lastPassword", "") ?: ""
    }

    fun rememberPassword(v: String) {
        if (v.isBlank()) return
        lastPassword = v
        prefs.edit().putString("lastPassword", v).apply()
    }

    fun changePips(v: Boolean) { pips = v; prefs.edit().putBoolean("pips", v).apply() }
    fun changeOutline(v: OutlineStyle) { outline = v; prefs.edit().putString("outline", v.name).apply() }
    fun changeAnimSpeed(v: AnimSpeed) { animSpeed = v; prefs.edit().putString("animSpeed", v.name).apply() }
    fun changeSoundMode(v: SoundMode) { soundMode = v; prefs.edit().putString("soundMode", v.name).apply() }
    fun changeVolume(v: Int) { volume = v.coerceIn(0, 100); prefs.edit().putInt("volume", volume).apply() }
    fun changeHaptics(v: Boolean) { haptics = v; prefs.edit().putBoolean("haptics", v).apply() }
    fun changeShowUnsupported(v: Boolean) { showUnsupported = v; prefs.edit().putBoolean("showUnsupported", v).apply() }
    fun changeAutoClue(v: Boolean) { autoClue = v; prefs.edit().putBoolean("autoClue", v).apply() }
    fun changeBackdrop(v: Boolean) { backdrop = v; prefs.edit().putBoolean("backdrop", v).apply() }
    fun changeSimplifiedCards(v: Boolean) { simplifiedCards = v; prefs.edit().putBoolean("simplifiedCards", v).apply() }
    fun changeKeepAwake(v: Boolean) { keepAwake = v; prefs.edit().putBoolean("keepAwake", v).apply() }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}

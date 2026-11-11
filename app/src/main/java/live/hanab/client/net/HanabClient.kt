package live.hanab.client.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import live.hanab.client.NativeOffline
import java.util.concurrent.TimeUnit

const val BASE_HOST = "hanab.live"

private class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        store.addAll(cookies)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }
}

enum class ConnState { Disconnected, Connecting, Connected }

/**
 * Talks to hanab.live: POST /login (sets a session cookie), then a WebSocket at /ws.
 * Frames are `command {json}`.
 */
class HanabClient {
    private val http = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Turns the server's messages into the ones the app reads, and the app's commands into the server's. */
    private var adapter = WireAdapter()

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<ServerMessage> = _messages

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    /** The website's current version, which the server wants on the login and on the WebSocket (see [siteVersion]). */
    @Volatile private var version = ""

    /**
     * The version of the website's client code that is live now: the hash in the name of its script. The server turns
     * away any client that does not name it (a client that says it is a bot is exempt, but then the account is marked
     * as a bot for good, which this app, played by people, must not do).
     */
    private fun siteVersion(): String? {
        val html = http.newCall(Request.Builder().url("https://$BASE_HOST/").build()).execute().use { it.body?.string().orEmpty() }
        return parseSiteVersion(html)
    }

    /** Returns null on success, otherwise a human-readable error. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        val current = try {
            siteVersion()
        } catch (e: Exception) {
            return@withContext "Network error: ${e.message}"
        } ?: return@withContext "Could not read the website's version, so the server would refuse this app."
        version = current
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("version", current)
            .build()
        val req = Request.Builder().url("https://$BASE_HOST/login").post(body).build()
        try {
            http.newCall(req).execute().use { resp: Response ->
                if (resp.isSuccessful) null
                else loginError(resp.body?.string(), resp.code)
            }
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }
    }

    /** What the server said when the login failed: `{"message": "...", "code": "..."}` (with some HTML), or plain text. */
    private fun loginError(body: String?, code: Int): String {
        val text = body?.trim().orEmpty()
        val message = runCatching { HanabJson.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
        return (message ?: text).replace(Regex("<[^>]*>"), " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { "Login failed ($code)" }
    }

    fun connect() {
        if (_state.value != ConnState.Disconnected) return
        _state.value = ConnState.Connecting
        adapter = WireAdapter()
        val req = Request.Builder()
            // The WebSocket checks the client version too (since Hanabi-Live/hanabi-live#3254), as the login does.
            .url("https://$BASE_HOST/ws?version=$version")
            .header("Origin", "https://$BASE_HOST")
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                unpack(text)?.let { m -> adapter.inbound(m).forEach { _messages.tryEmit(it) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnState.Disconnected
                _errors.tryEmit("Connection lost: ${t.message ?: response?.code}")
            }
        })
    }

    fun send(command: String, data: JsonObject? = null) {
        if (offlineHandle != 0L) {
            NativeOffline.send(offlineHandle, command, data?.toString() ?: "{}")
            return
        }
        adapter.outbound(command, data).forEach { (c, d) ->
            socket?.send(pack(c, d))
        }
    }

    // ------------------------------------------------------------ offline play
    // The offline table (see NativeOffline) answers the same messages as the server, so the rest of the app
    // reads them from [messages] and writes to [send] as usual.

    private var offlineHandle = 0L
    private var offlineJob: Job? = null

    val isOffline: Boolean get() = offlineHandle != 0L

    /** Starts an offline table; returns null on success, otherwise what went wrong. */
    fun startOffline(scope: CoroutineScope, variantsJson: String, suitsJson: String, configJson: String): String? {
        if (!NativeOffline.available) return "Offline play is not available on this phone."
        return attachOffline(scope, NativeOffline.start(variantsJson, suitsJson, configJson))
    }

    /** Replays a game kept by [live.hanab.client.OfflineStore]; returns null on success, otherwise what went wrong. */
    fun startOfflineReplay(scope: CoroutineScope, recordJson: String): String? {
        if (!NativeOffline.available) return "Offline play is not available on this phone."
        return attachOffline(scope, NativeOffline.startReplay(recordJson))
    }

    /** Reads messages from the offline table that [reply] (`{"handle": n}` or `{"error": ...}`) started. */
    private fun attachOffline(scope: CoroutineScope, reply: String): String? {
        val reply = HanabJson.parseToJsonElement(reply).jsonObject
        reply["error"]?.let { return it.jsonPrimitive.content }
        val handle = reply["handle"]?.jsonPrimitive?.long ?: return "Offline play could not start."
        offlineHandle = handle
        _state.value = ConnState.Connected
        offlineJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val line = NativeOffline.poll(handle, 500) ?: continue
                if (line.isEmpty()) break
                unpack(line)?.let { _messages.tryEmit(it) }
            }
        }
        return null
    }

    fun stopOffline() {
        val handle = offlineHandle
        if (handle == 0L) return
        offlineHandle = 0L
        offlineJob?.cancel()
        offlineJob = null
        NativeOffline.close(handle)
        _state.value = ConnState.Disconnected
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        _state.value = ConnState.Disconnected
    }
}

private val SITE_SCRIPT = Regex("""/public/js/bundles/main\.([0-9a-f]+)\.min\.js""")

/** The website's client version in its home page: the hash in the name of the main script; null if there is none. */
fun parseSiteVersion(html: String): String? = SITE_SCRIPT.find(html)?.groupValues?.get(1)

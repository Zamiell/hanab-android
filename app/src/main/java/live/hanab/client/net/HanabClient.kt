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

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<ServerMessage> = _messages

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    /** Returns null on success, otherwise a human-readable error. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            // The server rejects stale client versions; "bot" skips that check.
            .add("version", "bot")
            .build()
        val req = Request.Builder().url("https://$BASE_HOST/login").post(body).build()
        try {
            http.newCall(req).execute().use { resp: Response ->
                if (resp.isSuccessful) null
                else resp.body?.string()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Login failed (${resp.code})"
            }
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }
    }

    fun connect() {
        if (_state.value != ConnState.Disconnected) return
        _state.value = ConnState.Connecting
        val req = Request.Builder()
            // The WebSocket checks the client version too (since Hanabi-Live/hanabi-live#3254); "bot" skips that check, as at login.
            .url("https://$BASE_HOST/ws?version=bot")
            .header("Origin", "https://$BASE_HOST")
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                unpack(text)?.let { _messages.tryEmit(it) }
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
        socket?.send(pack(command, data))
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

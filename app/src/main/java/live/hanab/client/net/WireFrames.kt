package live.hanab.client.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The frames around the website's payloads (hanabi-live `packages/data/src/functions/frames.ts`; they are not generated, so
 * `scripts/protocol.ps1 outdated` watches that file too). Each frame is one JSON object:
 *
 *  - a request of ours: `{"id": 1, "type": "tableJoin", "data": {...}}`, with an id that we choose;
 *  - the answer to it, once: `{"reply": 1}` with the reply's `data` if it has any, or an `error`;
 *  - a message the server sends on its own: `{"event": "table", "data": {...}}`.
 */
sealed interface WireFrame {
    class Event(val name: String, val data: JsonElement?) : WireFrame
    class Reply(val id: Long, val data: JsonElement?, val error: JsonElement?) : WireFrame
}

object WireFrames {
    fun request(id: Long, command: String, data: JsonElement): String = buildJsonObject {
        put("id", id)
        put("type", command)
        put("data", data)
    }.toString()

    /** Reads a frame of the server; null if it is not one. */
    fun parse(text: String): WireFrame? {
        val frame = runCatching { HanabJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val data = frame["data"]?.takeIf { it !is JsonNull }
        val error = frame["error"]?.takeIf { it !is JsonNull }
        (frame["event"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return WireFrame.Event(it.content, data) }
        (frame["reply"] as? JsonPrimitive)?.longOrNull?.let { return WireFrame.Reply(it, data, error) }
        return null
    }

    /** What the server's error to a request says: a notice (`{"message": ...}`), or else the text of whatever it sent. */
    fun errorText(error: JsonElement): String =
        ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
}

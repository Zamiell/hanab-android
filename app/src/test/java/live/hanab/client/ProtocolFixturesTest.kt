package live.hanab.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.protocol.InInvalid
import live.hanab.client.protocol.InUnknown
import live.hanab.client.protocol.Inbound
import live.hanab.client.protocol.Outbound
import live.hanab.client.protocol.PROTOCOL_REV
import live.hanab.client.protocol.ProtocolJson
import live.hanab.client.protocol.decodeInbound
import live.hanab.client.protocol.decodeOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The website's own compatibility cases (`server/protocol/fixtures/compatibility.json`, the ones its Rust and Bun tests
 * share), at the commit the generated types are pinned to: every message the server accepts must read as the type its
 * command names, and writing it back must give what the server normalizes it to (so our defaults are the server's).
 * `scripts/protocol.ps1 bump` refreshes the file together with the types.
 */
class ProtocolFixturesTest {
    private val cases: JsonArray = ProtocolJson.parseToJsonElement(
        javaClass.getResourceAsStream("/protocol/compatibility.json")!!.bufferedReader().readText(),
    ) as JsonArray

    /** What a message of the client or the server looks like once read and written again (a server message only reads). */
    private fun roundTrip(direction: String, command: String, input: JsonElement): JsonElement? =
        if (direction == "client") {
            val out: Outbound = decodeOutbound(command, input)
            out.encode().second
        } else {
            val message: Inbound = decodeInbound(command, input)
            if (message is InUnknown || message is InInvalid) {
                val why = (message as? InInvalid)?.error ?: "no such command"
                throw AssertionError("the server's message $command does not read: $why")
            }
            null
        }

    @Test fun everyMessageTheServerAcceptsReads() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (case in cases) {
            val c = case.jsonObject
            if (c["valid"]!!.jsonPrimitive.content != "true") continue
            val name = c["name"]!!.jsonPrimitive.content
            // A JavaScript client cannot tell 1 from 1.0, so the website's own client accepts what its server refuses
            // (the fixtures record that difference); this one follows the server, as it reads numbers exactly.
            if ("float token" in name) continue
            val direction = c["direction"]!!.jsonPrimitive.content
            val command = c["command"]!!.jsonPrimitive.content
            try {
                val written = roundTrip(direction, command, c["input"]!!)
                val normalized = c["normalized"]
                if (written != null && normalized != null && written != normalized) {
                    failures += "$name: wrote $written, the server normalizes to $normalized"
                }
                checked++
            } catch (e: Exception) {
                failures += "$name ($direction $command): ${e.message}"
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        assertTrue("checked only $checked cases", checked >= 20)
        assertEquals("pinned to $PROTOCOL_REV:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test fun theCasesAreTheOnesOfThePinnedCommit() {
        // A guard against forgetting to refresh the file: it names objects only the pinned protocol has.
        assertTrue(cases.isNotEmpty())
        assertTrue(cases.all { (it as JsonObject).containsKey("command") })
    }
}

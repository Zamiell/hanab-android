package live.hanab.client

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.hanab.client.net.HanabJson
import live.hanab.client.net.WireFrame
import live.hanab.client.net.WireFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frames around the website's payloads: requests with an id, replies that repeat it, and events. */
class WireFramesTest {
    @Test fun aRequestCarriesItsId() {
        val frame = HanabJson.parseToJsonElement(WireFrames.request(7, "tableJoin", HanabJson.parseToJsonElement("""{"tableId":3}""")))
        assertEquals(7, frame.jsonObject["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("tableJoin", frame.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(3, frame.jsonObject["data"]!!.jsonObject["tableId"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun anEventNamesItself() {
        val frame = WireFrames.parse("""{"event":"table","data":{"tableId":1}}""") as WireFrame.Event
        assertEquals("table", frame.name)
        assertEquals(1, frame.data!!.jsonObject["tableId"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun aReplyRepeatsTheId() {
        val bare = WireFrames.parse("""{"reply":4}""") as WireFrame.Reply
        assertEquals(4L, bare.id)
        assertNull(bare.data)
        assertNull(bare.error)
        val withData = WireFrames.parse("""{"reply":5,"data":{"messages":[]}}""") as WireFrame.Reply
        assertTrue(withData.data != null)
        val failed = WireFrames.parse("""{"reply":6,"error":{"message":"Wrong password.","code":"commandFailed"}}""") as WireFrame.Reply
        assertEquals("Wrong password.", WireFrames.errorText(failed.error!!))
        assertEquals("just text", WireFrames.errorText(JsonPrimitive("just text")))
    }

    @Test fun anythingElseIsNotAFrame() {
        assertNull(WireFrames.parse("welcome {\"userId\":1}"))
        assertNull(WireFrames.parse("[1,2]"))
        assertNull(WireFrames.parse("""{"hello":1}"""))
    }
}

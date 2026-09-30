package ch.madtreasures.g2watch.geckoprobe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeBridgeTest {

    /** What content.js posts for `even.call(method, data)` (common/even.js builds the payload). */
    private fun call(id: Long, method: String, data: String = "{}"): String {
        val payload = """{"type":"call_even_app_method","method":"$method","data":$data}"""
        return buildJsonObject {
            put("kind", "call")
            put("id", id)
            put("name", "evenAppMessage")
            put("payload", payload)
        }.toString()
    }

    @Test
    fun `decodes the three messages of the content script`() {
        assertEquals(BridgeMessage.Hello("http://127.0.0.1:1234/text/index.html"), BridgeProtocol.decode("""{"kind":"hello","url":"http://127.0.0.1:1234/text/index.html","t":12.5}"""))

        val c = BridgeProtocol.decode(call(7, "textContainerUpgrade", """{"containerID":1,"echo":"t42"}""")) as BridgeMessage.Call
        assertEquals(7L, c.id)
        assertEquals("textContainerUpgrade", c.method)
        assertEquals("t42", c.data["echo"]?.jsonPrimitive?.content)

        val l = BridgeProtocol.decode("""{"kind":"layout","id":3,"layout":{"vw":384,"texts":[]}}""") as BridgeMessage.Layout
        assertEquals(3L, l.id)
        assertEquals(384, l.layout["vw"]?.jsonPrimitive?.int)

        assertEquals(BridgeMessage.Staged(12), BridgeProtocol.decode("""{"kind":"staged","id":12}"""))
        assertNull(BridgeProtocol.decode("""{"kind":"staged"}"""))
    }

    @Test
    fun `rejects what is not a bridge message`() {
        assertNull(BridgeProtocol.decode("not json"))
        assertNull(BridgeProtocol.decode("[1,2]"))
        assertNull(BridgeProtocol.decode("""{"kind":"other"}"""))
        assertNull(BridgeProtocol.decode("""{"kind":"call","payload":"{}"}"""))
        assertNull(BridgeProtocol.decode("""{"kind":"layout"}"""))
    }

    @Test
    fun `a call without a usable payload still gets an answer`() {
        val none = BridgeProtocol.decode("""{"kind":"call","id":1,"name":"x","payload":null}""") as BridgeMessage.Call
        assertEquals("", none.method)
        val broken = BridgeProtocol.decode("""{"kind":"call","id":2,"name":"x","payload":"{oops"}""") as BridgeMessage.Call
        assertEquals("", broken.method)
        assertEquals(JsonObject(emptyMap()), broken.data)
    }

    @Test
    fun `answers SDK calls like the EvenHub runtime and reports them`() {
        val events = ArrayList<BridgeEvent>()
        val bridge = ProbeBridge("text") { events += it }

        assertNull(bridge.receive("""{"kind":"hello","url":"u"}"""))
        val reply = Json.parseToJsonElement(bridge.receive(call(5, "createStartUpPageContainer"))!!).jsonObject
        assertEquals("reply", reply["kind"]?.jsonPrimitive?.content)
        assertEquals(5L, reply["id"]?.jsonPrimitive?.long)
        assertEquals(JsonPrimitive(0), reply["result"])

        val upgrade = Json.parseToJsonElement(bridge.receive(call(6, "textContainerUpgrade"))!!).jsonObject
        assertEquals(JsonPrimitive(true), upgrade["result"])
        val unknown = Json.parseToJsonElement(bridge.receive(call(8, "somethingElse"))!!).jsonObject
        assertEquals(JsonNull, unknown["result"])

        assertEquals(BridgeEvent.Hello("text", "u"), events[0])
        assertEquals(listOf("createStartUpPageContainer", "textContainerUpgrade", "somethingElse"), events.drop(1).map { (it as BridgeEvent.Called).method })
        assertTrue(events.all { it.app == "text" })
    }

    @Test
    fun `layout answers and painted stages are reported, not answered`() {
        val events = ArrayList<BridgeEvent>()
        val bridge = ProbeBridge("render") { events += it }
        assertNull(bridge.receive("""{"kind":"layout","id":9,"layout":{"vw":384}}"""))
        assertNull(bridge.receive("""{"kind":"staged","id":10}"""))
        val e = events[0] as BridgeEvent.LayoutReady
        assertEquals(9L, e.id)
        assertEquals("render", e.app)
        assertEquals(BridgeEvent.Staged("render", 10), events[1])
    }

    @Test
    fun `pushes are shaped like Even's host events`() {
        val m = Json.parseToJsonElement(BridgeProtocol.push("evenHubEvent", buildJsonObject { put("token", "t1") })).jsonObject
        assertEquals("push", m["kind"]?.jsonPrimitive?.content)
        val msg = m["msg"]!!.jsonObject
        assertEquals("listen_even_app_data", msg["type"]?.jsonPrimitive?.content)
        assertEquals("evenHubEvent", msg["method"]?.jsonPrimitive?.content)
        assertEquals("t1", msg["data"]!!.jsonObject["token"]?.jsonPrimitive?.content)

        val l = Json.parseToJsonElement(BridgeProtocol.layoutRequest(4)).jsonObject
        assertEquals("layout", l["kind"]?.jsonPrimitive?.content)
        assertEquals(4L, l["id"]?.jsonPrimitive?.long)

        val st = Json.parseToJsonElement(BridgeProtocol.stage(5, "hide-text")).jsonObject
        assertEquals("stage", st["kind"]?.jsonPrimitive?.content)
        assertEquals(5L, st["id"]?.jsonPrimitive?.long)
        assertEquals("hide-text", st["stage"]?.jsonPrimitive?.content)
    }
}

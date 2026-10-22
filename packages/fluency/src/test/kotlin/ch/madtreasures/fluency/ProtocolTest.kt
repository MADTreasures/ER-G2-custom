package ch.madtreasures.fluency

import ch.madtreasures.fluency.Protocol.Incoming
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The messages of `fluency-g2@1` (PLATTFORM.md §6), both ways, exactly as the spec shows them. */
class ProtocolTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `the app's messages`() {
        assertEquals(json("""{"t":"hello","proto":"fluency-g2@1","app":"1.0.0"}"""), json(Protocol.hello("1.0.0")))
        val request = Protocol.Request(Source.WATCH, "auto", "de-CH", speak = false, partials = true)
        assertEquals(
            json("""{"t":"start","n":1,"source":"watch","from":"auto","to":"de-CH","speak":false,"partials":true}"""),
            json(Protocol.start(request, 1)),
        )
        assertEquals(
            json("""{"t":"config","n":2,"source":"phone","from":"en","to":"de","speak":true,"partials":false}"""),
            json(Protocol.config(Protocol.Request(Source.PHONE, "en", "de", speak = true, partials = false), 2)),
        )
        assertEquals(json("""{"t":"stop","n":3}"""), json(Protocol.stop(3)))
    }

    @Test
    fun `Fluency's messages`() {
        assertEquals(
            Incoming.Welcome("fluency-g2@1", "1.9.0", listOf("de", "en")),
            Protocol.parse("""{"t":"welcome","proto":"fluency-g2@1","app":"1.9.0","languages":["de","en"]}"""),
        )
        assertEquals(Incoming.Welcome("fluency-g2@1", "", null), Protocol.parse("""{"t":"welcome","proto":"fluency-g2@1"}"""))
        assertEquals(
            Incoming.State(running = true, speech = true, note = "Lädt die Modelle …", re = 4),
            Protocol.parse("""{"t":"state","running":true,"speech":true,"note":"Lädt die Modelle …","re":4}"""),
        )
        assertEquals(Incoming.State(running = false), Protocol.parse("""{"t":"state","running":false}"""))
        assertEquals(
            Incoming.Utterance(17, "Where is the station?", "Wo ist der Bahnhof?", final = true, from = "en"),
            Protocol.parse("""{"t":"utt","id":17,"src":"Where is the station?","text":"Wo ist der Bahnhof?","final":true,"from":"en"}"""),
        )
        assertEquals(Incoming.Utterance(18, "", "", final = false, gone = true), Protocol.parse("""{"t":"utt","id":18,"gone":true}"""))
        assertEquals(
            Incoming.Error("no_model", "Kein Modell für Englisch → Japanisch"),
            Protocol.parse("""{"t":"error","code":"no_model","message":"Kein Modell für Englisch → Japanisch"}"""),
        )
    }

    @Test
    fun `unknown fields are ignored, unknown types and broken messages are none`() {
        assertEquals(Incoming.State(running = true), Protocol.parse("""{"t":"state","running":true,"neu":[1,2],"level":0.4}"""))
        assertNull(Protocol.parse("""{"t":"bye"}"""))
        assertNull(Protocol.parse("""{"running":true}"""))
        assertNull(Protocol.parse("not json"))
        assertNull(Protocol.parse("[1,2,3]"))
        assertNull(Protocol.parse(""))
        // Required fields missing or of the wrong kind.
        assertNull(Protocol.parse("""{"t":"welcome","app":"1.9.0"}"""))
        assertNull(Protocol.parse("""{"t":"state","running":"yes"}"""))
        assertNull(Protocol.parse("""{"t":"utt","id":"17","src":"x","text":"y"}"""))
        assertNull(Protocol.parse("""{"t":"utt","src":"x","text":"y"}"""))
        // An error without its fields still says something.
        assertEquals(Incoming.Error("error", "Fehler in Fluency"), Protocol.parse("""{"t":"error"}"""))
    }

    @Test
    fun `texts with quotes, line breaks and emoji survive the trip`() {
        val text = "Er sagte: \"Grüezi\"\nund ging 😊"
        val parsed = Protocol.parse(
            kotlinx.serialization.json.buildJsonObject {
                put("t", JsonPrimitive("utt"))
                put("id", JsonPrimitive(1))
                put("src", JsonPrimitive(text))
                put("text", JsonPrimitive(text))
            }.toString(),
        ) as Incoming.Utterance
        assertEquals(text, parsed.source)
        assertEquals(text, parsed.text)
    }
}

package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON forms of 02 §4 and §6 and 04 §5.3, both ways. */
class AppJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val events = listOf(
        AppEvent.Start, AppEvent.Visible, AppEvent.Hidden, AppEvent.Stop,
        AppEvent.Click("p_start", "radio"),
        AppEvent.Toggle("p_set", "leise", true),
        AppEvent.Check("p_liste", "items", 2, true),
        AppEvent.Navigate("p_start", "p_liste", "zur_liste"),
        AppEvent.Back("p_liste"),
        AppEvent.Menu("sortieren"),
        AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT),
        AppEvent.Timer("tick"),
        AppEvent.Imu(0.02f, -0.98f, 0.11f, 1727600000123),
        AppEvent.Compass(213.5f, 1727600000123),
        AppEvent.Location(47.37, 8.54, 12f, 1727600000123),
        AppEvent.Error("patch", "unknown_block", "Unbekannter Baustein"),
    )

    @Test
    fun `every event survives the round trip`() {
        for (event in events) assertEquals(event, AppJson.decodeEvent(AppJson.encodeEvent(event)))
    }

    @Test
    fun `events have the documented shape`() {
        assertEquals(json("""{ "kind": "click", "page": "p_start", "block": "radio" }"""), AppJson.encodeEvent(AppEvent.Click("p_start", "radio")))
        assertEquals(json("""{ "kind": "start" }"""), AppJson.encodeEvent(AppEvent.Start))
        assertEquals(
            json("""{ "kind": "gesture", "gesture": "scrollDown", "source": "right" }"""),
            AppJson.encodeEvent(AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT)),
        )
    }

    @Test
    fun `sensor events are told apart by the sensor field`() {
        assertEquals(
            AppEvent.Compass(213.5f, 1727600000123),
            AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "compass", "heading": 213.5, "t": 1727600000123 }""")),
        )
        assertEquals(
            AppEvent.Imu(0.02f, -0.98f, 0.11f, 1727600000123),
            AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "imu", "x": 0.02, "y": -0.98, "z": 0.11, "t": 1727600000123 }""")),
        )
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "light", "t": 1 }""")) }
    }

    @Test
    fun `an unknown kind is ignored, a broken event is an error`() {
        assertNull(AppJson.decodeEvent(json("""{ "kind": "teleport", "to": "mars" }""")))
        val missing = assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "click", "page": "p" }""")) }
        assertEquals(CommandException.BAD_VALUE, missing.code)
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""[1, 2]""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "toggle", "page": "p", "block": "b", "on": "ja" }""")) }
    }

    @Test
    fun `audio never travels as JSON`() {
        assertThrows(Exception::class.java) { AppJson.encodeEvent(AppEvent.Audio(ShortArray(800), 1)) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "audio", "seq": 1, "pcm": "" }""")) }
    }

    private val page = Page(
        "p_status", "PC-Status",
        listOf(
            Block.Heading("h", "Status", Align.CENTER, HeadingSize.GROSS),
            Block.Text("t", "Zwei\nZeilen"),
            Block.Button("b", "Weiter", target = "p_next", action = "öffnet"),
            Block.Button("zurueck", "Zurück", target = Block.BACK),
            Block.List("l", listOf(ListItem("Milch", true), ListItem("Brot")), ListStyle.CHECKS),
            Block.Toggle("s", "Leise", on = true),
            Block.Value("cpu", "Prozessor", "12 %"),
            Block.Progress("last", "Last", 42),
            Block.Divider("d"),
            Block.Image("bild", "data:image/png;base64,AAAA", 120, 80, Align.LEFT),
            Block.Image("karte", null, 576, 288, bleed = true),
        ),
        statusBar = false,
        notes = "Notiz",
    )

    private val commands = listOf(
        AppCommand.DefinePages(listOf(page)),
        AppCommand.Show("p_status"),
        AppCommand.Replace("p_status"),
        AppCommand.Patch("p_status", buildJsonObject { put("cpu", buildJsonObject { put("value", "12 %") }) }),
        AppCommand.SetBlocks("p_status", page.blocks.take(3)),
        AppCommand.Toast("Gespeichert", 2000),
        AppCommand.Vibrate(Vibration.DOUBLE),
        AppCommand.Menu(listOf(MenuItem("sortieren", "Sortieren"))),
        AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60))),
        AppCommand.Subscribe(Sensor.IMU, 100),
        AppCommand.Unsubscribe(Sensor.IMU),
        AppCommand.Audio(true),
        AppCommand.Close,
    )

    @Test
    fun `every command survives the round trip`() {
        for (command in commands) assertEquals(command, AppJson.decodeCommand(AppJson.encodeCommand(command)))
    }

    @Test
    fun `commands read as written in the protocol chapter`() {
        assertEquals(
            AppCommand.Patch("p_status", json("""{ "cpu": { "value": "12 %" } }""") as JsonObject),
            AppJson.decodeCommand(json("""{ "c": "patch", "page": "p_status", "changes": { "cpu": { "value": "12 %" } } }""")),
        )
        assertEquals(
            AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60), BuzzNote(1320, 50, 120))),
            AppJson.decodeCommand(json("""{ "c": "buzz", "notes": [[880, 50, 120], [0, 0, 60], [1320, 50, 120]] }""")),
        )
        assertEquals(AppCommand.Toast("Gespeichert", 2000), AppJson.decodeCommand(json("""{ "c": "toast", "text": "Gespeichert" }""")))
        assertEquals(AppCommand.Vibrate(Vibration.TICK), AppJson.decodeCommand(json("""{ "c": "vibrate" }""")))
        assertEquals(AppCommand.Close, AppJson.decodeCommand(json("""{ "c": "close" }""")))
    }

    @Test
    fun `definePages also takes a whole Baukasten project`() {
        val command = AppJson.decodeCommand(
            json("""{ "c": "definePages", "project": { "format": "g2-baukasten@1", "pages": [ { "id": "p", "name": "P", "blocks": [ { "id": "x", "type": "divider" } ] } ] } }"""),
        ) as AppCommand.DefinePages
        assertEquals(listOf(Page("p", "P", listOf(Block.Divider("x")))), command.pages)
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "definePages", "project": { "format": "anderes@1", "pages": [] } }""")) }
    }

    @Test
    fun `broken commands are errors, never dropped`() {
        for (text in listOf(
            """{ "c": "fly" }""",
            """{ "c": "show" }""",
            """{ "c": "patch", "page": "p", "changes": [] }""",
            """{ "c": "buzz", "notes": [[880, 50]] }""",
            """{ "c": "subscribe", "sensor": "light" }""",
            """{ "c": "vibrate", "pattern": "brumm" }""",
            """{ "page": "p" }""",
        )) {
            val e = assertThrows(text, CommandException::class.java) { AppJson.decodeCommand(json(text)) }
            assertEquals(CommandException.BAD_VALUE, e.code)
        }
    }

    @Test
    fun `pages and blocks keep every field`() {
        assertEquals(page, AppJson.decodePage(AppJson.encodePage(page)))
        val button = AppJson.encodeBlock(Block.Button("b", "Weiter"))
        assertEquals(JsonPrimitive("button"), button["type"])
        assertTrue(button.containsKey("target"))
    }

    @Test
    fun `missing optional fields of a block get their defaults`() {
        assertEquals(Block.Heading("h", "", Align.LEFT, HeadingSize.NORMAL), AppJson.decodeBlock(json("""{ "id": "h", "type": "heading" }""")))
        assertEquals(Block.List("l", listOf(ListItem("a"), ListItem("b", true))), AppJson.decodeBlock(json("""{ "id": "l", "type": "list", "items": ["a", { "text": "b", "done": true }] }""")))
        assertEquals(Block.Image("i", null, 10, 20), AppJson.decodeBlock(json("""{ "id": "i", "type": "image", "w": 10, "h": 20 }""")))
    }

    @Test
    fun `wrong blocks are errors`() {
        for (text in listOf(
            """{ "id": "x", "type": "karussell" }""",
            """{ "id": "mit leerzeichen", "type": "divider" }""",
            """{ "type": "divider" }""",
            """{ "id": "h", "type": "heading", "align": "right" }""",
            """{ "id": "i", "type": "image", "w": 10 }""",
            """{ "id": "l", "type": "list", "items": "Milch" }""",
        )) {
            assertThrows(text, CommandException::class.java) { AppJson.decodeBlock(json(text)) }
        }
    }

    @Test
    fun `the serializers speak the same JSON`() {
        val text = Json.encodeToString(AppEventSerializer, AppEvent.Check("p", "l", 1, false))
        assertEquals(json("""{ "kind": "check", "page": "p", "block": "l", "index": 1, "done": false }"""), json(text))
        assertEquals(AppEvent.Timer("tick"), Json.decodeFromString(AppEventSerializer, """{ "kind": "timer", "tag": "tick" }"""))
        assertEquals(AppCommand.Show("p"), Json.decodeFromString(AppCommandSerializer, Json.encodeToString(AppCommandSerializer, AppCommand.Show("p"))))
    }
}

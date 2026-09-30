package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The JSON forms of 02 §4 and §6, and the Baukasten import. */
class AppJsonTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `every event survives the round trip in the form of 02 §6_1`() {
        val events = listOf(
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
            AppEvent.Error("patch", "unknown_block", "…"),
        )
        for (e in events) assertEquals(e, AppJson.decodeEvent(AppJson.encodeEvent(e)!!))
        assertEquals(
            obj("""{"kind":"check","page":"p_liste","block":"items","index":2,"done":true}"""),
            AppJson.encodeEvent(AppEvent.Check("p_liste", "items", 2, true)),
        )
        assertEquals(
            obj("""{"kind":"sensor","sensor":"compass","heading":213.5,"t":1727600000123}"""),
            AppJson.encodeEvent(AppEvent.Compass(213.5f, 1727600000123)),
        )
        assertNull(AppJson.encodeEvent(AppEvent.Audio(ShortArray(800), 17)))
    }

    @Test
    fun `unknown event kinds are ignored, so the platform can add new ones`() {
        assertNull(AppJson.decodeEvent(obj("""{"kind":"teleport","to":"mars"}""")))
        assertNull(AppJson.decodeEvent(obj("""{"kind":"sensor","sensor":"light","lux":3}""")))
        assertEquals(
            AppEvent.Gesture(GestureKind.SWIPE_LEFT, InputSource.WATCH),
            AppJson.decodeEvent(obj("""{"kind":"gesture","gesture":"swipeLeft","source":"watch","extra":1}""")),
        )
    }

    @Test
    fun `every command survives the round trip`() {
        val commands = listOf(
            AppCommand.DefinePages(listOf(Page("p_status", "PC-Status", blocks = listOf(Block.Value("cpu", "Prozessor", "–"))))),
            AppCommand.Show("p_status"),
            AppCommand.Replace("p_status"),
            AppCommand.Patch("p_status", PatchBuilder().apply { value("cpu", "12 %") }.build()),
            AppCommand.SetBlocks("p_liste", listOf(Block.List("items", ListStyle.CHECKS, listOf(ListItem("Milch", true))))),
            AppCommand.Toast("Gespeichert", 2000),
            AppCommand.Vibrate(Vibration.DOUBLE),
            AppCommand.Menu(listOf(MenuItem("sortieren", "Sortieren"))),
            AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60))),
            AppCommand.Timer("tick", 2000, true),
            AppCommand.CancelTimer("tick"),
            AppCommand.Subscribe(Sensor.IMU, 100),
            AppCommand.Unsubscribe(Sensor.IMU),
            AppCommand.Audio(true),
            AppCommand.Close,
        )
        for (c in commands) assertEquals(c, AppJson.decodeCommand(AppJson.encodeCommand(c)))
        assertEquals(
            obj("""{"c":"buzz","notes":[[880,50,120],[0,0,60]]}"""),
            AppJson.encodeCommand(AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60)))),
        )
        assertEquals(
            obj("""{"c":"patch","page":"p_status","changes":{"cpu":{"value":"12 %"}}}"""),
            AppJson.encodeCommand(AppCommand.Patch("p_status", PatchBuilder().apply { value("cpu", "12 %") }.build())),
        )
    }

    @Test
    fun `malformed commands are bad values`() {
        for (bad in listOf("""{"c":"fly"}""", """{"c":"show"}""", """{"c":"buzz","notes":[[1,2]]}""", """{"c":"vibrate","pattern":"wild"}""")) {
            val e = assertThrows(CommandException::class.java) { AppJson.decodeCommand(obj(bad)) }
            assertEquals(CommandError.BAD_VALUE, e.error)
        }
    }

    @Test
    fun `the Baukasten example imports unchanged`() {
        val file = File("../designs/beispiel.json")
        val warnings = mutableListOf<String>()
        val project = BaukastenProject.parse(file.readText()) { warnings += it }
        assertEquals("p_start", project.start)
        assertTrue(warnings.toString(), warnings.isEmpty())
        assertEquals(Json.parseToJsonElement(file.readText()).jsonObject["pages"], project.toJson()["pages"])
    }

    @Test
    fun `the shopping list asset is a valid project`() {
        val project = BaukastenProject.parse(File("src/main/assets/apps/ch.madtreasures.einkauf/ui.json").readText())
        assertEquals(listOf("p_liste", "p_hilfe"), project.pages.map { it.id })
        assertEquals(Block.BACK, (project.page("p_hilfe")!!.block("hilfe_zurueck") as Block.Button).target)
    }

    @Test
    fun `normalize completes, cleans and reports like the Baukasten`() {
        val warnings = mutableListOf<String>()
        val project = BaukastenProject.parse(
            """{"name":"  ","pages":[
                {"id":"p_a","blocks":[
                    {"type":"heading","text":"Hi"},
                    {"id":"dup","type":"button","text":"Weiter","target":"p_nirgends"},
                    {"id":"dup","type":"button","text":"Zurück","target":"@back"},
                    {"id":"a b!","type":"toggle","text":"Ton","on":1},
                    {"id":"p","type":"progress","text":"Akku","value":"80,4 %"},
                    {"id":"img","type":"image","src":"asset:karte.png","w":900,"h":20},
                    {"id":"l","type":"list","style":"checks","items":["Milch",{"text":"Brot","done":true},null]},
                    {"type":"sparkles"}
                ]}]}""",
        ) { warnings += it }
        assertEquals("Mein Brillen-UI", project.name)
        val page = project.pages.single()
        assertEquals("Seite 1", page.name)
        assertEquals(listOf("b_1", "dup", "b_2", "a_b_", "p", "img", "l"), page.blocks.map { it.id })
        assertEquals(null, (page.blocks[1] as Block.Button).target)
        assertEquals(Block.BACK, (page.blocks[2] as Block.Button).target)
        assertEquals(true, (page.blocks[3] as Block.Toggle).on)
        assertEquals(80, (page.blocks[4] as Block.Progress).value)
        assertEquals(Block.Image("img", "asset:karte.png", Block.Image.MAX_W, 20), page.blocks[5])
        assertEquals(listOf(ListItem("Milch"), ListItem("Brot", true)), (page.blocks[6] as Block.List).items)
        assertEquals(4, warnings.size)
        assertTrue(warnings.any { "„dup“ doppelt" in it })
        assertTrue(warnings.any { "„p_nirgends“ gibt es nicht" in it })
    }

    @Test
    fun `foreign formats and empty projects are refused with a German message`() {
        val format = assertThrows(IllegalArgumentException::class.java) { BaukastenProject.parse("""{"format":"faceclaw-edit@1","pages":[]}""") }
        assertTrue(format.message!!.startsWith("Unbekanntes Format"))
        val empty = assertThrows(IllegalArgumentException::class.java) { BaukastenProject.parse("""{"pages":[]}""") }
        assertTrue(empty.message!!.startsWith("Keine Seiten gefunden"))
        assertThrows(IllegalArgumentException::class.java) { BaukastenProject.parse("{kein json") }
    }

    @Test
    fun `manifests check their fields`() {
        AppManifest("ch.madtreasures.stoppuhr", "Stoppuhr", "1.0.0")
        assertThrows(IllegalArgumentException::class.java) { AppManifest("Stoppuhr", "Stoppuhr", "1.0.0") }
        assertThrows(IllegalArgumentException::class.java) { AppManifest("ch.x.y", "Ein viel zu langer Name", "1.0.0") }
        assertThrows(IllegalArgumentException::class.java) { AppManifest("ch.x.y", "Name", "1.0") }
    }
}

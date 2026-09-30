package ch.madtreasures.g2watch.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reading Baukasten projects the way the Baukasten's own `normalize` does, plus `@back` (M0). */
class BaukastenProjectTest {

    @Test
    fun `the example project of the repo imports completely`() {
        // Unit tests run in the module directory.
        val project = BaukastenProject.parse(File("../designs/beispiel.json").readText())
        assertEquals("p_start", project.start)
        assertEquals(listOf("p_start", "p_einkauf", "p_einst"), project.pages.map { it.id })
        val start = project.page("p_start")!!
        assertEquals(Block.Button("b_einkauf", "Einkaufsliste", target = "p_einkauf"), start.block("b_einkauf"))
        val list = project.page("p_einkauf")!!.block("b_ek_liste") as Block.List
        assertEquals(ListStyle.CHECKS, list.style)
        assertEquals(ListItem("Milch", done = true), list.items.first())
        assertEquals(Block.Progress("b_hell", "Helligkeit", 60), project.page("p_einst")!!.block("b_hell"))
    }

    @Test
    fun `a project survives the round trip through its JSON`() {
        val project = BaukastenProject.parse(File("../designs/beispiel.json").readText())
        assertEquals(project, BaukastenProject.normalize(project.toJson()))
    }

    @Test
    fun `ids stay unique in the whole project, pages and blocks alike`() {
        val project = BaukastenProject.parse(
            """{ "pages": [
                 { "id": "p", "name": "Eins", "blocks": [ { "id": "p", "type": "divider" }, { "id": "x", "type": "divider" } ] },
                 { "id": "x", "name": "Zwei", "blocks": [ { "id": "", "type": "divider" }, { "id": "mit Leerzeichen!", "type": "divider" } ] }
               ] }""",
        )
        val ids = project.pages.flatMap { page -> listOf(page.id) + page.blocks.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Block.ID.matches(it) })
        assertEquals("p", project.pages[0].id)
        assertEquals("x", project.pages[0].blocks[1].id)
        assertEquals("mit_Leerzeichen_", project.pages[1].blocks[1].id)
    }

    @Test
    fun `back targets stay, targets to missing pages go`() {
        val project = BaukastenProject.parse(
            """{ "format": "g2-baukasten@1", "start": "fehlt", "pages": [ { "id": "p", "name": "P", "blocks": [
                 { "id": "zurueck", "type": "button", "text": "Zurück", "target": "@back" },
                 { "id": "weg", "type": "button", "text": "Weg", "target": "p_gibtsnicht" },
                 { "id": "hier", "type": "button", "text": "Hier", "target": "p" } ] } ] }""",
        )
        val page = project.pages.single()
        assertEquals(Block.BACK, (page.block("zurueck") as Block.Button).target)
        assertNull((page.block("weg") as Block.Button).target)
        assertEquals("p", (page.block("hier") as Block.Button).target)
        assertEquals("p", project.start)
    }

    @Test
    fun `hand-written JSON is completed like in the Baukasten`() {
        val project = BaukastenProject.parse(
            """{ "blocks": [
                 { "type": "heading", "text": "Titel", "size": "riesig" },
                 { "type": "list", "items": ["Milch", 3, { "text": "Brot", "done": 1 }, null] },
                 { "type": "progress", "text": "Akku", "value": "80,4 %" },
                 { "type": "toggle", "text": "An", "on": "ja" },
                 { "type": "karussell" },
                 { "type": "image", "src": "asset:bild.png", "w": 900, "h": 40, "bleed": true } ] }""",
        )
        val page = project.pages.single()
        assertEquals("Mein Brillen-UI", project.name)
        assertEquals("Seite 1", page.name)
        assertTrue(page.statusBar)
        assertEquals(5, page.blocks.size)
        assertEquals(HeadingSize.NORMAL, (page.blocks[0] as Block.Heading).size)
        assertEquals(listOf(ListItem("Milch"), ListItem("3"), ListItem("Brot", true)), (page.blocks[1] as Block.List).items)
        assertEquals(80, (page.blocks[2] as Block.Progress).value)
        assertEquals(true, (page.blocks[3] as Block.Toggle).on)
        val image = page.blocks[4] as Block.Image
        assertEquals(576, image.w)
        assertTrue(image.bleed)
    }

    @Test
    fun `a page's input mode is kept, an unknown one means the app's`() {
        val project = BaukastenProject.parse(
            """{ "pages": [ { "id": "p_video", "input": "gestures", "blocks": [] }, { "id": "p_liste", "input": "maus", "blocks": [] }, { "id": "p_x" } ] }""",
        )
        assertEquals(listOf(InputMode.GESTURES, null, null), project.pages.map { it.input })
        assertEquals(project, BaukastenProject.normalize(project.toJson()))
    }

    @Test
    fun `what cannot be read says why, in German`() {
        val cases = mapOf(
            "" to "leer",
            "{ nicht json" to "kein gültiges JSON",
            "[1]" to "JSON-Objekt",
            """{ "format": "faceclaw-edit@2", "pages": [] }""" to "alten Designer",
            """{ "format": "anderes@1", "pages": [] }""" to "Unbekanntes Format",
            """{ "pages": [] }""" to "Keine Seiten",
            """{ "pages": [1, 2] }""" to "Keine brauchbare Seite",
        )
        for ((text, message) in cases) {
            val e = assertThrows(text, BaukastenFormatException::class.java) { BaukastenProject.parse(text) }
            assertTrue("${e.message} should mention $message", e.message!!.contains(message))
        }
    }

    @Test
    fun `the shopping list asset is a valid project`() {
        val project = BaukastenProject.parse(File("src/main/assets/apps/ch.madtreasures.einkauf/ui.json").readText())
        assertEquals("p_liste", project.start)
        assertEquals(listOf("offen", "items", "leeren"), project.page("p_liste")!!.blocks.map { it.id })
    }
}

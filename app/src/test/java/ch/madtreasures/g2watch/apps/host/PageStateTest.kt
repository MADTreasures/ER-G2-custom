package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStateTest {
    private val state = PageState()

    private val start = Page(
        "p_start", "Start",
        listOf(
            Block.Heading("titel", "Hallo"),
            Block.Button("weiter", "Weiter", target = "p_liste"),
            Block.Toggle("leise", "Leise"),
            Block.Value("akku", "Akku", "80 %"),
            Block.Progress("last", "Last", 10),
            Block.Image("bild", null, 100, 50),
        ),
    )
    private val liste = Page("p_liste", "Liste", listOf(Block.List("items", listOf(ListItem("Milch"), ListItem("Brot")), ListStyle.CHECKS)))

    private fun patch(page: String, changes: PatchBuilder.() -> Unit) = state.patch(page, PatchBuilder().apply(changes).build())

    private fun refused(code: String, block: () -> Unit) {
        val e = assertThrows(CommandException::class.java) { block() }
        assertEquals(e.message, code, e.code)
    }

    @Test
    fun `show builds the history, replace does not, back walks it`() {
        state.define(listOf(start, liste))
        assertNull(state.current)
        state.show("p_start")
        state.show("p_liste")
        assertEquals(listOf("p_start", "p_liste"), state.historyIds)
        state.replace("p_start")
        assertEquals(listOf("p_start", "p_start"), state.historyIds)
        assertTrue(state.back())
        assertEquals("p_start", state.current?.id)
        assertFalse(state.back())
        refused(CommandException.UNKNOWN_PAGE) { state.show("p_fehlt") }
    }

    @Test
    fun `ids are unique across pages and blocks`() {
        state.define(listOf(start))
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("p_neu", "Neu", listOf(Block.Divider("titel"))))) }
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("titel", "Neu", emptyList()))) }
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("a", "A", listOf(Block.Divider("x"), Block.Divider("x"))))) }
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("a b", "A", emptyList()))) }
        // Nothing of a refused definition stays.
        assertNull(state.page("a"))
        // A page with the same id replaces the old one, which frees its ids.
        state.define(listOf(Page("p_start", "Neu", listOf(Block.Divider("weiter")))))
        state.define(listOf(Page("p_zwei", "Zwei", listOf(Block.Divider("titel")))))
        assertEquals("p_zwei", state.pageOf("titel"))
    }

    @Test
    fun `patch changes only the named fields`() {
        state.define(listOf(start, liste))
        patch("p_start") {
            text("titel", "Guten Tag")
            align("titel", Align.CENTER)
            on("leise", true)
            value("akku", "79 %")
            progress("last", 55)
            target("weiter", Block.BACK)
            image("bild", "asset:x.png", w = 60)
        }
        val page = state.page("p_start")!!
        assertEquals(Block.Heading("titel", "Guten Tag", Align.CENTER), page.block("titel"))
        assertEquals(true, (page.block("leise") as Block.Toggle).on)
        assertEquals("79 %", (page.block("akku") as Block.Value).value)
        assertEquals(55, (page.block("last") as Block.Progress).value)
        assertEquals(Block.BACK, (page.block("weiter") as Block.Button).target)
        assertEquals(Block.Image("bild", "asset:x.png", 60, 50), page.block("bild"))

        patch("p_liste") { item("items", 1, done = true) }
        assertEquals(listOf(ListItem("Milch"), ListItem("Brot", true)), (state.block("items") as Block.List).items)
        patch("p_liste") { items("items", listOf(ListItem("Käse"))) }
        assertEquals(listOf(ListItem("Käse")), (state.block("items") as Block.List).items)
    }

    @Test
    fun `wrong patches are refused and change nothing`() {
        state.define(listOf(start, liste))
        refused(CommandException.UNKNOWN_PAGE) { patch("p_fehlt") { text("titel", "x") } }
        refused(CommandException.UNKNOWN_BLOCK) { patch("p_start") { text("fehlt", "x") } }
        // The block exists, but on another page.
        refused(CommandException.UNKNOWN_BLOCK) { patch("p_start") { item("items", 0, done = true) } }
        refused(CommandException.BAD_VALUE) { patch("p_start") { on("titel", true) } }
        refused(CommandException.BAD_VALUE) { patch("p_start") { progress("last", 101) } }
        refused(CommandException.BAD_VALUE) { patch("p_liste") { item("items", 5, done = true) } }
        refused(CommandException.BAD_VALUE) { patch("p_start") { image("bild", "http://example.com/x.png") } }
        refused(CommandException.BAD_VALUE) { patch("p_start") { image("bild", null, w = 600) } }
        // Also the good parts of a refused patch did not happen.
        refused(CommandException.BAD_VALUE) {
            patch("p_start") {
                text("titel", "Neu")
                progress("last", -1)
            }
        }
        assertEquals("Hallo", (state.block("titel") as Block.Heading).text)
    }

    @Test
    fun `setBlocks replaces a page's blocks and keeps ids unique`() {
        state.define(listOf(start, liste))
        state.setBlocks("p_liste", listOf(Block.Text("t", "leer"), Block.Divider("items")))
        assertEquals(listOf("t", "items"), state.page("p_liste")!!.blocks.map { it.id })
        refused(CommandException.BAD_VALUE) { state.setBlocks("p_liste", listOf(Block.Divider("titel"))) }
        refused(CommandException.BAD_VALUE) { state.setBlocks("p_liste", listOf(Block.Divider("p_start"))) }
        refused(CommandException.UNKNOWN_PAGE) { state.setBlocks("p_fehlt", emptyList()) }
    }

    @Test
    fun `images must fit the app area`() {
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("p", "P", listOf(Block.Image("i", null, 545, 10))))) }
        refused(CommandException.BAD_VALUE) { state.define(listOf(Page("p", "P", listOf(Block.Image("i", null, 10, 261))))) }
        refused(CommandException.BAD_VALUE) {
            state.define(listOf(Page("p", "P", listOf(Block.Image("i", "data:image/png;base64," + "A".repeat(50_000), 10, 10)))))
        }
        state.define(listOf(Page("p", "P", listOf(Block.Image("i", null, 576, 288, bleed = true)), statusBar = false)))
    }

    @Test
    fun `resetHistory undoes navigation`() {
        state.define(listOf(start, liste))
        state.show("p_start")
        state.show("p_liste")
        state.resetHistory(listOf("p_start"))
        assertEquals("p_start", state.current?.id)
    }
}

package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sizes after 02 §4.2, with the stub font (every character is half as wide as the font is high). */
class PageLayoutTest {
    private val text = FakeText()

    private fun layout(vararg blocks: Block, statusBar: Boolean = true) = PageLayout.of(Page("p", "P", blocks.toList(), statusBar), text)

    @Test
    fun `blocks stack with margins and gaps`() {
        val l = layout(
            Block.Heading("h", "Titel"),
            Block.Heading("g", "Groß", size = HeadingSize.GROSS),
            Block.Text("t", "a\nb"),
            Block.Button("b", "Knopf"),
            Block.List("l", listOf(ListItem("x"), ListItem("y"), ListItem("z"))),
            Block.Toggle("s", "An"),
            Block.Value("v", "Wert", "1"),
            Block.Progress("p2", "Last", 5),
            Block.Divider("d"),
        )
        assertEquals(listOf(35, 45, 54, 40, 90, 36, 36, 32, 14), l.boxes.map { it.rect.h })
        assertEquals(Rect(16, 8, 544, 35), l.boxes[0].rect)
        assertEquals(8 + 35 + 8, l.boxes[1].rect.y)
        val sum = l.boxes.sumOf { it.rect.h } + 8 * (l.boxes.size - 1)
        assertEquals(8 + sum + 8, l.contentHeight)
        assertEquals(576, l.width)
        assertEquals(260, l.height)
    }

    @Test
    fun `long text wraps at the margins, long words are cut`() {
        // 22 px stub font: 11 px per character, 544 px per line = 49 characters.
        val l = layout(Block.Text("t", "wort ".repeat(20).trim() + " " + "x".repeat(60)))
        val lines = l.boxes.single().lines
        assertTrue(lines.all { it.length * 11 <= 544 })
        assertEquals("wort ".repeat(20).trim() + " " + "x".repeat(60), lines.joinToString(" ").replace("x ", "x"))
    }

    @Test
    fun `buttons, toggles and checklist rows are focusable, in reading order`() {
        val l = layout(
            Block.Text("t", "Text"),
            Block.Button("b", "Knopf"),
            Block.List("bullets", listOf(ListItem("a"))),
            Block.List("checks", listOf(ListItem("a"), ListItem("b")), ListStyle.CHECKS),
            Block.Toggle("s", "An"),
            Block.Value("v", "Wert", "1"),
        )
        assertEquals(
            listOf(FocusTarget("b"), FocusTarget("checks", 0), FocusTarget("checks", 1), FocusTarget("s")),
            l.focusables.map { it.target },
        )
        val row1 = l.focusable(FocusTarget("checks", 1))!!.rect
        assertEquals(30, row1.h)
        assertEquals(l.box("checks")!!.rect.y + 30, row1.y)
    }

    @Test
    fun `hits take the scroll into account`() {
        val l = layout(*(1..10).map { Block.Button("k$it", "Knopf $it") }.toTypedArray())
        val k1 = l.focusable(FocusTarget("k1"))!!.rect
        assertEquals(FocusTarget("k1"), l.hit(100, k1.y + 5, 0)?.target)
        assertEquals(FocusTarget("k2"), l.hit(100, k1.y + 5, 48)?.target)
        assertNull(l.hit(5, k1.y + 5, 0))
        assertNull(l.hit(100, 300, 0))
        assertEquals(10 * 48 - 8 + 16 - 260, l.maxScroll)
    }

    @Test
    fun `a borderless picture fills a full-screen page edge to edge`() {
        val l = layout(Block.Image("i", null, 576, 288, bleed = true), statusBar = false)
        assertEquals(Rect(0, 0, 576, 288), l.boxes.single().rect)
        assertEquals(288, l.contentHeight)
        assertEquals(0, l.maxScroll)
        val small = layout(Block.Image("i", null, 100, 50))
        assertEquals(Rect(16 + (544 - 100) / 2, 8, 100, 50), small.boxes.single().rect)
    }

    @Test
    fun `the renderer draws focus brighter than the rest`() {
        val page = Page("p", "P", listOf(Block.Button("a", "A"), Block.Button("b", "B")))
        val l = PageLayout.of(page, text)
        val raster = GrayRaster(l.width, l.height)
        PageRenderer(text).render(raster, l, PageLook(focus = FocusTarget("b")))
        val a = l.box("a")!!.rect
        val b = l.box("b")!!.rect
        // The outline of the focused button (row through the middle, left edge) is at full brightness.
        fun edge(r: Rect) = (0 until 4).maxOf { raster[r.x + it, r.y + r.h / 2] }
        assertEquals(Levels.STRONG, edge(b))
        assertTrue(edge(a) in Levels.BORDER - 16..Levels.BORDER)
    }

    @Test
    fun `levels are drawn so the glasses round them back exactly`() {
        for (level in 0..15) assertEquals(level, minOf(15, (Levels.of(level) + 8) shr 4))
    }
}

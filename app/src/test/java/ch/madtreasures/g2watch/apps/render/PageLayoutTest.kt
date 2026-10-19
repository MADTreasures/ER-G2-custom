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

    private fun grid(vararg blocks: Block) = PageLayout.of(Page("p_apps", "Apps", blocks.toList()), text, tiles = true)

    private fun apps(n: Int) = (0 until n).map { Block.Button("app$it", "App $it") }.toTypedArray()

    @Test
    fun `the launcher grid has four cells per row, in reading order and without gaps`() {
        val l = grid(*apps(10))
        assertTrue(l.tiles)
        assertEquals((0 until 10).map { FocusTarget("app$it") }, l.focusables.map { it.target })
        assertTrue(l.boxes.all { it.tile })
        assertEquals(Rect(16, 8, 136, 120), l.focusable(FocusTarget("app0"))!!.rect)
        assertEquals(Rect(16 + 3 * 136, 8, 136, 120), l.focusable(FocusTarget("app3"))!!.rect)
        assertEquals(Rect(16, 8 + 120, 136, 120), l.focusable(FocusTarget("app4"))!!.rect)
        assertEquals(Rect(16 + 136, 8 + 240, 136, 120), l.focusable(FocusTarget("app9"))!!.rect)
        assertEquals(8 + 3 * 120 + 8, l.contentHeight)
        // Neighbouring cells touch: every pixel of the grid belongs to one cell.
        assertEquals(FocusTarget("app0"), l.hit(16 + 135, 8 + 119, 0)?.target)
        assertEquals(FocusTarget("app1"), l.hit(16 + 136, 8, 0)?.target)
        assertEquals(FocusTarget("app5"), l.hit(16 + 136, 8 + 120, 0)?.target)
        assertEquals(FocusTarget("app8"), l.hit(20, 8 + 240 - 116, 116)?.target)
        assertNull(l.hit(15, 50, 0))
        assertNull(l.hit(16 + 544, 50, 0))
    }

    @Test
    fun `eight apps fit without scrolling, headings and text end a row`() {
        val eight = grid(*apps(8))
        assertEquals(8 + 2 * 120 + 8, eight.contentHeight)
        assertEquals(0, eight.maxScroll)

        val l = grid(
            Block.Heading("h1", "Auf der Uhr"), Block.Button("a", "A"), Block.Button("b", "B"),
            Block.Heading("h2", "Even Hub"), Block.Button("c", "C"), Block.Text("t", "Ende"),
        )
        assertEquals(Rect(16, 8, 544, 35), l.box("h1")!!.rect)
        assertEquals(Rect(16, 51, 136, 120), l.box("a")!!.rect)
        assertEquals(Rect(16 + 136, 51, 136, 120), l.box("b")!!.rect)
        assertEquals(51 + 120 + 8, l.box("h2")!!.rect.y)
        assertEquals(Rect(16, 179 + 35 + 8, 136, 120), l.box("c")!!.rect)
        assertEquals(222 + 120 + 8, l.box("t")!!.rect.y)
        // The same page for an app: buttons stay full-width rows.
        val rows = PageLayout.of(l.page, text)
        assertEquals(Rect(16, 51, 544, 40), rows.box("a")!!.rect)
        assertTrue(rows.boxes.none { it.tile })
    }

    @Test
    fun `a launcher cell blends its icon over the focus fill and marks a running app`() {
        val l = grid(Block.Button("a", "Eins"), Block.Button("b", "Zwei"), Block.Button("c", "Drei"))
        val icon = GrayRaster(64, 64).also { it.fillRect(16, 16, 32, 32, 240) }
        val raster = GrayRaster(l.width, l.height)
        val looks = mapOf(
            "a" to TileLook(icon, "E", running = true),
            "b" to TileLook(icon, "Z", running = false),
            "c" to TileLook(null, "D", running = false),
        )
        PageRenderer(text).render(raster, l, PageLook(focus = FocusTarget("a"), tiles = looks))
        // Icons start 36 px into the cell and 14 px down: (52, 22) for the first cell.
        fun px(cell: Int, x: Int, y: Int) = raster[16 + cell * 136 + 36 + x, 8 + 14 + y]
        // The focused cell: the icon's black does not punch a hole into the fill, its light is kept.
        assertEquals(Levels.SURFACE, px(0, 4, 4))
        assertEquals(240, px(0, 32, 32))
        // The focus frame, 4 px inside the cell, and the running dot right of the icon's top.
        assertEquals(Levels.STRONG, (0 until 4).maxOf { raster[16 + 4 + it, 8 + 60] })
        assertEquals(Levels.STRONG, px(0, 72, 6))
        // Not focused: no fill, no frame, a dimmer icon, no dot.
        assertEquals(0, px(1, 4, 4))
        assertEquals(240 * 13 / 15, px(1, 32, 32))
        assertEquals(0, px(1, 72, 6))
        // Without an icon: a frame at DIM with the initials, and the names below.
        assertEquals(Levels.DIM, px(2, 2, 32))
        assertTrue(listOf("D", "Eins", "Zwei", "Drei").all { it in text.drawn })
        assertTrue("E" !in text.drawn && "Z" !in text.drawn)
    }

    @Test
    fun `a long name stays inside the focus frame of its cell`() {
        val l = grid(Block.Button("a", "M".repeat(20)))
        val raster = GrayRaster(l.width, l.height)
        PageRenderer(text).render(raster, l, PageLook(focus = FocusTarget("a")))
        assertTrue(text.drawn.any { it.endsWith("…") })
        // The frame is 3 px wide, 4 px inside the cell; next to its inner edge, 4 px stay free of the name
        // (in the name's rows above where the frame's corners round off, 12 px above its bottom).
        val rows = 8 + 84 until 8 + 116 - 12
        val free = (7 until 11) + (136 - 11 until 136 - 7)
        assertEquals(Levels.SURFACE, rows.maxOf { y -> free.maxOf { x -> raster[16 + x, y] } })
    }

    @Test
    fun `initials are the first letters of the first two words`() {
        assertEquals("Y", TileLook.initials("YouTube"))
        assertEquals("HA", TileLook.initials("Hub-App"))
        assertEquals("ML", TileLook.initials("mein langer Name"))
        assertEquals("É", TileLook.initials("été"))
        assertEquals("", TileLook.initials(" "))
    }

    @Test
    fun `levels are drawn so the glasses round them back exactly`() {
        for (level in 0..15) assertEquals(level, minOf(15, (Levels.of(level) + 8) shr 4))
    }
}

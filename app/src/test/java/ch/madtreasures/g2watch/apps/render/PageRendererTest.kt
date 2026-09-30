package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout and drawing rules of 02 §4.2, checked on pixels (the pictures come from AppsSnapshotTest). */
class PageRendererTest {
    private val text = FakeText()
    private val renderer = PageRenderer(text)

    private fun layout(page: Page, height: Int = PageMetrics.HEIGHT) = renderer.layout(page, PageMetrics.WIDTH, height)

    private fun draw(page: Page, view: PageView = PageView(), height: Int = PageMetrics.HEIGHT, toast: String? = null): GrayRaster {
        val raster = GrayRaster(PageMetrics.WIDTH, height)
        renderer.render(page, layout(page, height), view, raster, toast = toast)
        return raster
    }

    @Test
    fun `blocks stack with the margins and gaps of 02`() {
        val page = Page(
            "p", "P",
            blocks = listOf(
                Block.Heading("h", "Titel"),
                Block.Heading("g", "Groß", size = HeadingSize.GROSS),
                Block.Button("b", "Knopf"),
                Block.List("l", ListStyle.BULLETS, listOf(ListItem("a"), ListItem("b"))),
                Block.Toggle("t", "Ton"),
                Block.Value("v", "Akku", "80 %"),
                Block.Progress("pr", "Laden", 40),
                Block.Divider("d"),
            ),
        )
        val boxes = layout(page).boxes
        assertEquals(listOf(8, 51, 104, 152, 220, 264, 308, 348), boxes.map { it.y })
        assertEquals(listOf(35, 45, 40, 60, 36, 36, 32, 14), boxes.map { it.h })
        assertTrue(boxes.all { it.x == 16 && it.w == 544 })
        assertEquals(370, layout(page).contentHeight)
    }

    @Test
    fun `the focused button gets a bright, thicker frame`() {
        val page = Page("p", "P", blocks = listOf(Block.Button("a", "A"), Block.Button("b", "B")))
        val plain = draw(page)
        val focused = draw(page, PageView(focus = FocusTarget("b")))
        val b = layout(page).box("b")!!
        // The left edge of the capsule, at its vertical middle.
        val y = b.y + b.h / 2
        assertEquals(PageMetrics.BORDER, (0 until 3).maxOf { plain[b.x + it, y] })
        assertEquals(PageMetrics.STRONG, (0 until 3).maxOf { focused[b.x + it, y] })
        val a = layout(page).box("a")!!
        assertEquals(plain[a.x, a.y + a.h / 2], focused[a.x, a.y + a.h / 2])
    }

    @Test
    fun `a check row with focus gets a frame, ticked boxes are filled`() {
        val page = Page("p", "P", blocks = listOf(Block.List("l", ListStyle.CHECKS, listOf(ListItem("a", true), ListItem("b")))))
        val r = draw(page, PageView(focus = FocusTarget("l", 1)))
        val box = layout(page).box("l")!!
        // Ticked box: lit inside; open box: dark inside.
        assertEquals(PageMetrics.TEXT, r[box.x + 2, box.y + 7])
        assertEquals(0, r[box.x + 10, box.y + 30 + 15])
        // The focus frame runs 8 px left of the row.
        assertEquals(PageMetrics.STRONG, (0 until 3).maxOf { r[box.x - 8 + it, box.y + 30 + 15] })
        assertEquals(0, r[box.x - 8, box.y + 15])
    }

    @Test
    fun `a taller page scrolls and shows a thin scroll bar`() {
        val long = Page("p", "P", blocks = (1..10).map { Block.Button("b$it", "Knopf $it") })
        val l = layout(long)
        assertEquals(8 + 10 * 40 + 9 * 8 + 8 - PageMetrics.HEIGHT, l.maxScroll)
        val top = draw(long)
        val scrolled = draw(long, PageView(scroll = l.maxScroll))
        val barX = PageMetrics.WIDTH - 5
        assertTrue(top[barX, 10] > 0)
        // The thumb is at the top, then at the bottom.
        assertEquals(PageMetrics.DIM, top[barX, 10])
        assertEquals(PageMetrics.DIM, scrolled[barX, PageMetrics.HEIGHT - 10])
        val short = Page("s", "S", blocks = listOf(Block.Button("b", "B")))
        assertEquals(0, draw(short)[barX, 10])
        assertEquals(FocusTarget("b10"), l.targetAt(100, l.box("b10")!!.y + 5))
        assertEquals(l.maxScroll, l.scrollToShow(FocusTarget("b10"), 0))
    }

    @Test
    fun `a borderless picture fills the full band from the left edge`() {
        val page = Page("p", "P", statusBar = false, blocks = listOf(Block.Image("i", null, 576, 288, bleed = true)))
        val l = layout(page, PageMetrics.FULL_HEIGHT)
        val box = l.box("i")!!
        assertEquals(listOf(0, 0, 576, 288), listOf(box.x, box.y, box.w, box.h))
        assertEquals(0, l.maxScroll)
        val pixels = GrayRaster(576, 288).also { it.fillRect(0, 0, 576, 288, 77) }
        val out = GrayRaster(576, 288)
        renderer.render(page, l, PageView(), out, image = { if (it == "i") pixels else null })
        assertEquals(77, out[0, 0])
        assertEquals(77, out[575, 287])
    }

    @Test
    fun `a toast sits on a dark plate at the bottom`() {
        val page = Page("p", "P", blocks = listOf(Block.Text("t", "x ".repeat(200))))
        val r = draw(page, toast = "Gespeichert")
        assertTrue("Gespeichert" in text.drawn)
        val y = PageMetrics.HEIGHT - 10 - 18
        assertEquals(PageMetrics.STRONG, (0 until PageMetrics.WIDTH).maxOf { r[it, y] })
    }

    @Test
    fun `text wraps between words and breaks words that are too long`() {
        val lines = PageLayout.wrap(text, "Ein Satz\nund ein Wort: " + "x".repeat(60), 22, false, 544)
        assertEquals("Ein Satz", lines[0])
        assertTrue(lines.all { text.measure(it, 22) <= 544 })
        assertEquals("x".repeat(60), lines.drop(1).joinToString("").substringAfter("Wort:"))
        assertEquals("Sehr la…", PageLayout.ellipsize(text, "Sehr langer Text", 22, false, 11 * 8))
    }
}

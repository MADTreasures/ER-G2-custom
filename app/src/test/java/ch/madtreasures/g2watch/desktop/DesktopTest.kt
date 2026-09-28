package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTest {
    private val layout = DesktopLayout.centered()
    private val desktop = Desktop(layout)

    private fun Rect.centerX() = x + w / 2
    private fun Rect.centerY() = y + h / 2
    private fun tile(app: AppId) = layout.tiles.first { it.first == app }.second
    private fun button(app: AppId, id: ButtonId) = layout.buttons(app).first { it.first == id }.second

    @Test
    fun `everything lies in the visible band and nothing overlaps`() {
        val band = layout.band
        val rects = layout.tiles.map { it.second } + layout.window +
            AppId.entries.flatMap { app -> layout.buttons(app).map { it.second } }
        for (r in rects) {
            assertTrue("$r outside $band", r.x >= band.x && r.y >= band.y && r.right <= band.right && r.bottom <= band.bottom)
        }
        val tiles = layout.tiles.map { it.second }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            val a = tiles[i]
            val b = tiles[j]
            assertFalse("$a overlaps $b", a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom)
        }
        for (app in AppId.entries) {
            for ((_, r) in layout.buttons(app)) {
                val body = layout.windowBody
                assertTrue(r.x >= body.x && r.right <= body.right && r.bottom <= body.bottom)
            }
        }
    }

    @Test
    fun `clicking a tile opens its window`() {
        val r = tile(AppId.CLOCK)
        assertEquals(Target.Tile(AppId.CLOCK), desktop.hitTest(r.centerX(), r.centerY()))
        assertEquals(ClickEffect.REDRAW, desktop.click(r.centerX(), r.centerY()))
        assertEquals(AppId.CLOCK, desktop.openApp)
    }

    @Test
    fun `an open window is modal`() {
        desktop.click(tile(AppId.NOTE).centerX(), tile(AppId.NOTE).centerY())
        // Where another tile would be, the window body answers nothing.
        val other = tile(AppId.HELP)
        assertNull(desktop.hitTest(other.centerX(), other.centerY()))
        assertEquals(ClickEffect.NONE, desktop.click(other.centerX(), other.centerY()))
        assertEquals(AppId.NOTE, desktop.openApp)
    }

    @Test
    fun `the close box and back close the window`() {
        desktop.click(tile(AppId.INFO).centerX(), tile(AppId.INFO).centerY())
        val close = layout.closeButton
        assertEquals(ClickEffect.REDRAW, desktop.click(close.centerX(), close.centerY()))
        assertNull(desktop.openApp)

        desktop.click(tile(AppId.INFO).centerX(), tile(AppId.INFO).centerY())
        assertTrue(desktop.back())
        assertNull(desktop.openApp)
        assertFalse(desktop.back())
    }

    @Test
    fun `counter buttons count`() {
        desktop.click(tile(AppId.COUNTER).centerX(), tile(AppId.COUNTER).centerY())
        val plus = button(AppId.COUNTER, ButtonId.PLUS)
        val minus = button(AppId.COUNTER, ButtonId.MINUS)
        val reset = button(AppId.COUNTER, ButtonId.RESET)
        desktop.click(plus.centerX(), plus.centerY())
        desktop.click(plus.centerX(), plus.centerY())
        desktop.click(minus.centerX(), minus.centerY())
        assertEquals(1, desktop.counter)
        desktop.click(reset.centerX(), reset.centerY())
        assertEquals(0, desktop.counter)
    }

    @Test
    fun `pointer window buttons report their effect`() {
        desktop.click(tile(AppId.POINTER).centerX(), tile(AppId.POINTER).centerY())
        fun clickOn(id: ButtonId) = button(AppId.POINTER, id).let { desktop.click(it.centerX(), it.centerY()) }
        assertEquals(ClickEffect.SLOWER, clickOn(ButtonId.SLOWER))
        assertEquals(ClickEffect.FASTER, clickOn(ButtonId.FASTER))
        assertEquals(ClickEffect.CENTER_POINTER, clickOn(ButtonId.CENTER))
    }

    @Test
    fun `hover reports only changes`() {
        val r = tile(AppId.COUNTER)
        assertTrue(desktop.updateHover(r.centerX(), r.centerY()))
        assertFalse(desktop.updateHover(r.centerX() + 1, r.centerY()))
        assertEquals(Target.Tile(AppId.COUNTER), desktop.hover)
        assertTrue(desktop.updateHover(0, 0))
        assertNull(desktop.hover)
    }
}

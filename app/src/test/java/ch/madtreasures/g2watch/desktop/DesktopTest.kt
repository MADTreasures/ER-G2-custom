package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.FakeText
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
    fun `everything lies on the stage, the middle 576 px of the visible band, and nothing overlaps`() {
        val stage = layout.stage
        assertEquals(Rect(32, 96, 576, 288), stage)
        assertEquals(stage, layout.appArea(fullScreen = true))
        val rects = layout.tiles.map { it.second } + layout.window + layout.topBar + layout.appBack + layout.appTitle +
            layout.closeButton + AppId.entries.flatMap { app -> layout.buttons(app).map { it.second } }
        for (r in rects) {
            assertTrue("$r outside $stage", r.x >= stage.x && r.y >= stage.y && r.right <= stage.right && r.bottom <= stage.bottom)
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
    fun `the apps tile opens the launcher instead of a window`() {
        assertEquals(ClickEffect.OPEN_APPS, desktop.click(tile(AppId.APPS).centerX(), tile(AppId.APPS).centerY()))
        assertNull(desktop.openApp)
    }

    @Test
    fun `over an app only the header answers, with back arrow and title`() {
        desktop.app = AppView("YouTube", null, fullScreen = false, ByteArray(576 * 260), 576, 260, pointer = true)
        assertEquals(Target.AppBack, desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
        assertEquals(Target.AppTitle, desktop.hitTest(layout.appTitle.centerX(), layout.appTitle.centerY()))
        val area = layout.appArea(fullScreen = false)
        assertNull(desktop.hitTest(area.centerX(), area.centerY()))
        // Tiles under the app do not react.
        assertEquals(ClickEffect.NONE, desktop.click(tile(AppId.COUNTER).centerX(), tile(AppId.COUNTER).centerY()))
        assertNull(desktop.openApp)
        // Full screen: no header at all.
        desktop.app = AppView("YouTube", null, fullScreen = true, ByteArray(576 * 288), 576, 288, pointer = false)
        assertNull(desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
    }

    @Test
    fun `the app area is 576 by 260 below the header, 576 by 288 without it`() {
        assertEquals(Rect(32, 124, 576, 260), layout.appArea(fullScreen = false))
        assertEquals(Rect(32, 96, 576, 288), layout.appArea(fullScreen = true))
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

    @Test
    fun `the help window says that the arrows move the pointer on the desktop`() {
        // The window shows only on the bare desktop, where the arrows move the pointer (AppHost.desktopKey);
        // only inside apps do they move to the next button.
        val text = FakeText()
        val r = tile(AppId.HELP)
        desktop.click(r.centerX(), r.centerY())
        DesktopRenderer(text).render(desktop, GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT))
        val keyboard = text.drawn.single { it.startsWith("Tastatur:") }
        assertTrue(keyboard, keyboard.startsWith("Tastatur: Pfeile = Zeiger (in Apps: Knopf)"))
    }
}

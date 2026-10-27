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

    private fun view(title: String = "YouTube", fullScreen: Boolean = false, home: Boolean = false): AppView {
        val h = if (fullScreen) 288 else 260
        return AppView(title, null, fullScreen, ByteArray(576 * h), 576, h, pointer = !fullScreen, home = home)
    }

    @Test
    fun `everything lies on the stage, the middle 576 px of the visible band`() {
        val stage = layout.stage
        assertEquals(Rect(32, 96, 576, 288), stage)
        assertEquals(stage, layout.appArea(fullScreen = true))
        for (r in listOf(layout.topBar, layout.appBack, layout.appTitle, layout.appArea(fullScreen = false))) {
            assertTrue("$r outside $stage", r.x >= stage.x && r.y >= stage.y && r.right <= stage.right && r.bottom <= stage.bottom)
        }
    }

    @Test
    fun `over an app only the header answers, with back arrow and title`() {
        desktop.app = view()
        assertEquals(Target.AppBack, desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
        assertEquals(Target.AppTitle, desktop.hitTest(layout.appTitle.centerX(), layout.appTitle.centerY()))
        val area = layout.appArea(fullScreen = false)
        assertNull(desktop.hitTest(area.centerX(), area.centerY()))
        // Full screen: no header at all.
        desktop.app = view(fullScreen = true)
        assertNull(desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
    }

    @Test
    fun `the launcher is home, so its header has no back arrow and no menu`() {
        desktop.app = view("Apps", home = true)
        assertNull(desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
        assertNull(desktop.hitTest(layout.appTitle.centerX(), layout.appTitle.centerY()))
        val text = FakeText()
        DesktopRenderer(text).render(desktop, GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT))
        assertTrue("Apps" in text.drawn)
    }

    @Test
    fun `without an app nothing answers, and the bar stands alone`() {
        assertNull(desktop.hitTest(layout.appBack.centerX(), layout.appBack.centerY()))
        assertNull(desktop.hitTest(layout.stage.centerX(), layout.stage.centerY()))
        val text = FakeText()
        val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
        DesktopRenderer(text).render(desktop, raster)
        assertTrue("G2 Watch" in text.drawn)
        // Below the bar the stage is dark.
        val content = layout.appArea(fullScreen = false)
        for (y in content.y until content.bottom) for (x in content.x until content.right) assertEquals("$x,$y", 0, raster[x, y])
    }

    @Test
    fun `the app area is 576 by 260 below the header, 576 by 288 without it`() {
        assertEquals(Rect(32, 124, 576, 260), layout.appArea(fullScreen = false))
        assertEquals(Rect(32, 96, 576, 288), layout.appArea(fullScreen = true))
    }

    @Test
    fun `hover reports only changes`() {
        desktop.app = view()
        val back = layout.appBack
        assertTrue(desktop.updateHover(back.centerX(), back.centerY()))
        assertFalse(desktop.updateHover(back.centerX() + 1, back.centerY()))
        assertEquals(Target.AppBack, desktop.hover)
        assertTrue(desktop.updateHover(0, 0))
        assertNull(desktop.hover)
    }
}

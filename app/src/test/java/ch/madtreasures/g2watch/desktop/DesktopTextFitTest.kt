package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With Android's real fonts (Robolectric, native graphics): every text of the desktop fits the top bar,
 * with and without an app's header. So the renderer's hard edge at the stage never cuts a text off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class DesktopTextFitTest {
    private class Box(val text: String, val rect: Rect)

    /** The real painter, remembering the line box of every text it draws. */
    private class Recording(private val painter: TextPainter) : TextPainter by painter {
        val boxes = mutableListOf<Box>()

        override fun draw(target: GrayRaster, text: String, x: Int, y: Int, sizePx: Int, value: Int, bold: Boolean) {
            boxes += Box(text, Rect(x, y, painter.measure(text, sizePx, bold), painter.lineHeight(sizePx)))
            painter.draw(target, text, x, y, sizePx, value, bold)
        }
    }

    private val text = Recording(AndroidTextPainter())
    private val layout = DesktopLayout.centered()
    private val desktop = Desktop(layout).apply {
        // The widest values the top bar can show.
        status = DesktopStatus(time = "23:59", watchBattery = 100, glassesBattery = 100, glassesCharging = true)
    }
    private val renderer = DesktopRenderer(text)
    private val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)

    private fun Rect.holds(r: Rect) = r.x >= x && r.y >= y && r.right <= right && r.bottom <= bottom

    /** Renders and checks that each text lies in the top bar. */
    private fun check(what: String) {
        text.boxes.clear()
        renderer.render(desktop, raster)
        assertTrue(text.boxes.isNotEmpty())
        for (box in text.boxes) {
            assertTrue("$what: „${box.text}“ at ${box.rect} runs out of ${layout.topBar}", layout.topBar.holds(box.rect))
        }
    }

    @Test
    fun `every text fits the top bar, alone, at home and in an app's header`() {
        check("bar alone")
        val area = layout.appArea(fullScreen = false)
        desktop.app = AppView("Apps", null, false, ByteArray(area.w * area.h), area.w, area.h, pointer = true, home = true)
        check("launcher")
        desktop.app = AppView(
            "Eine App mit einem sehr langen Namen", "und eine Seite mit langem Namen", false,
            ByteArray(area.w * area.h), area.w, area.h, pointer = true,
        )
        check("app header")
    }
}

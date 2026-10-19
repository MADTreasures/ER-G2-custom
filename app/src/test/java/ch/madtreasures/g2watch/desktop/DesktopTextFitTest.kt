package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With Android's real fonts (Robolectric, native graphics): every text of the desktop fits where it
 * belongs, the top bar or the open window. So the renderer's hard edge at the stage never cuts a text off.
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
        // The widest values the top bar and the info window can show.
        status = DesktopStatus(
            time = "23:59",
            date = "Donnerstag, 30. September",
            watchBattery = 100,
            glassesBattery = 100,
            glassesCharging = true,
            connection = "Verbunden",
            firmware = "Faceclaw/35 · Basis 2.3.0.24",
        )
    }
    private val renderer = DesktopRenderer(text)
    private val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)

    private fun Rect.holds(r: Rect) = r.x >= x && r.y >= y && r.right <= right && r.bottom <= bottom

    /** Renders and checks that each text lies in the top bar or in [body]. */
    private fun check(what: String, body: Rect) {
        text.boxes.clear()
        renderer.render(desktop, raster)
        assertTrue(text.boxes.isNotEmpty())
        for (box in text.boxes) {
            val home = if (box.rect.y < layout.topBar.bottom) layout.topBar else body
            assertTrue("$what: „${box.text}“ at ${box.rect} runs out of $home", home.holds(box.rect))
        }
    }

    @Test
    fun `every text fits its place on the tiles, in each window and in an app's header`() {
        check("tiles", layout.stage)
        for (app in AppId.entries - AppId.APPS) {
            val tile = layout.tiles.first { it.first == app }.second
            desktop.click(tile.x + tile.w / 2, tile.y + tile.h / 2)
            check("window ${app.title}", layout.window)
            desktop.back()
        }
        val area = layout.appArea(fullScreen = false)
        desktop.app = AppView(
            "Eine App mit einem sehr langen Namen", "und eine Seite mit langem Namen", false,
            ByteArray(area.w * area.h), area.w, area.h, pointer = true,
        )
        check("app header", area)
    }
}

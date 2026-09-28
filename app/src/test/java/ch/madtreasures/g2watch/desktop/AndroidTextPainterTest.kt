package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real text rendering through Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidTextPainterTest {
    private val painter = AndroidTextPainter()

    @Test
    fun `measures wider for longer and larger text`() {
        val small = painter.measure("Hallo", 18)
        assertTrue(small > 0)
        assertTrue(painter.measure("Hallo Welt", 18) > small)
        assertTrue(painter.measure("Hallo", 36) > small)
    }

    @Test
    fun `draws inside its line box at the requested brightness`() {
        val raster = GrayRaster(300, 80)
        val x = 20
        val y = 10
        val size = 22
        painter.draw(raster, "Hallo Welt", x, y, size, value = 200)
        val right = x + painter.measure("Hallo Welt", size) + 2
        val bottom = y + painter.lineHeight(size)
        var lit = 0
        var brightest = 0
        for (py in 0 until raster.height) for (px in 0 until raster.width) {
            val v = raster[px, py]
            if (v == 0) continue
            assertTrue("pixel ($px, $py) outside the line box", px >= x - 1 && px < right && py >= y && py < bottom)
            lit++
            brightest = maxOf(brightest, v)
        }
        assertTrue("only $lit pixels lit", lit > 50)
        assertTrue(brightest <= 200)
        assertTrue("brightest $brightest", brightest >= 180)
    }

    @Test
    fun `text never darkens what is already there`() {
        val raster = GrayRaster(200, 40)
        raster.fillRect(0, 0, 200, 40, 255)
        painter.draw(raster, "Hallo", 10, 5, 22, value = 100)
        for (py in 0 until raster.height) for (px in 0 until raster.width) assertEquals(255, raster[px, py])
    }

    @Test
    fun `a large line after a small one is not clipped`() {
        val raster = GrayRaster(640, 120)
        painter.draw(raster, "a", 0, 0, 12)
        painter.draw(raster, "Uhr 14:05", 0, 10, 72, bold = true)
        var lowest = 0
        for (py in 0 until raster.height) for (px in 0 until raster.width) if (raster[px, py] > 0) lowest = maxOf(lowest, py)
        assertTrue("lowest lit row $lowest", lowest > 60)
    }
}

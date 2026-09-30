package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real text rendering through Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidTextPainterTest {
    private val painter = AndroidTextPainter()
    private val emojiFont = AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets)
    private val withEmoji = AndroidTextPainter(emojiFont)

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

    @Test
    fun `the app brings its emoji font`() {
        assertNotNull(emojiFont)
    }

    @Test
    fun `text without emoji looks the same with the emoji font`() {
        val line = "Grüße – „Zürich“ 14:05 · 1,2 Mio. © ❤ ☀ ✓"
        for (bold in listOf(false, true)) {
            assertEquals(painter.measure(line, 22, bold), withEmoji.measure(line, 22, bold))
            val plain = GrayRaster(560, 40)
            val emoji = GrayRaster(560, 40)
            painter.draw(plain, line, 4, 4, 22, bold = bold)
            withEmoji.draw(emoji, line, 4, 4, 22, bold = bold)
            assertArrayEquals(plain.pixels, emoji.pixels)
        }
    }

    @Test
    fun `an emoji is a line drawing, not a blob`() {
        // Also with U+FE0F, for which Android would take the colour font: "☺️", the keycap "1️⃣".
        for (e in listOf("😀", "☺️", "1️⃣")) {
            val raster = GrayRaster(120, 100)
            withEmoji.draw(raster, e, 10, 5, 60)
            val ink = Ink.of(raster)
            // 45 to 80 px wide and high, of which at most half is lit: outline, eyes, mouth.
            assertTrue("$e: ${ink.width} × ${ink.height}", ink.width in 45..80 && ink.height in 45..80)
            assertTrue("$e: fill ${ink.fill}", ink.fill < 0.5)
            val width = withEmoji.measure(e, 60)
            assertTrue("$e: advance $width", width in 60..90)
        }
    }

    @Test
    fun `a family, a flag, a skin tone and a keycap are one picture each`() {
        val one = withEmoji.measure("😀", 40)
        for (e in listOf("👨‍👩‍👧", "🇨🇭", "👍🏽", "1️⃣", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "❤️")) {
            val w = withEmoji.measure(e, 40)
            assertTrue("$e is $w px, one emoji $one px", w in one * 3 / 4..one * 5 / 4)
        }
    }

    @Test
    fun `text and emoji share a line`() {
        val size = 22
        val text = "Katzen 😹 TOP"
        val before = withEmoji.measure("Katzen ", size)
        val emoji = withEmoji.measure("😹", size)
        val parts = before + emoji + withEmoji.measure(" TOP", size)
        assertTrue(Math.abs(withEmoji.measure(text, size) - parts) <= 2)

        val x = 5
        val y = 5
        val raster = GrayRaster(300, 50)
        withEmoji.draw(raster, text, x, y, size)
        val box = GrayRaster(emoji, withEmoji.lineHeight(size))
        for (py in 0 until box.height) for (px in 0 until box.width) box[px, py] = raster[x + before + px, y + py]
        val ink = Ink.of(box)
        assertTrue("emoji ${ink.width} × ${ink.height}", ink.width >= size * 3 / 4 && ink.height >= size * 3 / 4)
        // Neither cut at the top nor at the bottom of the line box.
        assertTrue("top ${ink.top}, bottom ${ink.bottom}", ink.top > 0 && ink.bottom < box.height - 1)
    }

    @Test
    fun `bold emoji are drawn heavier`() {
        fun ink(bold: Boolean) = GrayRaster(100, 70).also { withEmoji.draw(it, "😀", 5, 5, 44, bold = bold) }.pixels.sumOf { it.toInt() and 0xff }
        val regular = ink(false)
        val bold = ink(true)
        assertTrue("regular $regular, bold $bold", bold > regular * 11 / 10)
    }

    @Test
    fun `U+FE0E keeps the letter form of an emoji`() {
        val text = "❤︎ ⭐︎"
        assertEquals(painter.measure(text, 22), withEmoji.measure(text, 22))
    }

    /** Where a raster is lit (value > 64): bounding box and how much of it is lit. */
    private class Ink(val left: Int, val top: Int, val right: Int, val bottom: Int, val lit: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val fill get() = lit.toDouble() / (width * height)

        companion object {
            fun of(raster: GrayRaster): Ink {
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = -1
                var bottom = -1
                var lit = 0
                for (y in 0 until raster.height) for (x in 0 until raster.width) {
                    if (raster[x, y] <= 64) continue
                    lit++
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
                assertTrue("nothing drawn", lit > 0)
                return Ink(left, top, right, bottom, lit)
            }
        }
    }
}

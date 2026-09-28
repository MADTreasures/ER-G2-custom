package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerTest {
    private val band = Rect(0, 96, 640, 288)

    @Test
    fun `starts in the middle of the visible band`() {
        val p = PointerPosition(band)
        assertEquals(320, p.x)
        assertEquals(240, p.y)
    }

    @Test
    fun `stays inside the band`() {
        val p = PointerPosition(band)
        p.moveBy(-10_000f, -10_000f)
        assertEquals(0, p.x)
        assertEquals(96, p.y)
        p.moveBy(10_000f, 10_000f)
        assertEquals(639, p.x)
        assertEquals(383, p.y)
    }

    @Test
    fun `sub-pixel moves add up and report only visible changes`() {
        val p = PointerPosition(band)
        assertFalse(p.moveBy(0.3f, 0f))
        assertTrue(p.moveBy(0.3f, 0f))
        assertEquals(321, p.x)
    }

    @Test
    fun `center returns to the middle`() {
        val p = PointerPosition(band)
        p.moveBy(100f, 50f)
        p.center()
        assertEquals(320, p.x)
        assertEquals(240, p.y)
    }

    @Test
    fun `slow strokes are precise and fast flicks cover distance`() {
        // 1 dp in 100 ms: barely any acceleration.
        val (slowX, _) = PointerMotion.toGlasses(1f, 0f, 100f, 1f)
        assertEquals(PointerMotion.BASE_GAIN * (0.55f + 0.01f * 1.2f), slowX, 0.001f)
        // 40 dp in 10 ms: acceleration at its cap.
        val (fastX, _) = PointerMotion.toGlasses(40f, 0f, 10f, 1f)
        assertEquals(40f * PointerMotion.BASE_GAIN * 2.6f, fastX, 0.001f)
    }

    @Test
    fun `speed is limited`() {
        val (x, _) = PointerMotion.toGlasses(1f, 0f, 100f, 100f)
        assertEquals(PointerMotion.BASE_GAIN * (0.55f + 0.01f * 1.2f) * PointerMotion.MAX_SPEED, x, 0.001f)
    }

    @Test
    fun `sprite uses only the color-key values`() {
        assertEquals(11, PointerSprite.width)
        assertEquals(17, PointerSprite.height)
        assertEquals(PointerSprite.BRIGHT, PointerSprite.normal[TIP].toInt() and 0xFF)
        val values = PointerSprite.normal.map { it.toInt() and 0xFF }.toSet()
        assertEquals(setOf(0, PointerSprite.DARK, PointerSprite.BRIGHT), values)
    }

    @Test
    fun `over dark pixels the pointer has a bright outline and a dark fill`() {
        val pixels = PointerSprite.render(GrayRaster(640, 480), 100, 100)
        assertArrayEquals(PointerSprite.normal, pixels)
        assertEquals(PointerSprite.BRIGHT, pixels[TIP].toInt() and 0xFF)
        assertEquals(PointerSprite.DARK, pixels[FILL].toInt() and 0xFF)
    }

    @Test
    fun `over lit pixels the pointer is negative`() {
        val lit = GrayRaster(640, 480).apply { clear(255) }
        val pixels = PointerSprite.render(lit, 100, 100)
        assertEquals(PointerSprite.DARK, pixels[TIP].toInt() and 0xFF)
        assertEquals(PointerSprite.BRIGHT, pixels[FILL].toInt() and 0xFF)
        // The shape stays the same: transparent where the normal pointer is transparent.
        for (i in pixels.indices) assertEquals(PointerSprite.normal[i] == 0.toByte(), pixels[i] == 0.toByte())
    }

    @Test
    fun `each pixel follows what lies beneath it`() {
        // Lit up to x = 104: the left part of the pointer is negative, the rest normal.
        val half = GrayRaster(640, 480).apply { fillRect(0, 0, 105, 480, 200) }
        val pixels = PointerSprite.render(half, 100, 100)
        assertEquals(PointerSprite.DARK, pixels[TIP].toInt() and 0xFF) // x = 100, over lit
        val rightEdge = 9 * PointerSprite.width + 9 // "X........X", last X at x = 109, over dark
        assertEquals(PointerSprite.BRIGHT, pixels[rightEdge].toInt() and 0xFF)
    }

    @Test
    fun `the switch happens at half brightness`() {
        val below = GrayRaster(640, 480).apply { clear(PointerSprite.NEGATIVE_FROM - 1) }
        val at = GrayRaster(640, 480).apply { clear(PointerSprite.NEGATIVE_FROM) }
        assertArrayEquals(PointerSprite.normal, PointerSprite.render(below, 10, 10))
        assertEquals(PointerSprite.DARK, PointerSprite.render(at, 10, 10)[TIP].toInt() and 0xFF)
    }

    @Test
    fun `pixels beyond the screen edge count as dark`() {
        val lit = GrayRaster(640, 480).apply { clear(255) }
        val pixels = PointerSprite.render(lit, 635, 470)
        assertEquals(PointerSprite.DARK, pixels[TIP].toInt() and 0xFF) // (635, 470) is on screen
        val offScreen = 9 * PointerSprite.width + 9 // (644, 479) is not
        assertEquals(PointerSprite.BRIGHT, pixels[offScreen].toInt() and 0xFF)
    }

    @Test
    fun `the fingerprint follows the pixels`() {
        val negative = PointerSprite.render(GrayRaster(20, 20).apply { clear(255) }, 0, 0)
        assertNotEquals(PointerSprite.fingerprint(PointerSprite.normal), PointerSprite.fingerprint(negative))
        assertEquals(PointerSprite.fingerprint(PointerSprite.normal), PointerSprite.fingerprint(PointerSprite.normal.copyOf()))
    }

    private companion object {
        /** The tip, an outline pixel. */
        const val TIP = 0

        /** Row "X.X": its middle is a fill pixel. */
        val FILL = 2 * PointerSprite.width + 1
    }
}

package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GrayRasterTest {

    @Test
    fun `fillRect clips at the edges`() {
        val r = GrayRaster(4, 3)
        r.fillRect(-2, 1, 4, 10, 9)
        assertEquals(9, r[0, 1])
        assertEquals(9, r[1, 2])
        assertEquals(0, r[2, 1])
        assertEquals(0, r[0, 0])
    }

    @Test
    fun `strokeRect draws a frame inside the rect`() {
        val r = GrayRaster(5, 5)
        r.strokeRect(Rect(0, 0, 5, 5), 7)
        assertEquals(7, r[0, 0])
        assertEquals(7, r[4, 4])
        assertEquals(7, r[0, 2])
        assertEquals(0, r[2, 2])
    }

    @Test
    fun `lighten keeps the brighter value`() {
        val r = GrayRaster(2, 1)
        r[0, 0] = 100
        r.lighten(0, 0, 50)
        r.lighten(1, 0, 50)
        r.lighten(5, 5, 50) // outside: ignored
        assertEquals(100, r[0, 0])
        assertEquals(50, r[1, 0])
    }

    @Test
    fun `copyRegion copies rows`() {
        val r = GrayRaster(3, 3)
        for (y in 0 until 3) for (x in 0 until 3) r[x, y] = y * 3 + x
        assertArrayEquals(byteArrayOf(4, 5, 7, 8), r.copyRegion(1, 1, 2, 2))
    }

    @Test
    fun `clearOutside keeps only the rect`() {
        val r = GrayRaster(6, 5).apply { clear(9) }
        r.clearOutside(Rect(1, 1, 3, 2))
        for (y in 0 until 5) for (x in 0 until 6) assertEquals("$x,$y", if (x in 1..3 && y in 1..2) 9 else 0, r[x, y])
    }

    @Test
    fun `copyFrom copies one block to the same place`() {
        val source = GrayRaster(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) source[x, y] = y * 4 + x + 1
        val target = GrayRaster(4, 4).apply { clear(99) }
        target.copyFrom(source, Rect(1, 2, 2, 2))
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals("$x,$y", if (x in 1..2 && y in 2..3) y * 4 + x + 1 else 99, target[x, y])
        }
        assertThrows(IllegalArgumentException::class.java) { target.copyFrom(source, Rect(3, 3, 2, 1)) }
        assertThrows(IllegalArgumentException::class.java) { target.copyFrom(GrayRaster(5, 4), Rect(0, 0, 1, 1)) }
    }

    @Test
    fun `union covers both rects, intersect is the common part or null`() {
        assertEquals(Rect(0, 0, 10, 8), Rect(0, 0, 4, 4).union(Rect(6, 5, 4, 3)))
        assertEquals(Rect(2, 1, 3, 3), Rect(2, 1, 3, 3).union(Rect(3, 2, 1, 1)))
        assertEquals(Rect(2, 2, 2, 2), Rect(0, 0, 4, 4).intersect(Rect(2, 2, 5, 5)))
        assertEquals(Rect(3, 2, 1, 1), Rect(2, 1, 3, 3).intersect(Rect(3, 2, 1, 1)))
        // Rects that only touch share no pixel.
        assertNull(Rect(0, 0, 4, 4).intersect(Rect(4, 0, 2, 2)))
        assertNull(Rect(0, 0, 4, 4).intersect(Rect(0, 4, 2, 2)))
    }

    @Test
    fun `content hash follows the pixels`() {
        val a = GrayRaster(8, 8)
        val b = GrayRaster(8, 8)
        assertEquals(a.contentHash(), b.contentHash())
        b[3, 3] = 1
        assertNotEquals(a.contentHash(), b.contentHash())
    }
}

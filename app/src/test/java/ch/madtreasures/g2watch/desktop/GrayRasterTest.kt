package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `content hash follows the pixels`() {
        val a = GrayRaster(8, 8)
        val b = GrayRaster(8, 8)
        assertEquals(a.contentHash(), b.contentHash())
        b[3, 3] = 1
        assertNotEquals(a.contentHash(), b.contentHash())
    }
}

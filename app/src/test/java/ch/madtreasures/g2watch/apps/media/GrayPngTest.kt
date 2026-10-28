package ch.madtreasures.g2watch.apps.media

import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Grey covers as PNG for image blocks: a valid file, the glasses' 16 levels, small enough for a block. */
class GrayPngTest {
    @Test
    fun `a grey picture becomes a valid PNG with the glasses' levels`() {
        val r = GrayRaster(120, 90)
        for (y in 0 until r.height) for (x in 0 until r.width) r.pixels[y * r.width + x] = (x * 255 / 119).toByte()
        val png = GrayPng.encode(r)
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(120, image.width)
        assertEquals(90, image.height)
        val levels = HashSet<Int>()
        for (x in 0 until image.width) levels += image.raster.getSample(x, 10, 0)
        assertTrue(levels.toString(), levels.all { it == 255 || it % 16 == 0 })
        assertEquals(16, levels.size)
        assertEquals(0, image.raster.getSample(0, 0, 0))
        assertEquals(255, image.raster.getSample(119, 0, 0))
    }

    @Test
    fun `a cover of 120 square stays far below the 48 KB of an image block`() {
        val r = GrayRaster(120, 120)
        val random = java.util.Random(7)
        for (i in r.pixels.indices) r.pixels[i] = random.nextInt(256).toByte()
        val url = GrayPng.dataUrl(r)
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertTrue(url.length < 48 * 1024)
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(url.substringAfter(','))))
        assertEquals(120, image.height)
    }
}

package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.assetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Map pictures for the glasses from the Vaduz map; with -PsnapshotDir they are written out to look at. */
class MapRendererTest {
    private val fonts = Fonts { assetFile(it).takeIf { f -> f.isFile }?.readBytes() }
    private val renderer = MapRenderer(fonts)
    private val vaduz = LatLon(47.1404, 9.5213)

    private fun save(r: Raster, name: String) {
        val dir = System.getProperty("snapshotDir") ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").writeBytes(r.png())
    }

    private fun lit(r: Raster) = r.px.count { it.toInt() != 0 }

    @Test
    fun `Vaduz up close shows its streets, calm and within the glasses' levels`() {
        MapSource(Fixtures.vaduz).use { src ->
            val r = Raster(576, 288)
            val drawn = renderer.render(r, MapView(vaduz, 16.0, 576, 288), listOf(src), Overlay(position = vaduz, heading = 30.0, accuracyM = 8.0))
            save(r, "test-vaduz-16")
            assertTrue("ways drawn: $drawn", drawn > 200)
            val share = lit(r).toDouble() / (576 * 288)
            assertTrue("lit share $share", share in 0.03..0.45)
            // The arrow sits in the middle, brightest.
            assertEquals(255, r[288, 144])
        }
    }

    @Test
    fun `the picture fits an image block as PNG with 16 levels`() {
        MapSource(Fixtures.vaduz).use { src ->
            val r = Raster(576, 288)
            renderer.render(r, MapView(vaduz, 15.0, 576, 288), listOf(src))
            val png = r.png()
            val url = r.dataUrl()
            assertTrue("PNG ${png.size} bytes", url.length <= Raster.MAX_DATA_URL)
            val image = ImageIO.read(ByteArrayInputStream(png))
            assertEquals(576 to 288, image.width to image.height)
            // Every grey the PNG holds is one of the glasses' 16.
            val greys = HashSet<Int>()
            for (y in 0 until 288) for (x in 0 until 576) greys += image.raster.getSample(x, y, 0)
            assertTrue(greys.toString(), greys.all { it in 0..15 })
        }
    }

    @Test
    fun `heading up turns the map, and the arrow keeps pointing up`() {
        MapSource(Fixtures.vaduz).use { src ->
            val north = Raster(576, 288)
            val east = Raster(576, 288)
            renderer.render(north, MapView(vaduz, 16.0, 576, 288), listOf(src))
            renderer.render(east, MapView(vaduz, 16.0, 576, 288, rotation = 90.0), listOf(src), Overlay(position = vaduz, heading = 90.0))
            save(east, "test-vaduz-heading")
            assertTrue(north.px.contentHashCode() != east.px.contentHashCode())
            // The tip of the arrow is above the middle.
            assertEquals(255, east[288, 144 - 10])
        }
    }

    @Test
    fun `real cities up close and from afar`() {
        val ch = Fixtures.realMap("switzerland.map")
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        MapSource(ch!!).use { src ->
            for ((name, center, zoom) in listOf(
                Triple("zuerich-16", LatLon(47.3769, 8.5417), 16.0),
                Triple("zuerich-13", LatLon(47.3769, 8.5417), 13.0),
                Triple("bern-17", LatLon(46.9480, 7.4474), 17.0),
                Triple("schweiz-9", LatLon(46.8, 8.2), 9.0),
            )) {
                val r = Raster(576, 288)
                val t0 = System.nanoTime()
                val drawn = renderer.render(r, MapView(center, zoom, 576, 288), listOf(src), Overlay(position = center, heading = 0.0))
                val t1 = System.nanoTime()
                val r2 = Raster(576, 288)
                renderer.render(r2, MapView(center, zoom, 576, 288), listOf(src), Overlay(position = center, heading = 0.0))
                val t2 = System.nanoTime()
                println("$name: $drawn ways, first ${(t1 - t0) / 1_000_000} ms, again ${(t2 - t1) / 1_000_000} ms, PNG ${r.png().size} bytes")
                save(r, "test-$name")
                assertTrue(drawn > 50)
            }
        }
    }
}

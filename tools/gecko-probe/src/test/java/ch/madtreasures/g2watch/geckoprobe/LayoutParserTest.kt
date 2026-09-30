package ch.madtreasures.g2watch.geckoprobe

import ch.madtreasures.g2watch.webraster.Box
import ch.madtreasures.g2watch.webraster.GlassesRasterizer
import ch.madtreasures.g2watch.webraster.Surface
import ch.madtreasures.g2watch.webraster.TextRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutParserTest {

    @Test
    fun `css colours as getComputedStyle reports them`() {
        assertEquals(0xFF102030.toInt(), LayoutParser.cssColour("rgb(16, 32, 48)"))
        assertEquals(0x80FFFFFF.toInt(), LayoutParser.cssColour("rgba(255, 255, 255, 0.5)"))
        assertEquals(0xFF000000.toInt(), LayoutParser.cssColour(" rgb(0 0 0) "))
        assertEquals(0x40FF0000, LayoutParser.cssColour("rgb(255 0 0 / 25%)"))
        assertEquals(0xFFFF8001.toInt(), LayoutParser.cssColour("rgb(255.4, 127.6, 1)"))
        assertNull(LayoutParser.cssColour("rgba(0, 0, 0, 0)"))
        assertNull(LayoutParser.cssColour("transparent"))
        assertNull(LayoutParser.cssColour("color(srgb 1 0 0)"))
    }

    @Test
    fun `layout boxes are scaled from CSS pixels to captured pixels`() {
        // The content script's report for a 384 CSS pixel wide viewport, captured at 576 pixels.
        val layout = Json.parseToJsonElement(
            """
            {"dpr":1.5,"vw":384,"vh":173,
             "texts":[[10,20,100,14,"rgb(255, 255, 255)"],[10,40,50.5,14,"rgba(0, 0, 0, 0)"],[1,2,3]],
             "pictures":[[0,60,384,100]],
             "surfaces":[[0,0,384,30,"rgb(20, 20, 20)"],[0,30,10,10,"transparent"]],
             "page":"rgba(0, 0, 0, 0)","body":"rgb(255, 255, 255)","url":"http://x/"}
            """,
        ).jsonObject
        val argb = IntArray(576 * 260)
        val c = LayoutParser.capture(576, 260, argb, layout)
        assertSame(argb, c.argb)
        assertEquals(listOf(TextRun(Box(15, 30, 150, 21), 0xFFFFFFFF.toInt()), TextRun(Box(15, 60, 76, 21), null)), c.texts)
        assertEquals(listOf(Box(0, 90, 576, 150)), c.pictures)
        // The page background first, over the whole capture; surfaces without a colour are skipped.
        assertEquals(listOf(Surface(Box(0, 0, 576, 260), 0xFFFFFFFF.toInt()), Surface(Box(0, 0, 576, 45), 0xFF141414.toInt())), c.surfaces)
    }

    @Test
    fun `the capture without text is passed on`() {
        val layout = Json.parseToJsonElement("""{"vw":4,"texts":[[0,0,2,1,"rgb(0, 0, 0)"]]}""").jsonObject
        val bare = IntArray(4 * 2) { 0xFFFFFFFF.toInt() }
        assertSame(bare, LayoutParser.capture(4, 2, IntArray(8), layout, bare).textless)
        // A capture of another size is left out rather than misread.
        assertNull(LayoutParser.capture(4, 2, IntArray(8), layout, IntArray(3)).textless)
    }

    @Test
    fun `without a layout only the pixels count`() {
        val argb = IntArray(4 * 2)
        val none = LayoutParser.capture(4, 2, argb, null)
        assertTrue(none.texts.isEmpty() && none.pictures.isEmpty() && none.surfaces.isEmpty())
        val broken = LayoutParser.capture(4, 2, argb, Json.parseToJsonElement("""{"error":"TypeError"}""").jsonObject)
        assertTrue(broken.texts.isEmpty() && broken.surfaces.isEmpty())
    }

    @Test
    fun `a parsed capture goes through the rasterizer`() {
        // White text on a black page: text stays bright, the ground stays see-through.
        val w = 576
        val h = 260
        val argb = IntArray(w * h) { 0xFF000000.toInt() }
        for (y in 30 until 44) for (x in 20 until 200) if ((x / 3 + y) % 2 == 0) argb[y * w + x] = 0xFFFFFFFF.toInt()
        val layout = Json.parseToJsonElement("""{"vw":576,"texts":[[20,30,180,14,"rgb(255, 255, 255)"]],"page":"rgb(0, 0, 0)"}""").jsonObject
        val raster = GlassesRasterizer.rasterize(LayoutParser.capture(w, h, argb, layout))
        assertEquals(1, raster.report.textRuns)
        assertEquals(0, raster.report.negativeRuns)
        assertEquals(0, raster.pixels[250 * w + 500].toInt() and 0xFF)
        assertTrue((30 until 44).any { y -> (20 until 200).any { x -> (raster.pixels[y * w + x].toInt() and 0xFF) >= 240 } })
    }
}

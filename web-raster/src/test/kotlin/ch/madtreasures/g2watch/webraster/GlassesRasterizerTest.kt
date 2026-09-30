package ch.madtreasures.g2watch.webraster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class GlassesRasterizerTest {
    private val black = Color(20, 20, 20)
    private val white = Color.WHITE

    private fun GlassesRaster.maxIn(b: Box): Int = (b.y until b.bottom).maxOf { y -> (b.x until b.right).maxOf { x -> this[x, y] } }

    private fun GlassesRaster.minIn(b: Box): Int = (b.y until b.bottom).minOf { y -> (b.x until b.right).minOf { x -> this[x, y] } }

    private fun GlassesRaster.meanIn(b: Box): Double = (b.y until b.bottom).sumOf { y -> (b.x until b.right).sumOf { x -> this[x, y] } }.toDouble() / b.area

    /** Every pixel is one of the 16 values the glasses show. */
    private fun GlassesRaster.assertLevels() {
        val allowed = (0 until 16).map { Levels.gray(it) }.toSet()
        for (i in pixels.indices) assertTrue((pixels[i].toInt() and 0xFF) in allowed, "pixel $i = ${pixels[i].toInt() and 0xFF}")
    }

    private fun article(ground: Color, ink: Color): TestPage = TestPage(576, 260, ground).apply {
        text("Die Brille zeigt jede Seite", 16, 12, 26, ink, bold = true)
        text("Hintergründe werden durchsichtig, Text leuchtet.", 16, 56, 20, ink)
        text("So bleibt alles lesbar, egal wie die Seite aussieht.", 16, 86, 20, ink)
    }

    @Test
    fun `a white page becomes see-through and its text bright`() {
        val page = article(white, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        r.assertLevels()
        assertEquals(0, r.maxIn(Box(300, 150, 200, 80)))
        for (t in page.texts) assertEquals(255, r.maxIn(t.box))
        assertTrue(r.report.lightGroundShare > 0.9f)
        assertFalse(r.report.overloaded)
        assertEquals(0, r.report.negativeRuns)
    }

    @Test
    fun `a dark page keeps its polarity`() {
        val page = article(Color(18, 18, 18), Color(225, 225, 225))
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r.maxIn(Box(300, 150, 200, 80)))
        for (t in page.texts) assertEquals(255, r.maxIn(t.box))
        assertTrue(r.report.lightGroundShare < 0.1f)
    }

    @Test
    fun `light and dark sections both lose their ground`() {
        val page = TestPage(576, 260, white)
        page.rect(0, 0, 576, 64, Color(30, 40, 52))
        val head = page.text("Nachrichten", 16, 18, 26, white, bold = true)
        val body = page.text("Ein Artikel auf weißem Grund.", 16, 90, 22, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r.maxIn(Box(400, 5, 150, 50)))
        assertEquals(0, r.maxIn(Box(400, 120, 150, 100)))
        assertEquals(255, r.maxIn(head))
        assertEquals(255, r.maxIn(body))
    }

    @Test
    fun `a DOM ground the pixels do not show is ignored`() {
        // The DOM reports a white page, but the header is black (a background image): it must
        // not light up, as it did when the page's colour was taken on trust.
        val page = TestPage(576, 260, white)
        page.paint(0, 0, 576, 60, Color(0, 0, 0))
        val head = page.text("NASA", 16, 18, 26, white, bold = true)
        val body = page.text("Ein Artikel auf weißem Grund.", 16, 90, 22, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r.maxIn(Box(300, 0, 276, 60)))
        assertEquals(0, r.maxIn(Box(300, 120, 276, 140)))
        assertEquals(255, r.maxIn(head))
        assertEquals(255, r.maxIn(body))
        assertFalse(r.report.overloaded)
        assertEquals(0, r.report.negativeRuns)
    }

    @Test
    fun `a semi-transparent DOM surface is no ground`() {
        // A hidden menu veil (rgba(0, 0, 0, 0.8)) over a white article, as Wikipedia reports one.
        val page = article(white, black)
        page.surfaces += Surface(Box(0, 0, 576, 260), 0xCC000000.toInt())
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r.maxIn(Box(300, 150, 200, 80)))
        for (t in page.texts) assertEquals(255, r.maxIn(t.box))
        assertFalse(r.report.overloaded)
        assertEquals(0, r.report.negativeRuns)
    }

    @Test
    fun `an icon among the pictures is drawn like text, not as a lit square`() {
        // A menu icon (three dark bars on white) that the DOM reports as a picture (<svg>, <img>).
        val page = article(white, black)
        val icon = Box(520, 12, 36, 30)
        page.paint(icon.x + 4, icon.y + 4, 28, 4, black)
        page.paint(icon.x + 4, icon.y + 13, 28, 4, black)
        page.paint(icon.x + 4, icon.y + 22, 28, 4, black)
        page.pictures += icon
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r[icon.x + 1, icon.y + 1])
        assertEquals(0, r[icon.x + 18, icon.y + 10])
        assertTrue(r[icon.x + 18, icon.y + 5] >= 240)
        // A real photo stays a picture.
        val photo = TestPage(576, 260, white).apply { photo(100, 40, 300, 180) }
        val p = GlassesRasterizer.rasterize(photo.capture())
        assertTrue(p.report.pictureShare > 0.3f)
    }

    @Test
    fun `links in a row with separators stay bright`() {
        // "new | past | comments" as separate text nodes that touch each other, as on Hacker News.
        val page = TestPage(576, 260, white)
        var x = 16
        for (word in listOf("new", " | ", "past", " | ", "comments", " | ", "ask")) {
            val b = page.text(word, x, 20, 20, black)
            x = b.right
        }
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(0, r.report.negativeRuns)
        for (t in page.texts) assertEquals(255, r.maxIn(t.box))
    }

    @Test
    fun `a solid logo on a page lights up whole, not as an outline`() {
        val page = article(white, black)
        val logo = Box(480, 150, 40, 40)
        page.paint(logo.x, logo.y, logo.w, logo.h, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        assertTrue(r[logo.x + 20, logo.y + 20] >= 240, "inside ${r[logo.x + 20, logo.y + 20]}")
        assertEquals(0, r[logo.x - 8, logo.y + 20])
    }

    @Test
    fun `coloured text is drawn at full brightness`() {
        val page = TestPage(576, 260, white)
        val link = page.text("Ein blauer Link", 16, 20, 22, Color(26, 13, 171))
        val caption = page.text("eine graue Bildunterschrift", 16, 60, 18, Color(120, 120, 120))
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(255, r.maxIn(link))
        assertEquals(255, r.maxIn(caption))
    }

    @Test
    fun `text over a restless photo turns negative, elsewhere it stays bright`() {
        val page = TestPage(576, 260, white)
        page.photo(16, 120, 220, 100)
        val onPhoto = page.text("Über dem Foto", 24, 150, 22, white, bold = true)
        val plain = page.text("Daneben auf Weiß", 260, 40, 22, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        r.assertLevels()
        assertFalse(r.report.overloaded)
        assertEquals(1, r.report.negativeRuns)
        // The plate is lit around the letters, the letters are cut out of it.
        val plate = Levels.gray(RasterOptions().plateLevel)
        assertEquals(plate, r[onPhoto.x - 2, onPhoto.y + onPhoto.h / 2])
        assertTrue(r.minIn(onPhoto) < 64)
        assertEquals(255, r.maxIn(plain))
        assertEquals(0, r[plain.right + 20, plain.y + plain.h / 2])
    }

    @Test
    fun `a window full of dark pictures turns all text negative`() {
        val page = TestPage(576, 260, white)
        page.photo(0, 0, 280, 200, dark = true)
        page.photo(296, 0, 280, 200, dark = true)
        page.text("Bild 1: Abend am See", 8, 210, 20, black)
        page.text("Bild 2: Hügel", 300, 210, 20, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        assertTrue(r.report.overloaded)
        assertEquals(2, r.report.negativeRuns)
        // Dark pictures are not dimmed any further.
        val calm = GlassesRasterizer.rasterize(page.capture(), RasterOptions(overloadShare = 1f))
        assertFalse(calm.report.overloaded)
        val photo = Box(20, 20, 240, 160)
        assertEquals(calm.meanIn(photo), r.meanIn(photo), 2.0)
    }

    @Test
    fun `an overloaded window dims its bright pictures`() {
        val page = TestPage(576, 260, white)
        page.photo(0, 0, 576, 200)
        page.text("Ein helles Bild", 8, 214, 20, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        val calm = GlassesRasterizer.rasterize(page.capture(), RasterOptions(overloadShare = 1f))
        val photo = Box(0, 0, 576, 200)
        assertTrue(r.meanIn(photo) <= RasterOptions().overloadMean + 8, "mean ${r.meanIn(photo)}")
        assertTrue(r.meanIn(photo) < calm.meanIn(photo) * 0.9)
    }

    @Test
    fun `pictures are dithered to the 16 levels`() {
        val page = TestPage(576, 260, white)
        val box = page.photo(0, 0, 576, 260)
        val r = GlassesRasterizer.rasterize(page.capture(), RasterOptions(overloadShare = 1f))
        r.assertLevels()
        val used = (box.y until box.bottom).flatMap { y -> (box.x until box.right).map { x -> r[x, y] } }.toSet()
        assertTrue(used.size >= 8, "levels used: $used")
    }

    @Test
    fun `without the DOM the pixels alone still give a readable page`() {
        val page = article(white, black)
        val r = GlassesRasterizer.rasterize(page.capture(withDom = false))
        r.assertLevels()
        assertEquals(0, r.maxIn(Box(300, 150, 200, 80)))
        for (t in page.texts) assertTrue(r.maxIn(t.box) >= 224, "text ${t.box}")
        assertEquals(0f, r.report.pictureShare)
    }

    @Test
    fun `pictures are found in the pixels when the DOM names none`() {
        val page = article(white, black)
        page.photo(300, 120, 256, 128)
        val r = GlassesRasterizer.rasterize(page.capture(withDom = false))
        val expected = 256f * 128 / (576 * 260)
        assertTrue(r.report.pictureShare in expected * 0.7f..expected * 1.3f, "found ${r.report.pictureShare}, drawn $expected")
    }

    @Test
    fun `a wider capture is scaled to the glasses`() {
        val page = TestPage(1152, 520, white)
        val t = page.text("Doppelte Auflösung", 32, 40, 44, black)
        val r = GlassesRasterizer.rasterize(page.capture())
        assertEquals(576, r.width)
        assertEquals(260, r.height)
        assertEquals(255, r.maxIn(t.scaled(0.5f).clip(576, 260)))
    }

    @Test
    fun `the text mode can be forced`() {
        val page = TestPage(576, 260, white)
        page.photo(16, 120, 220, 100)
        page.text("Über dem Foto", 24, 150, 22, white, bold = true)
        page.text("Daneben", 260, 40, 22, black)
        assertEquals(0, GlassesRasterizer.rasterize(page.capture(), RasterOptions(text = TextMode.NORMAL)).report.negativeRuns)
        assertEquals(2, GlassesRasterizer.rasterize(page.capture(), RasterOptions(text = TextMode.NEGATIVE)).report.negativeRuns)
    }

    @Test
    fun `dithering keeps the average brightness`() {
        val w = 64
        val h = 16
        val gray = ByteArray(w * h) { (it % w * 4).toByte() }
        val out = Dither.gray(gray, w, h)
        val inMean = gray.sumOf { it.toInt() and 0xFF } / gray.size.toDouble()
        val outMean = out.sumOf { it.toInt() and 0xFF } / out.size.toDouble()
        assertEquals(inMean, outMean, 4.0)
        assertTrue(out.all { (it.toInt() and 0xFF) in (0 until 16).map { l -> Levels.gray(l) } })
    }

    @Test
    fun `flat graphics are rounded, photos dithered`() {
        val flat = ByteArray(64) { 100 }
        assertTrue(Dither.forGlasses(flat, 8, 8).all { (it.toInt() and 0xFF) == 96 })
        val photo = ByteArray(64 * 16) { (it % 64 * 3 + it / 64).toByte() }
        val out = Dither.forGlasses(photo, 64, 16)
        assertTrue(out.map { it.toInt() and 0xFF }.toSet().size > 4)
    }

    @Test
    fun `levels match the glasses' rounding`() {
        for (v in 0..255) assertEquals(minOf(15, (v + 8) shr 4), Levels.of(Levels.round(v)))
        assertEquals(255, Levels.gray(15))
        assertEquals(128, Levels.gray(8))
        assertEquals(255, Levels.luma(0x00000000))
        assertEquals(0, Levels.luma(0xFF000000.toInt()))
    }
}

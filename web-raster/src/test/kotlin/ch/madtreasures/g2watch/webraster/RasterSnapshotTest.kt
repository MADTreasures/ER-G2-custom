package ch.madtreasures.g2watch.webraster

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Before and after: a web page as a browser shows it, and as the glasses show it after
 * [GlassesRasterizer] (16 shades of green, black = see-through):
 *
 *     ./gradlew :web-raster:test --tests '*RasterSnapshotTest*' -PsnapshotDir=$PWD/docs/bilder
 *
 * Skipped without -PsnapshotDir.
 */
class RasterSnapshotTest {
    private val ink = Color(24, 24, 28)

    @Test
    fun render() {
        val dir = System.getProperty("snapshotDir")
        assumeTrue(!dir.isNullOrBlank(), "no -PsnapshotDir")
        val out = File(dir!!).apply { mkdirs() }

        // A white news page: the ground vanishes, text and the small photo remain.
        val news = TestPage(576, 260, Color.WHITE).apply {
            rect(0, 0, 576, 40, Color(0, 82, 147))
            text("Tagesnachrichten", 14, 8, 20, Color.WHITE, bold = true)
            text("Brille zeigt Web-Seiten", 14, 52, 28, ink, bold = true)
            photo(392, 100, 168, 110)
            text("Die Uhr wandelt jede Seite in das Raster", 14, 100, 18, ink)
            text("der Brille um: Grund durchsichtig, Text hell.", 14, 124, 18, ink)
            text("Mehr lesen ›", 14, 160, 18, Color(26, 13, 171))
            text("Bildunterschrift in Grau", 392, 216, 14, Color(110, 110, 110))
        }
        save(out, "raster-hell", news)

        // A dark page stays as it is: its light text lights up.
        val dark = TestPage(576, 260, Color(16, 18, 22)).apply {
            text("Dunkles Design", 14, 14, 28, Color(235, 235, 235), bold = true)
            text("Weißer Text auf dunklem Grund bleibt hell,", 14, 64, 18, Color(200, 200, 205))
            text("nichts wird umgekehrt.", 14, 88, 18, Color(200, 200, 205))
            rect(14, 130, 250, 40, Color(40, 44, 52))
            text("Knopf auf Karte", 28, 139, 18, Color(120, 200, 255))
        }
        save(out, "raster-dunkel", dark)

        // Text on a photo: a lit plate with the letters cut out (negative).
        val hero = TestPage(576, 260, Color.WHITE).apply {
            photo(0, 0, 360, 260)
            text("Titel über dem Bild", 16, 96, 26, Color.WHITE, bold = true)
            text("Daneben Text", 376, 30, 18, ink)
            text("auf weißem Grund.", 376, 54, 18, ink)
        }
        save(out, "raster-text-auf-bild", hero)

        // A window full of dark pictures: overloaded, so all text turns negative and pictures dim.
        val gallery = TestPage(576, 260, Color(245, 245, 245)).apply {
            photo(8, 8, 184, 110, dark = true)
            photo(196, 8, 184, 110, dark = true)
            photo(384, 8, 184, 110, dark = true)
            photo(8, 130, 184, 90, dark = true)
            photo(196, 130, 184, 90, dark = true)
            photo(384, 130, 184, 90, dark = true)
            text("Album: Abendstimmung", 12, 232, 18, ink, bold = true)
            text("6 Bilder", 470, 232, 18, ink)
        }
        save(out, "raster-ueberladen", gallery)
    }

    private fun save(dir: File, name: String, page: TestPage) {
        val raster = GlassesRasterizer.rasterize(page.capture())
        val gap = 16
        val label = 26
        val image = BufferedImage(page.width * 2 + gap * 3, page.height + gap * 2 + label, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 44)
        g.fillRect(0, 0, image.width, image.height)
        g.drawImage(page.image, gap, gap + label, null)
        for (y in 0 until raster.height) for (x in 0 until raster.width) image.setRGB(gap * 2 + page.width + x, gap + label + y, green(raster[x, y]))
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        g.color = Color(210, 210, 210)
        g.drawString("Web-Seite", gap, gap + 16)
        val r = raster.report
        val note = buildString {
            append("Brille")
            if (r.overloaded) append(" · Fenster überladen")
            if (r.negativeRuns > 0) append(" · ${r.negativeRuns} Zeilen negativ")
        }
        g.drawString(note, gap * 2 + page.width, gap + 16)
        g.dispose()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    private fun green(v: Int): Int {
        val level = Levels.of(v) * 17
        return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
    }
}

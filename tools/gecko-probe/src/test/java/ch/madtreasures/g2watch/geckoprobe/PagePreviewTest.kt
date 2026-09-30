package ch.madtreasures.g2watch.geckoprobe

import ch.madtreasures.g2watch.webraster.GlassesRasterizer
import ch.madtreasures.g2watch.webraster.Levels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * The page preview (tools/page-preview): screenshots and layouts of real pages, taken by
 * `capture.js` in Chromium, turned into glasses pictures by [LayoutParser] and web-raster, side by
 * side as `<PREVIEW_DIR>/views/<page>.png`. Skipped without the environment variable PREVIEW_DIR:
 *
 *     PREVIEW_DIR=/tmp/vorschau ./gradlew :gecko-probe:testArmv7DebugUnitTest --tests '*PagePreviewTest*'
 */
class PagePreviewTest {
    @Test
    fun preview() {
        val path = System.getenv("PREVIEW_DIR")
        assumeTrue("no PREVIEW_DIR", !path.isNullOrBlank())
        val dir = File(path!!)
        val out = File(dir, "views").apply { mkdirs() }
        val frames = File(dir, "raw").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        frames.groupBy { it.name.substringBeforeLast('-') }.forEach { (id, files) -> page(id, files, out) }
    }

    private fun page(id: String, files: List<File>, out: File) {
        val w = 576
        val h = 260
        val gap = 16
        val label = 24
        val head = 34
        val rowH = label + h + gap
        val image = BufferedImage(w * 2 + gap * 3, head + files.size * rowH + gap / 2, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 44)
        g.fillRect(0, 0, image.width, image.height)
        var title = ""
        files.forEachIndexed { row, json ->
            val root = Json.parseToJsonElement(json.readText()).jsonObject
            val layout = root["layout"] as? JsonObject
            val shot = ImageIO.read(File(json.path.removeSuffix(".json") + ".png"))
            val argb = IntArray(w * h)
            shot.getRGB(0, 0, w, minOf(h, shot.height), argb, 0, w)
            val raster = GlassesRasterizer.rasterize(LayoutParser.capture(w, h, argb, layout))
            if (row == 0) title = (root["title"] as? JsonPrimitive)?.content.orEmpty().take(70) + " · " + host(root)
            val y0 = head + row * rowH
            val scrolled = (root["scrollY"] as? JsonPrimitive)?.intOrNull ?: 0
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
            g.color = Color(200, 200, 200)
            g.drawString("Web-Seite (Chromium)" + if (scrolled > 0) " · $scrolled px gescrollt" else " · oben", gap, y0 + 16)
            val r = raster.report
            val note = buildString {
                append("Brille · ${r.textRuns} Textzeilen")
                if (r.negativeRuns > 0) append(" · ${r.negativeRuns} negativ")
                if (r.overloaded) append(" · Fenster überladen")
                append(" · ${"%.0f".format(r.millis)} ms")
            }
            g.drawString(note, gap * 2 + w, y0 + 16)
            g.drawImage(shot.getSubimage(0, 0, w, minOf(h, shot.height)), gap, y0 + label, null)
            for (y in 0 until raster.height) for (x in 0 until raster.width) image.setRGB(gap * 2 + w + x, y0 + label + y, green(raster[x, y]))
            println("$id ${json.name}: $note")
        }
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.color = Color(235, 235, 235)
        g.drawString(title, gap, 24)
        g.dispose()
        ImageIO.write(image, "png", File(out, "$id.png"))
    }

    private fun host(root: JsonObject): String = try {
        URI((root["url"] as JsonPrimitive).content).host.removePrefix("www.")
    } catch (e: Exception) {
        ""
    }

    private fun green(v: Int): Int {
        val level = Levels.of(v) * 17
        return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
    }
}

package ch.madtreasures.g2watch.geckoprobe

import ch.madtreasures.g2watch.webraster.Contrast
import ch.madtreasures.g2watch.webraster.GlassesRaster
import ch.madtreasures.g2watch.webraster.GlassesRasterizer
import ch.madtreasures.g2watch.webraster.Levels
import ch.madtreasures.g2watch.webraster.RasterOptions
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
 * `capture.js` in Chromium (with a second screenshot without text), turned into glasses pictures by
 * [LayoutParser] and web-raster, side by side as `<PREVIEW_DIR>/views/<page>.png`. With
 * `PREVIEW_STYLES=1` also `<page>-stile.png`: the three [Contrast] styles next to each other.
 * Skipped without the environment variable PREVIEW_DIR:
 *
 *     PREVIEW_DIR=/tmp/vorschau ./gradlew :gecko-probe:testArmv7DebugUnitTest --tests '*PagePreviewTest*'
 */
class PagePreviewTest {
    private val w = 576
    private val h = 260
    private val gap = 16
    private val label = 24

    @Test
    fun preview() {
        val path = System.getenv("PREVIEW_DIR")
        assumeTrue("no PREVIEW_DIR", !path.isNullOrBlank())
        val dir = File(path!!)
        val out = File(dir, "views").apply { mkdirs() }
        val styles = System.getenv("PREVIEW_STYLES") == "1"
        val frames = File(dir, "raw").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        frames.groupBy { it.name.substringBeforeLast('-') }.forEach { (id, files) ->
            val shots = files.map(::load)
            sheet(shots, listOf("Brille" to RasterOptions()), File(out, "$id.png"))
            if (styles) {
                val variants = listOf(
                    "Umriss (Standard)" to RasterOptions(contrast = Contrast.OUTLINE),
                    "Leuchtschrift mit Rand" to RasterOptions(contrast = Contrast.HALO),
                    "Platte (bisher)" to RasterOptions(contrast = Contrast.PLATE),
                )
                sheet(shots, variants, File(out, "$id-stile.png"))
            }
        }
    }

    private class Shot(val root: JsonObject, val image: BufferedImage, val argb: IntArray, val textless: IntArray?)

    private fun load(json: File): Shot {
        val root = Json.parseToJsonElement(json.readText()).jsonObject
        val image = ImageIO.read(File(json.path.removeSuffix(".json") + ".png"))
        fun pixels(img: BufferedImage) = IntArray(w * h).also { img.getRGB(0, 0, w, minOf(h, img.height), it, 0, w) }
        val bare = File(json.path.removeSuffix(".json") + "-bare.png").takeIf { it.isFile }?.let { ImageIO.read(it) }
        return Shot(root, image, pixels(image), bare?.let(::pixels))
    }

    /** One row per shot: the page, then one glasses picture per variant. */
    private fun sheet(shots: List<Shot>, variants: List<Pair<String, RasterOptions>>, file: File) {
        val head = 34
        val rowH = label + h + gap
        val image = BufferedImage(w * (variants.size + 1) + gap * (variants.size + 2), head + shots.size * rowH + gap / 2, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 44)
        g.fillRect(0, 0, image.width, image.height)
        shots.forEachIndexed { row, shot ->
            val layout = shot.root["layout"] as? JsonObject
            val y0 = head + row * rowH
            val scrolled = (shot.root["scrollY"] as? JsonPrimitive)?.intOrNull ?: 0
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
            g.color = Color(200, 200, 200)
            g.drawString("Web-Seite (Chromium)" + if (scrolled > 0) " · $scrolled px gescrollt" else " · oben", gap, y0 + 16)
            g.drawImage(shot.image.getSubimage(0, 0, w, minOf(h, shot.image.height)), gap, y0 + label, null)
            variants.forEachIndexed { k, (name, options) ->
                val raster = GlassesRasterizer.rasterize(LayoutParser.capture(w, h, shot.argb, layout, shot.textless), options)
                val x0 = gap * (k + 2) + w * (k + 1)
                g.color = Color(200, 200, 200)
                g.drawString(note(name, raster), x0, y0 + 16)
                for (y in 0 until raster.height) for (x in 0 until raster.width) image.setRGB(x0 + x, y0 + label + y, green(raster[x, y]))
            }
        }
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.color = Color(235, 235, 235)
        val first = shots.first().root
        g.drawString((first["title"] as? JsonPrimitive)?.content.orEmpty().take(70) + " · " + host(first), gap, 24)
        g.dispose()
        ImageIO.write(image, "png", file)
        println("${file.name}: ${shots.size} Aufnahmen")
    }

    private fun note(name: String, raster: GlassesRaster): String {
        val r = raster.report
        return buildString {
            append("$name · ${r.textRuns} Zeilen")
            if (r.negativeRuns > 0) append(" · ${r.negativeRuns} abgesetzt")
            if (r.overloaded) append(" · überladen")
            append(" · ${"%.0f".format(r.millis)} ms")
        }
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

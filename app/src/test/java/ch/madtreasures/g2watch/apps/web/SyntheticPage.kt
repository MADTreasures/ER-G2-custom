package ch.madtreasures.g2watch.apps.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.sin

/**
 * A web page drawn with Java2D as GeckoView would paint it into the browser's surface (pixels of the
 * picture, 1.5 per CSS pixel), with the layout report the extension would send (CSS pixels, CSS
 * colours, as `collectLayout()` of page-layout.js) and the capture without text. Made-up pages only:
 * no third-party content in tests or pictures.
 */
class SyntheticPage(val width: Int = 576, val height: Int = 260, private val ground: Color) {
    private val page = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val bare = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val g = page.createGraphics().apply { hints() }
    private val gb = bare.createGraphics().apply { hints() }
    private val texts = ArrayList<List<Any>>()
    private val pictures = ArrayList<List<Double>>()
    private val surfaces = ArrayList<List<Any>>()

    init {
        both {
            color = ground
            fillRect(0, 0, width, height)
        }
    }

    private fun Graphics2D.hints() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun both(draw: Graphics2D.() -> Unit) {
        g.draw()
        gb.draw()
    }

    private fun css(px: Int): Double = px / WebPicture.DENSITY.toDouble()

    private fun rgb(c: Color) = "rgb(${c.red}, ${c.green}, ${c.blue})"

    /** An element with a background colour. */
    fun surface(x: Int, y: Int, w: Int, h: Int, color: Color): SyntheticPage = apply {
        both {
            this.color = color
            fillRect(x, y, w, h)
        }
        surfaces += listOf(css(x), css(y), css(w), css(h), rgb(color))
    }

    /** A line of text with its top-left at ([x], [y]), [size] pixels high. */
    fun text(s: String, x: Int, y: Int, size: Int, color: Color, bold: Boolean = false): SyntheticPage = apply {
        g.font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
        g.color = color
        val m = g.fontMetrics
        g.drawString(s, x, y + m.ascent)
        texts += listOf(css(x), css(y), css(m.stringWidth(s)), css(m.height), rgb(color))
    }

    /** Lines of text, [lineHeight] apart. */
    fun paragraph(lines: List<String>, x: Int, y: Int, size: Int, lineHeight: Int, color: Color): SyntheticPage = apply {
        lines.forEachIndexed { i, line -> text(line, x, y + i * lineHeight, size, color) }
    }

    /** A made-up photo: sky, sun, mountains, a lake. */
    fun photo(x: Int, y: Int, w: Int, h: Int): SyntheticPage = apply {
        both {
            paint = GradientPaint(x.toFloat(), y.toFloat(), Color(70, 110, 170), x.toFloat(), (y + h).toFloat(), Color(230, 190, 150))
            fillRect(x, y, w, h)
            color = Color(255, 240, 200)
            fillOval(x + w * 7 / 10, y + h / 6, h / 4, h / 4)
            for (px in 0 until w) {
                val ridge = (h * 0.55 + h * 0.12 * sin(px / 37.0) + h * 0.05 * sin(px / 9.0)).toInt()
                color = Color(60, 70, 80)
                fillRect(x + px, y + ridge, 1, h * 3 / 4 - ridge)
                color = Color(40, 70, 100)
                fillRect(x + px, y + h * 3 / 4, 1, h - h * 3 / 4)
            }
        }
        pictures += listOf(css(x), css(y), css(w), css(h))
    }

    fun argb(): IntArray = IntArray(width * height).also { page.getRGB(0, 0, width, height, it, 0, width) }

    fun textless(): IntArray = IntArray(width * height).also { bare.getRGB(0, 0, width, height, it, 0, width) }

    /** The layout report, as the extension sends it. */
    fun layout(): JsonObject = buildJsonObject {
        put("dpr", WebPicture.DENSITY.toDouble())
        put("vw", css(width))
        put("vh", css(height))
        put("texts", buildJsonArray { texts.forEach { t -> add(array(t)) } })
        put("pictures", buildJsonArray { pictures.forEach { p -> add(array(p)) } })
        put("surfaces", buildJsonArray { surfaces.forEach { s -> add(array(s)) } })
        put("page", rgb(ground))
        put("body", "rgba(0, 0, 0, 0)")
    }

    private fun array(values: List<Any>) = buildJsonArray {
        for (v in values) add(if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
    }

    /** The picture the browser would put into its image block. */
    fun raster() = WebPicture.raster(width, height, argb(), layout(), textless())

    companion object {
        /** A news page: a dark bar with the paper's name, a headline, a line, a photo with its caption on it. */
        fun news(): SyntheticPage = SyntheticPage(ground = Color(250, 250, 248)).apply {
            surface(0, 0, 576, 42, Color(0, 51, 102))
            text("TAGBLATT", 16, 7, 26, Color.WHITE, bold = true)
            text("Menü", 500, 11, 20, Color.WHITE)
            text("Brille zeigt jetzt Web-Seiten", 16, 52, 28, Color(20, 20, 20), bold = true)
            text("Dunkle Schrift wird hell, der weisse Grund durchsichtig.", 16, 90, 19, Color(70, 70, 70))
            photo(0, 122, 576, 138)
            text("Bild: Alpen am Morgen", 16, 228, 19, Color.WHITE)
        }

        /** The same article in reading mode: its text alone, light on black (content.js, READER_CSS). */
        fun reading(): SyntheticPage = SyntheticPage(ground = Color.BLACK).apply {
            text("Brille zeigt jetzt Web-Seiten", 15, 12, 33, Color(242, 242, 242), bold = true)
            text("Tagblatt · Von Anna Muster", 15, 58, 22, Color(187, 187, 187))
            paragraph(
                listOf(
                    "Die Uhr lädt die Seite, die Brille zeigt",
                    "sie als grünes Bild. Im Lesemodus bleibt",
                    "nur der Text: gross, hell auf durchsich-",
                    "tigem Grund, ohne Menüs und Werbung.",
                ),
                15, 96, 27, 38, Color(242, 242, 242),
            )
        }
    }
}

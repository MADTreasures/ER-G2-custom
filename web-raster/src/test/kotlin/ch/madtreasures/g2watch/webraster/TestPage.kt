package ch.madtreasures.g2watch.webraster

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.sin

/**
 * A web page drawn with Java2D for tests, remembering what a browser's DOM would report: text
 * lines with their colour, pictures and elements with a background colour.
 */
class TestPage(val width: Int, val height: Int, ground: Color) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val g = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    val texts = mutableListOf<TextRun>()
    val pictures = mutableListOf<Box>()
    val surfaces = mutableListOf<Surface>()

    init {
        rect(0, 0, width, height, ground)
    }

    /** An element with a background colour. */
    fun rect(x: Int, y: Int, w: Int, h: Int, color: Color): TestPage = apply {
        g.color = color
        g.fillRect(x, y, w, h)
        surfaces += Surface(Box(x, y, w, h), color.rgb)
    }

    /** Painted without telling the DOM, like a CSS background image or gradient. */
    fun paint(x: Int, y: Int, w: Int, h: Int, color: Color): TestPage = apply {
        g.color = color
        g.fillRect(x, y, w, h)
    }

    /** One line of text with its top-left at ([x], [y]); returns its box. */
    fun text(s: String, x: Int, y: Int, size: Int, color: Color, bold: Boolean = false): Box {
        g.font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
        g.color = color
        val m = g.fontMetrics
        g.drawString(s, x, y + m.ascent)
        val box = Box(x, y, m.stringWidth(s), m.ascent + m.descent)
        texts += TextRun(box, color.rgb)
        return box
    }

    /** A photo-like picture: a gradient sky, a sun, hills and some texture. */
    fun photo(x: Int, y: Int, w: Int, h: Int, dark: Boolean = false): Box = Box(x, y, w, h).also { box ->
        val top = if (dark) Color(20, 25, 40) else Color(90, 150, 230)
        val bottom = if (dark) Color(60, 50, 70) else Color(250, 240, 200)
        g.paint = GradientPaint(x.toFloat(), y.toFloat(), top, x.toFloat(), (y + h).toFloat(), bottom)
        g.fillRect(x, y, w, h)
        g.color = if (dark) Color(200, 180, 120) else Color(255, 250, 210)
        g.fillOval(x + w * 2 / 3, y + h / 6, h / 4, h / 4)
        g.color = if (dark) Color(30, 40, 30) else Color(60, 120, 60)
        val xs = IntArray(12) { x + it * w / 10 }.also { it[10] = x + w; it[11] = x }
        val ys = IntArray(12) { y + h * 2 / 3 + (sin(it * 1.3) * h / 8).toInt() }.also { it[10] = y + h; it[11] = y + h }
        g.fillPolygon(xs, ys, 12)
        g.stroke = BasicStroke(1f)
        for (i in 0 until w step 5) {
            g.color = if (i % 10 == 0) Color(0, 0, 0, 60) else Color(255, 255, 255, 40)
            g.drawLine(x + i, y + h * 3 / 4, x + i + 3, y + h - 2)
        }
        // Real photos are never flat: a little sensor noise (deterministic).
        var seed = 12345L + x * 31L + y * 17L
        for (py in y until minOf(height, y + h)) for (px in x until minOf(width, x + w)) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val noise = ((seed ushr 33) % 17).toInt() - 8
            val c = image.getRGB(px, py)
            fun ch(v: Int) = (v + noise).coerceIn(0, 255)
            image.setRGB(px, py, (c and 0xFF000000.toInt()) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF))
        }
        pictures += box
    }

    /** The page as a browser engine would hand it over, with or without what the DOM knows. */
    fun capture(withDom: Boolean = true): PageCapture {
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width)
        return if (withDom) PageCapture(width, height, argb, texts.toList(), pictures.toList(), surfaces.toList())
        else PageCapture(width, height, argb)
    }
}

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
 * lines with their colour, pictures and elements with a background colour. Like the render test,
 * it also keeps the page without its text ([bare]), which gives the glyphs exactly.
 */
class TestPage(val width: Int, val height: Int, ground: Color) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    /** Everything but the text. */
    val bare = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val g = image.createGraphics().apply { hints() }
    private val gb = bare.createGraphics().apply { hints() }

    private fun java.awt.Graphics2D.hints() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    /** Draws on the page and on its bare copy. */
    private fun both(draw: java.awt.Graphics2D.() -> Unit) {
        g.draw()
        gb.draw()
    }
    val texts = mutableListOf<TextRun>()
    val pictures = mutableListOf<Box>()
    val surfaces = mutableListOf<Surface>()

    init {
        rect(0, 0, width, height, ground)
    }

    /** An element with a background colour. */
    fun rect(x: Int, y: Int, w: Int, h: Int, color: Color): TestPage = apply {
        both {
            this.color = color
            fillRect(x, y, w, h)
        }
        surfaces += Surface(Box(x, y, w, h), color.rgb)
    }

    /** Painted without telling the DOM, like a CSS background image or gradient. */
    fun paint(x: Int, y: Int, w: Int, h: Int, color: Color): TestPage = apply {
        both {
            this.color = color
            fillRect(x, y, w, h)
        }
    }

    /** A filled circle, painted without telling the DOM (part of a logo or picture). */
    fun disc(cx: Int, cy: Int, r: Int, color: Color): TestPage = apply {
        both {
            this.color = color
            fillOval(cx - r, cy - r, 2 * r, 2 * r)
        }
    }

    /** A word painted as part of a picture (a logo's letters), not reported as text. */
    fun lettering(s: String, x: Int, y: Int, size: Int, color: Color): TestPage = apply {
        both {
            font = Font(Font.SANS_SERIF, Font.BOLD, size)
            this.color = color
            drawString(s, x, y + fontMetrics.ascent)
        }
    }

    /** One line of text with its top-left at ([x], [y]); returns its box. Only on the page, not on [bare]. */
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
        both {
            paint = GradientPaint(x.toFloat(), y.toFloat(), top, x.toFloat(), (y + h).toFloat(), bottom)
            fillRect(x, y, w, h)
            color = if (dark) Color(200, 180, 120) else Color(255, 250, 210)
            fillOval(x + w * 2 / 3, y + h / 6, h / 4, h / 4)
            color = if (dark) Color(30, 40, 30) else Color(60, 120, 60)
            val xs = IntArray(12) { x + it * w / 10 }.also { it[10] = x + w; it[11] = x }
            val ys = IntArray(12) { y + h * 2 / 3 + (sin(it * 1.3) * h / 8).toInt() }.also { it[10] = y + h; it[11] = y + h }
            fillPolygon(xs, ys, 12)
            stroke = BasicStroke(1f)
            for (i in 0 until w step 5) {
                color = if (i % 10 == 0) Color(0, 0, 0, 60) else Color(255, 255, 255, 40)
                drawLine(x + i, y + h * 3 / 4, x + i + 3, y + h - 2)
            }
        }
        // Real photos are never flat: a little sensor noise (deterministic).
        for (img in listOf(image, bare)) {
            var seed = 12345L + x * 31L + y * 17L
            for (py in y until minOf(height, y + h)) for (px in x until minOf(width, x + w)) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                val noise = ((seed ushr 33) % 17).toInt() - 8
                val c = img.getRGB(px, py)
                fun ch(v: Int) = (v + noise).coerceIn(0, 255)
                img.setRGB(px, py, (c and 0xFF000000.toInt()) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF))
            }
        }
        pictures += box
    }

    /** Pixels where the text changed the page: the glyphs, as the double capture finds them. */
    fun glyphMask(): BooleanArray = BooleanArray(width * height) { i ->
        val a = image.getRGB(i % width, i / width)
        val b = bare.getRGB(i % width, i / width)
        maxOf(kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), kotlin.math.abs((a and 0xFF) - (b and 0xFF))) > 96
    }

    /**
     * The page as a browser engine would hand it over, with or without what the DOM knows and the
     * textless capture (which needs the DOM to hide the text).
     */
    fun capture(withDom: Boolean = true, textless: Boolean = withDom): PageCapture {
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width)
        val without = if (textless) IntArray(width * height).also { bare.getRGB(0, 0, width, height, it, 0, width) } else null
        return if (withDom) PageCapture(width, height, argb, texts.toList(), pictures.toList(), surfaces.toList(), without)
        else PageCapture(width, height, argb, textless = without)
    }
}

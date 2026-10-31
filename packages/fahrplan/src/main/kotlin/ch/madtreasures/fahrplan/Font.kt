package ch.madtreasures.fahrplan

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/**
 * Glyphs of one font size for drawing text into the app's pictures (format "navi-font@1" of the Navi package's
 * tools/font.py, rendered from Inter; packages share no code, so this is a copy of Navi's). Characters it lacks
 * become "?".
 */
class Font(bytes: ByteArray) {
    val size: Int
    val ascent: Int
    val descent: Int
    val lineHeight: Int
    private val glyphs = HashMap<Int, Glyph>()
    private val fallback: Glyph

    private class Glyph(val advance16: Int, val w: Int, val h: Int, val left: Int, val top: Int, val bits: ByteArray)

    init {
        val head = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(8).also { head.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "NAVIFONT" || head.readUnsignedByte() != 1) throw IOException("keine Navi-Schrift")
        size = head.readUnsignedByte()
        ascent = head.readShort().toInt()
        descent = head.readShort().toInt()
        lineHeight = head.readShort().toInt()
        val count = head.readUnsignedShort()
        val data = DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, 18, bytes.size - 18)))
        val meta = List(count) {
            intArrayOf(data.readUnsignedShort(), data.readUnsignedShort(), data.readUnsignedByte(), data.readUnsignedByte(), data.readByte().toInt(), data.readByte().toInt())
        }
        for (m in meta) {
            val bits = ByteArray(m[2] * m[3]).also { data.readFully(it) }
            glyphs[m[0]] = Glyph(m[1], m[2], m[3], m[4], m[5], bits)
        }
        fallback = glyphs['?'.code] ?: Glyph(size * 8, 0, 0, 0, 0, ByteArray(0))
    }

    private fun glyph(c: Char) = glyphs[c.code] ?: fallback

    /** Width of [text] in px. */
    fun width(text: String): Double = text.sumOf { glyph(it).advance16 } / 16.0

    /**
     * [text] with its left end at [x] and its baseline at [baseline]. With [halo] > 0 a black rim of that
     * width first clears what lies under the letters, so they read over lines and areas.
     */
    fun draw(r: Raster, text: String, x: Double, baseline: Double, ink: Ink, halo: Int = 0) {
        if (halo > 0) {
            for (dy in -halo..halo) for (dx in -halo..halo) {
                if (dx * dx + dy * dy > halo * halo + 1) continue
                stamp(r, text, x + dx, baseline + dy, Ink.ERASE)
            }
        }
        stamp(r, text, x, baseline, ink)
    }

    /** [text] centred on [cx]. */
    fun drawCentered(r: Raster, text: String, cx: Double, baseline: Double, ink: Ink, halo: Int = 0) =
        draw(r, text, cx - width(text) / 2, baseline, ink, halo)

    /** [text] ending at [right]. */
    fun drawRight(r: Raster, text: String, right: Double, baseline: Double, ink: Ink, halo: Int = 0) =
        draw(r, text, right - width(text), baseline, ink, halo)

    /** The longest start of [text] that fits [maxWidth], with "…" if it had to be cut. */
    fun fit(text: String, maxWidth: Double): String {
        if (width(text) <= maxWidth) return text
        val dots = width("…")
        var end = text.length
        while (end > 0 && width(text.substring(0, end).trimEnd()) + dots > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun stamp(r: Raster, text: String, x: Double, baseline: Double, ink: Ink) {
        var pen16 = 0
        val by = baseline.roundToInt()
        for (c in text) {
            val g = glyph(c)
            val gx = (x + pen16 / 16.0).roundToInt() + g.left
            val gy = by - g.top
            for (yy in 0 until g.h) {
                val ty = gy + yy
                if (ty < 0 || ty >= r.h) continue
                for (xx in 0 until g.w) {
                    val a = g.bits[yy * g.w + xx].toInt() and 0xFF
                    if (a != 0) r.paint(gx + xx, ty, ink, a / 255.0)
                }
            }
            pen16 += g.advance16
        }
    }

    companion object {
        /** The fonts the app draws with, by size: small notes and badges (Medium), names (SemiBold), times (SemiBold). */
        const val SMALL = "inter-15"
        const val LABEL = "inter-20"
        const val TEXT = "inter-26"
    }
}

/** The app's fonts, loaded once from its assets. */
class Fonts(load: (String) -> ByteArray?) {
    private val fonts = HashMap<String, Font>()
    private val loader = load

    @Synchronized
    operator fun get(name: String): Font = fonts.getOrPut(name) {
        Font(loader("apps/${FahrplanApp.ID}/fonts/$name.font") ?: throw IOException("Schrift $name fehlt"))
    }

    val small get() = this[Font.SMALL]
    val label get() = this[Font.LABEL]
    val text get() = this[Font.TEXT]
}

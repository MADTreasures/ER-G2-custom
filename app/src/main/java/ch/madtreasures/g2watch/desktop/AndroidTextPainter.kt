package ch.madtreasures.g2watch.desktop

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * [TextPainter] with Android's font renderer. Text is drawn anti-aliased into an ALPHA_8 bitmap
 * and its coverage is copied into the raster, scaled to the requested gray value. Where the text
 * overlaps something brighter, the brighter pixel stays, so text never erases a frame line.
 *
 * Emoji ([EmojiText]) are drawn with [emoji], a black-and-white emoji font ([emojiFont]): the
 * system's emoji are colour pictures, and of those the coverage keeps only the silhouette, a
 * filled blob. Without that font emoji look as the system draws them. Runs of text and emoji are
 * placed left to right, in the order they are written. Used from the desktop thread only.
 */
class AndroidTextPainter(emoji: Typeface? = null) : TextPainter {
    private val regular = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL))
    private val bold = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
    private val emojiRegular = emoji?.let { paint(it) }
    private val emojiBold = emoji?.let { paint(it).apply { fontVariationSettings = "'wght' 700" } }
    private var scratch: Bitmap? = null
    private var coverage = ByteArray(0)

    override fun measure(text: String, sizePx: Int, bold: Boolean): Int {
        var width = 0f
        forEachRun(text) { run, emoji -> width += paintFor(sizePx, bold, emoji).measureText(run) }
        return ceil(width).toInt()
    }

    override fun draw(target: GrayRaster, text: String, x: Int, y: Int, sizePx: Int, value: Int, bold: Boolean) {
        if (text.isEmpty()) return
        val width = measure(text, sizePx, bold) + 2
        val height = lineHeight(sizePx)
        val bitmap = scratchBitmap(width, height)
        bitmap.eraseColor(Color.TRANSPARENT)
        val metrics = paintFor(sizePx, bold).fontMetrics
        // Centre the glyphs' ascent-descent box vertically in the line box.
        val baseline = (height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        val canvas = Canvas(bitmap)
        var pen = 1f
        forEachRun(text) { run, emoji ->
            val paint = paintFor(sizePx, bold, emoji)
            canvas.drawText(run, pen, baseline, paint)
            pen += paint.measureText(run)
        }

        val stride = bitmap.rowBytes
        val needed = stride * bitmap.height
        if (coverage.size < needed) coverage = ByteArray(needed)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(coverage, 0, needed))

        val level = value.coerceIn(0, 255)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val alpha = coverage[row * stride + col].toInt() and 0xff
                if (alpha != 0) target.lighten(x - 1 + col, y + row, alpha * level / 255)
            }
        }
    }

    /**
     * Calls [block] for the runs of text and of emoji in [text], in order. Text without emoji, or
     * a painter without emoji font, is one text run. Emoji runs lose their U+FE0F: the emoji font
     * has no variation sequences, and for "☺️" or "1️⃣" Android would pick the colour font again.
     */
    private inline fun forEachRun(text: String, block: (run: String, emoji: Boolean) -> Unit) {
        val emoji = if (emojiRegular == null) emptyList() else EmojiText.ranges(text)
        if (emoji.isEmpty()) return block(text, false)
        var at = 0
        for (range in emoji) {
            if (range.first > at) block(text.substring(at, range.first), false)
            block(text.substring(range.first, range.last + 1).replace(EMOJI_STYLE, ""), true)
            at = range.last + 1
        }
        if (at < text.length) block(text.substring(at), false)
    }

    /** One bitmap that grows as needed; a call uses only its top-left [width] × [height]. */
    private fun scratchBitmap(width: Int, height: Int): Bitmap {
        val current = scratch
        if (current != null && current.width >= width && current.height >= height) return current
        val grown = createBitmap(
            maxOf(width, current?.width ?: 0),
            maxOf(height, current?.height ?: 0),
            Bitmap.Config.ALPHA_8,
        )
        current?.recycle()
        scratch = grown
        return grown
    }

    private fun paintFor(sizePx: Int, bold: Boolean, emoji: Boolean = false): Paint {
        val paint = when {
            emoji && bold -> emojiBold
            emoji -> emojiRegular
            bold -> this.bold
            else -> regular
        } ?: regular
        return paint.apply { textSize = sizePx.toFloat() }
    }

    companion object {
        /**
         * The black-and-white emoji font in the app's assets: Noto Emoji by Google, SIL Open Font
         * License 1.1 (`NotoEmoji-OFL.txt` next to it).
         */
        const val EMOJI_FONT = "fonts/NotoEmoji.ttf"

        private const val EMOJI_STYLE = "\uFE0F"

        /**
         * The emoji font from [assets], or null if it cannot be read (then emoji look as the system
         * draws them). Load it once and share it: it is 2 MB.
         */
        fun emojiFont(assets: AssetManager): Typeface? = try {
            Typeface.createFromAsset(assets, EMOJI_FONT)
        } catch (e: RuntimeException) {
            null
        }

        private fun paint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = Color.WHITE
        }
    }
}

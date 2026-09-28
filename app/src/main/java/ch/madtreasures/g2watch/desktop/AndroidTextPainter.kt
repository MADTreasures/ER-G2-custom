package ch.madtreasures.g2watch.desktop

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
 * Used from the desktop thread only.
 */
class AndroidTextPainter : TextPainter {
    private val regular = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL))
    private val bold = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
    private var scratch: Bitmap? = null
    private var coverage = ByteArray(0)

    override fun measure(text: String, sizePx: Int, bold: Boolean): Int =
        ceil(paintFor(sizePx, bold).measureText(text)).toInt()

    override fun draw(target: GrayRaster, text: String, x: Int, y: Int, sizePx: Int, value: Int, bold: Boolean) {
        if (text.isEmpty()) return
        val paint = paintFor(sizePx, bold)
        val width = measure(text, sizePx, bold) + 2
        val height = lineHeight(sizePx)
        val bitmap = scratchBitmap(width, height)
        bitmap.eraseColor(Color.TRANSPARENT)
        val metrics = paint.fontMetrics
        // Centre the glyphs' ascent-descent box vertically in the line box.
        val baseline = (height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        Canvas(bitmap).drawText(text, 1f, baseline, paint)

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

    private fun paintFor(sizePx: Int, bold: Boolean): Paint =
        (if (bold) this.bold else regular).apply { textSize = sizePx.toFloat() }

    private companion object {
        fun paint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = Color.WHITE
        }
    }
}

package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Anti-aliased shapes on a [GrayRaster]: each pixel is covered by the shape to a degree between 0 and 1
 * (from a signed distance, like Faceclaw's rasteriser), and blends towards the shape's value by that
 * much. So edges keep a clean look even after the glasses round everything to 16 levels.
 */
object Shapes {
    /** A filled rectangle with round corners of radius [r]. */
    fun fillRoundRect(target: GrayRaster, x: Float, y: Float, w: Float, h: Float, r: Float, value: Int) {
        if (w <= 0f || h <= 0f) return
        val rad = r.coerceIn(0f, min(w, h) / 2)
        cover(target, x, y, x + w, y + h, value) { px, py -> 0.5f - roundRectDistance(px, py, x, y, w, h, rad) }
    }

    /** The outline of a rounded rectangle, [t] pixels wide, inside the given bounds. */
    fun strokeRoundRect(target: GrayRaster, x: Float, y: Float, w: Float, h: Float, r: Float, t: Float, value: Int) {
        if (w <= 0f || h <= 0f || t <= 0f) return
        val rad = r.coerceIn(0f, min(w, h) / 2)
        cover(target, x, y, x + w, y + h, value) { px, py ->
            val d = roundRectDistance(px, py, x, y, w, h, rad)
            // Inside the band -t..0 of the distance field.
            0.5f - (abs(d + t / 2) - t / 2)
        }
    }

    fun fillCircle(target: GrayRaster, cx: Float, cy: Float, r: Float, value: Int) {
        if (r <= 0f) return
        cover(target, cx - r, cy - r, cx + r, cy + r, value) { px, py -> 0.5f - (hypot(px - cx, py - cy) - r) }
    }

    /** Line segments through [points] (x0, y0, x1, y1, …), [width] wide, with round ends and joins. */
    fun polyline(target: GrayRaster, points: FloatArray, width: Float, value: Int) {
        if (points.size < 4) return
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            x0 = min(x0, points[i]); x1 = max(x1, points[i])
            y0 = min(y0, points[i + 1]); y1 = max(y1, points[i + 1])
        }
        val half = width / 2
        cover(target, x0 - half, y0 - half, x1 + half, y1 + half, value) { px, py ->
            var best = Float.MAX_VALUE
            for (i in 0 until points.size - 2 step 2) {
                best = min(best, segmentDistance(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]))
            }
            0.5f - (best - half)
        }
    }

    /** Blends [value] into the pixel by [alpha] (0–1). */
    fun blend(target: GrayRaster, x: Int, y: Int, value: Int, alpha: Float) {
        if (x !in 0 until target.width || y !in 0 until target.height || alpha <= 0f) return
        val old = target[x, y]
        target[x, y] = if (alpha >= 1f) value else (old + (value - old) * alpha).roundToInt()
    }

    /** Runs [coverage] (pixel centre → 0…1 after clamping) over the pixels of the bounds and blends. */
    private inline fun cover(target: GrayRaster, x0: Float, y0: Float, x1: Float, y1: Float, value: Int, coverage: (Float, Float) -> Float) {
        val left = max(0, floor(x0).toInt() - 1)
        val top = max(0, floor(y0).toInt() - 1)
        val right = min(target.width, ceil(x1).toInt() + 1)
        val bottom = min(target.height, ceil(y1).toInt() + 1)
        for (py in top until bottom) {
            for (px in left until right) {
                val a = coverage(px + 0.5f, py + 0.5f).coerceIn(0f, 1f)
                if (a > 0f) blend(target, px, py, value, a)
            }
        }
    }

    /** Signed distance to a rounded rectangle: negative inside. */
    private fun roundRectDistance(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float, r: Float): Float {
        val cx = x + w / 2
        val cy = y + h / 2
        val qx = abs(px - cx) - (w / 2 - r)
        val qy = abs(py - cy) - (h / 2 - r)
        val outside = hypot(max(qx, 0f), max(qy, 0f))
        return outside + min(max(qx, qy), 0f) - r
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }
}

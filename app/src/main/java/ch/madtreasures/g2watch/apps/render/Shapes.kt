package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Anti-aliased shapes for the pages: rounded rectangles, circles and lines, drawn with signed
 * distances. Each pixel is blended towards the value by its coverage; everything clips at the
 * raster edges.
 */
internal object Shapes {

    fun fillRoundRect(t: GrayRaster, x: Float, y: Float, w: Float, h: Float, radius: Float, value: Int) {
        if (w <= 0f || h <= 0f) return
        val r = min(radius, min(w, h) / 2)
        cover(t, x, y, w, h) { px, py -> clamp01(0.5f - roundRectDistance(px, py, x, y, w, h, r)) }.let { paint(t, it, value) }
    }

    /** A frame of [width] pixels inside the rounded rectangle. */
    fun strokeRoundRect(t: GrayRaster, x: Float, y: Float, w: Float, h: Float, radius: Float, width: Float, value: Int) {
        if (w <= 0f || h <= 0f || width <= 0f) return
        val r = min(radius, min(w, h) / 2)
        cover(t, x, y, w, h) { px, py ->
            val d = roundRectDistance(px, py, x, y, w, h, r)
            clamp01(0.5f - d) - clamp01(0.5f - (d + width))
        }.let { paint(t, it, value) }
    }

    fun fillCircle(t: GrayRaster, cx: Float, cy: Float, radius: Float, value: Int) =
        fillRoundRect(t, cx - radius, cy - radius, 2 * radius, 2 * radius, radius, value)

    /** Connected line segments through [points] (x0, y0, x1, y1, …) with round ends. */
    fun polyline(t: GrayRaster, points: FloatArray, width: Float, value: Int) {
        if (points.size < 4) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            minX = min(minX, points[i]); maxX = max(maxX, points[i])
            minY = min(minY, points[i + 1]); maxY = max(maxY, points[i + 1])
        }
        val pad = width / 2 + 1
        cover(t, minX - pad, minY - pad, maxX - minX + 2 * pad, maxY - minY + 2 * pad) { px, py ->
            var best = Float.MAX_VALUE
            for (i in 0 until points.size - 2 step 2) {
                best = min(best, segmentDistance(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]))
            }
            clamp01(width / 2 + 0.5f - best)
        }.let { paint(t, it, value) }
    }

    /** Coverage of every pixel in the box; evaluated at pixel centres. */
    private inline fun cover(t: GrayRaster, x: Float, y: Float, w: Float, h: Float, coverage: (Float, Float) -> Float): Area {
        val x0 = max(0, floor(x).toInt() - 1)
        val y0 = max(0, floor(y).toInt() - 1)
        val x1 = min(t.width, ceil(x + w).toInt() + 1)
        val y1 = min(t.height, ceil(y + h).toInt() + 1)
        if (x0 >= x1 || y0 >= y1) return Area.EMPTY
        val values = FloatArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (py in y0 until y1) for (px in x0 until x1) values[i++] = coverage(px + 0.5f, py + 0.5f)
        return Area(x0, y0, x1 - x0, values)
    }

    private fun paint(t: GrayRaster, area: Area, value: Int) {
        if (area.values.isEmpty()) return
        val v = value.coerceIn(0, 255)
        for (i in area.values.indices) {
            val c = area.values[i]
            if (c <= 0f) continue
            val px = area.x + i % area.w
            val py = area.y + i / area.w
            val old = t[px, py]
            t[px, py] = if (c >= 1f) v else (old + (v - old) * c).roundToInt()
        }
    }

    private class Area(val x: Int, val y: Int, val w: Int, val values: FloatArray) {
        companion object {
            val EMPTY = Area(0, 0, 1, FloatArray(0))
        }
    }

    /** Signed distance to a rounded rectangle; negative inside. */
    private fun roundRectDistance(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float, r: Float): Float {
        val qx = abs(px - (x + w / 2)) - (w / 2 - r)
        val qy = abs(py - (y + h / 2)) - (h / 2 - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / len2
        val c = t.coerceIn(0f, 1f)
        val ex = px - (ax + c * dx)
        val ey = py - (ay + c * dy)
        return sqrt(ex * ex + ey * ey)
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
}

package ch.madtreasures.navi

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A grey picture for the glasses: one byte per pixel, 0 is black (see-through on the lens), 255 the
 * brightest green. Drawing never adds up: [light] brightens a pixel to at least a value, [darken] pulls it
 * towards black, so lines that cross or overlap stay even. Edges are smooth (coverage per pixel).
 */
class Raster(val w: Int, val h: Int) {
    val px = ByteArray(w * h)

    operator fun get(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, v: Int) {
        px[y * w + x] = v.coerceIn(0, 255).toByte()
    }

    /** Brightens ([x], [y]) to at least [value] × [coverage]. */
    fun light(x: Int, y: Int, value: Int, coverage: Double) {
        if (x < 0 || y < 0 || x >= w || y >= h || coverage <= 0) return
        val v = (value * min(coverage, 1.0) + 0.5).toInt()
        val i = y * w + x
        if (v > (px[i].toInt() and 0xFF)) px[i] = v.toByte()
    }

    /** Darkens ([x], [y]) by [coverage] (1 = black). */
    fun darken(x: Int, y: Int, coverage: Double) {
        if (x < 0 || y < 0 || x >= w || y >= h || coverage <= 0) return
        val i = y * w + x
        px[i] = ((px[i].toInt() and 0xFF) * (1 - min(coverage, 1.0)) + 0.5).toInt().toByte()
    }

    fun paint(x: Int, y: Int, ink: Ink, coverage: Double) = if (ink.value < 0) darken(x, y, coverage) else light(x, y, ink.value, coverage)

    fun clear() = px.fill(0)

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, ink: Ink) {
        for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) paint(x, y, ink, 1.0)
    }

    /** [other] at ([x], [y]), pixel for pixel (its black too). */
    fun blit(other: Raster, x: Int, y: Int) {
        for (yy in 0 until other.h) {
            val ty = y + yy
            if (ty < 0 || ty >= h) continue
            for (xx in 0 until other.w) {
                val tx = x + xx
                if (tx in 0 until w) px[ty * w + tx] = other.px[yy * other.w + xx]
            }
        }
    }

    // --- Lines ---------------------------------------------------------------------------------------

    /** A line [width] px wide from ([ax], [ay]) to ([bx], [by]), with round ends. */
    fun line(ax: Double, ay: Double, bx: Double, by: Double, width: Double, ink: Ink) {
        val r = width / 2
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val len = sqrt(len2)
        val reach = r + 1
        val y0 = max(0, floor(min(ay, by) - reach).toInt())
        val y1 = min(h - 1, ceil(max(ay, by) + reach).toInt())
        val xMin = max(0, floor(min(ax, bx) - reach).toInt())
        val xMax = min(w - 1, ceil(max(ax, bx) + reach).toInt())
        if (y0 > y1 || xMin > xMax) return
        for (y in y0..y1) {
            val py = y + 0.5
            var x0 = xMin
            var x1 = xMax
            // Only where the band around the line's direction can reach.
            if (len > 0 && abs(dy) > 1e-9) {
                val base = ax + (py - ay) * dx / dy
                val spread = reach * len / abs(dy)
                x0 = max(x0, floor(base - spread).toInt())
                x1 = min(x1, ceil(base + spread).toInt())
            } else if (len > 0 && abs(py - ay) > reach) {
                continue
            }
            for (x in x0..x1) {
                val pxc = x + 0.5
                val t = if (len2 <= 1e-12) 0.0 else (((pxc - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx - pxc
                val cy = ay + t * dy - py
                val coverage = r + 0.5 - sqrt(cx * cx + cy * cy)
                if (coverage > 0) paint(x, y, ink, coverage)
            }
        }
    }

    /**
     * A line through [n] points ([xs], [ys]); with [dash] (on, off, on, off … in px) a dashed one. Round
     * joints come from the round ends of its pieces.
     */
    fun polyline(xs: DoubleArray, ys: DoubleArray, n: Int, width: Double, ink: Ink, dash: DoubleArray? = null) {
        if (n < 2) {
            if (n == 1) line(xs[0], ys[0], xs[0], ys[0], width, ink)
            return
        }
        if (dash == null) {
            for (i in 1 until n) line(xs[i - 1], ys[i - 1], xs[i], ys[i], width, ink)
            return
        }
        var phase = 0
        var left = dash[0]
        for (i in 1 until n) {
            var ax = xs[i - 1]
            var ay = ys[i - 1]
            val bx = xs[i]
            val by = ys[i]
            var seg = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            while (seg > 1e-9) {
                val step = min(left, seg)
                val f = step / seg
                val cx = ax + (bx - ax) * f
                val cy = ay + (by - ay) * f
                if (phase % 2 == 0) line(ax, ay, cx, cy, width, ink)
                ax = cx
                ay = cy
                seg -= step
                left -= step
                if (left <= 1e-9) {
                    phase = (phase + 1) % dash.size
                    left = dash[phase]
                }
            }
        }
    }

    fun circle(cx: Double, cy: Double, radius: Double, ink: Ink) {
        val y0 = max(0, floor(cy - radius - 1).toInt())
        val y1 = min(h - 1, ceil(cy + radius + 1).toInt())
        val x0 = max(0, floor(cx - radius - 1).toInt())
        val x1 = min(w - 1, ceil(cx + radius + 1).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy))
            val coverage = radius + 0.5 - d
            if (coverage > 0) paint(x, y, ink, coverage)
        }
    }

    /** A ring of [width] around ([cx], [cy]). */
    fun ring(cx: Double, cy: Double, radius: Double, width: Double, ink: Ink) {
        val y0 = max(0, floor(cy - radius - width).toInt())
        val y1 = min(h - 1, ceil(cy + radius + width).toInt())
        val x0 = max(0, floor(cx - radius - width).toInt())
        val x1 = min(w - 1, ceil(cx + radius + width).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy))
            val coverage = width / 2 + 0.5 - abs(d - radius)
            if (coverage > 0) paint(x, y, ink, coverage)
        }
    }

    // --- Areas ---------------------------------------------------------------------------------------

    /**
     * Fills the area inside [rings] (x, y, x, y … in px; holes are rings inside rings, even-odd), with
     * smooth edges: four lines per pixel row and the exact share across.
     */
    fun polygon(rings: List<DoubleArray>, ink: Ink) {
        var count = 0
        for (r in rings) count += r.size / 2
        if (count < 3) return
        val ex0 = DoubleArray(count)
        val ey0 = DoubleArray(count)
        val ey1 = DoubleArray(count)
        val slope = DoubleArray(count)
        var n = 0
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        for (r in rings) {
            val m = r.size / 2
            for (i in 0 until m) {
                val j = (i + 1) % m
                val x0 = r[2 * i]
                val y0 = r[2 * i + 1]
                val x1 = r[2 * j]
                val y1 = r[2 * j + 1]
                minX = min(minX, x0)
                maxX = max(maxX, x0)
                if (y0 == y1) continue
                val (ax, ay, by) = if (y0 < y1) Triple(x0, y0, y1) else Triple(x1, y1, y0)
                ex0[n] = ax
                ey0[n] = ay
                ey1[n] = by
                slope[n] = (x1 - x0) / (y1 - y0)
                minY = min(minY, ay)
                maxY = max(maxY, by)
                n++
            }
        }
        if (n == 0) return
        val rowStart = max(0, floor(minY).toInt())
        val rowEnd = min(h - 1, ceil(maxY).toInt())
        if (rowStart > rowEnd || maxX < 0 || minX >= w) return
        val order = (0 until n).sortedBy { ey0[it] }.toIntArray()
        val active = IntArray(n)
        var activeCount = 0
        var next = 0
        val cover = DoubleArray(w)
        val xs = DoubleArray(n)
        val cx0 = max(0, floor(minX).toInt())
        val cx1 = min(w - 1, ceil(maxX).toInt())
        for (y in rowStart..rowEnd) {
            for (s in 0 until SUB) {
                val sy = y + (s + 0.5) / SUB
                while (next < n && ey0[order[next]] <= sy) active[activeCount++] = order[next++]
                var k = 0
                var found = 0
                while (k < activeCount) {
                    val e = active[k]
                    if (ey1[e] <= sy) {
                        active[k] = active[--activeCount]
                        continue
                    }
                    if (ey0[e] <= sy) xs[found++] = ex0[e] + (sy - ey0[e]) * slope[e]
                    k++
                }
                if (found < 2) continue
                xs.sort(0, found)
                var i = 0
                while (i + 1 < found) {
                    span(cover, xs[i], xs[i + 1], 1.0 / SUB)
                    i += 2
                }
            }
            for (x in cx0..cx1) {
                val c = cover[x]
                if (c > 0) {
                    paint(x, y, ink, c)
                    cover[x] = 0.0
                }
            }
        }
    }

    private fun span(cover: DoubleArray, a: Double, b: Double, weight: Double) {
        val xa = max(0.0, a)
        val xb = min(w.toDouble(), b)
        if (xb <= xa) return
        val ia = xa.toInt()
        val ib = xb.toInt()
        if (ia == ib) {
            cover[ia] += weight * (xb - xa)
            return
        }
        cover[ia] += weight * (ia + 1 - xa)
        for (i in ia + 1 until ib) cover[i] += weight
        if (ib < w) cover[ib] += weight * (xb - ib)
    }

    /** As the glasses show it: one of 16 levels per pixel. */
    fun level(x: Int, y: Int): Int = (this[x, y] * 15 + 127) / 255

    /** A PNG of 16 grey levels, 4 bits per pixel: exactly what the glasses can show, and small ([pngLevel]). */
    fun png(): ByteArray {
        val rowBytes = (w + 1) / 2
        val raw = ByteArray((rowBytes + 1) * h)
        var o = 0
        for (y in 0 until h) {
            // No PNG filter: with two pixels per byte none of them packs map pictures better.
            raw[o++] = 0
            for (x in 0 until w step 2) {
                val hi = pngLevel(x, y)
                val lo = if (x + 1 < w) pngLevel(x + 1, y) else 0
                raw[o++] = ((hi shl 4) or lo).toByte()
            }
        }
        val deflater = Deflater(9)
        deflater.setInput(raw)
        deflater.finish()
        val zipped = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) zipped.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().apply {
            write(int32(w))
            write(int32(h))
            write(byteArrayOf(4, 0, 0, 0, 0)) // 4 bits, grey, deflate, no filter method, no interlace
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", zipped.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The PNG as `data:` address for an image block (at most [MAX_DATA_URL] characters). */
    fun dataUrl(): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png())

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(int32(data.size))
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.write(int32(crc.value.toInt()))
    }

    /**
     * The level a pixel gets in the PNG: levels 1 and 2 only come from the faint rims of smooth edges,
     * which the lens hardly shows; leaving them black makes the file a sixth smaller.
     */
    private fun pngLevel(x: Int, y: Int): Int = level(x, y).let { if (it < MIN_LEVEL) 0 else it }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        /** Lines per pixel row when filling areas. */
        private const val SUB = 4

        /** The faintest level a PNG keeps (see [png]); what the app means to show is never fainter. */
        const val MIN_LEVEL = 3

        /** What an image block's `data:` address may hold (Block.DATA_URL_MAX of the app interface). */
        const val MAX_DATA_URL = 48 * 1024

        /** Grey value of level [n] of the glasses' 16 (0–15). */
        fun level(n: Int): Int = if (n >= 15) 255 else n * 17
    }
}

/** How to paint: brighten to [value] (0–255), or with a negative value darken towards black. */
@JvmInline
value class Ink(val value: Int) {
    companion object {
        val ERASE = Ink(-1)

        /** Level [n] of the glasses' 16. */
        fun level(n: Int) = Ink(Raster.level(n))
    }
}

package ch.madtreasures.g2watch.desktop

/**
 * 8-bit gray pixels, row-major. The glasses show the top 4 bits as 16 shades of green, and
 * black emits no light, so black is see-through on the lens. Drawing clips at the edges.
 */
class GrayRaster(val width: Int, val height: Int) {
    val pixels = ByteArray(width * height)

    init {
        require(width > 0 && height > 0)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = value.toByte()
    }

    fun clear(value: Int = 0) {
        pixels.fill(value.toByte())
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, value: Int) {
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val x1 = (x + w).coerceAtMost(width)
        val y1 = (y + h).coerceAtMost(height)
        if (x0 >= x1 || y0 >= y1) return
        val v = value.toByte()
        for (row in y0 until y1) pixels.fill(v, row * width + x0, row * width + x1)
    }

    fun fillRect(rect: Rect, value: Int) = fillRect(rect.x, rect.y, rect.w, rect.h, value)

    /** A frame of [thickness] pixels drawn inside [rect]. */
    fun strokeRect(rect: Rect, value: Int, thickness: Int = 1) {
        val t = thickness.coerceAtMost(minOf(rect.w, rect.h) / 2).coerceAtLeast(1)
        fillRect(rect.x, rect.y, rect.w, t, value)
        fillRect(rect.x, rect.y + rect.h - t, rect.w, t, value)
        fillRect(rect.x, rect.y + t, t, rect.h - 2 * t, value)
        fillRect(rect.x + rect.w - t, rect.y + t, t, rect.h - 2 * t, value)
    }

    /** Keeps the brighter of the old and the new value, the way light adds up on the lens. */
    fun lighten(x: Int, y: Int, value: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        if (value > (pixels[i].toInt() and 0xFF)) pixels[i] = value.toByte()
    }

    /** Copies a [w]×[h] block starting at ([x], [y]) into a new array. */
    fun copyRegion(x: Int, y: Int, w: Int, h: Int): ByteArray {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height)
        val out = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, out, row * w, w)
        return out
    }

    /** 64-bit FNV-1a over the pixels; used as the content fingerprint Faceclaw's compositor needs. */
    fun contentHash(): Long = fnv1a64(pixels)
}

/** 64-bit FNV-1a, for content fingerprints. */
fun fnv1a64(bytes: ByteArray): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (b in bytes) {
        hash = hash xor (b.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash
}

/** Axis-aligned rectangle in screen pixels. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun contains(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h

    fun inset(d: Int): Rect = Rect(x + d, y + d, w - 2 * d, h - 2 * d)
}

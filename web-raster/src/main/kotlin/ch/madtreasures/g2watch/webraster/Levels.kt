package ch.madtreasures.g2watch.webraster

import kotlin.math.roundToInt

/**
 * The 16 levels of the glasses. The core rounds 8-bit gray with `min(15, (v + 8) >> 4)` (BmpUtil);
 * a level n is written as gray n × 16, and level 15 as 255 (02 §4.2).
 */
object Levels {
    const val COUNT = 16

    fun of(gray: Int): Int = minOf(15, (gray.coerceIn(0, 255) + 8) shr 4)

    fun gray(level: Int): Int = if (level >= 15) 255 else level.coerceAtLeast(0) * 16

    /** [gray] rounded to the nearest value the glasses can show. */
    fun round(gray: Int): Int = gray(of(gray))

    /** Relative luminance (sRGB weights) of an ARGB colour, 0–255; transparency counts as white paper. */
    fun luma(argb: Int): Int {
        val a = argb ushr 24 and 0xFF
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return (y * a / 255f + 255f * (255 - a) / 255f).roundToInt().coerceIn(0, 255)
    }
}

/**
 * Floyd–Steinberg error diffusion to the 16 levels: smooth pictures instead of bands. Only pixels
 * with [mask] set are changed, and error is only passed between them.
 */
object Dither {
    fun toLevels(values: FloatArray, width: Int, height: Int, mask: BooleanArray? = null, out: IntArray) {
        val err = values.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (mask != null && !mask[i]) continue
                val v = err[i].coerceIn(0f, 255f)
                val q = Levels.round(v.roundToInt())
                out[i] = q
                val e = v - q
                if (x + 1 < width) spread(err, mask, i + 1, e * 7f / 16)
                if (y + 1 < height) {
                    val below = i + width
                    if (x > 0) spread(err, mask, below - 1, e * 3f / 16)
                    spread(err, mask, below, e * 5f / 16)
                    if (x + 1 < width) spread(err, mask, below + 1, e * 1f / 16)
                }
            }
        }
    }

    private fun spread(err: FloatArray, mask: BooleanArray?, j: Int, amount: Float) {
        if (mask == null || mask[j]) err[j] += amount
    }

    /**
     * 8-bit gray pixels for an image block: photos (many different grays) are dithered, flat
     * graphics are only rounded, so their areas stay calm.
     */
    fun forGlasses(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val seen = BooleanArray(256)
        var distinct = 0
        for (p in pixels) {
            val v = p.toInt() and 0xFF
            if (!seen[v]) {
                seen[v] = true
                if (++distinct > PHOTO_GRAYS) return gray(pixels, width, height)
            }
        }
        return ByteArray(pixels.size) { Levels.round(pixels[it].toInt() and 0xFF).toByte() }
    }

    /** More different grays than this make a picture a photo. */
    const val PHOTO_GRAYS = 32

    /** 8-bit gray pixels dithered to the glasses' levels. */
    fun gray(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val values = FloatArray(pixels.size) { (pixels[it].toInt() and 0xFF).toFloat() }
        val out = IntArray(pixels.size)
        toLevels(values, width, height, null, out)
        return ByteArray(out.size) { out[it].toByte() }
    }
}

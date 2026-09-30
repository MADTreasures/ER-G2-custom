package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.desktop.GrayRaster

/** Resizing grey pictures for image blocks. */
object GrayImages {
    /**
     * [source] scaled to exactly [w] × [h]: averaged over the covered source pixels when shrinking (so
     * fine detail turns into grey instead of flicker), nearest pixel when enlarging.
     */
    fun scale(source: GrayRaster, w: Int, h: Int): GrayRaster {
        require(w > 0 && h > 0)
        if (source.width == w && source.height == h) return copy(source)
        val out = GrayRaster(w, h)
        for (y in 0 until h) {
            val sy0 = y * source.height / h
            val sy1 = maxOf(sy0 + 1, (y + 1) * source.height / h)
            for (x in 0 until w) {
                val sx0 = x * source.width / w
                val sx1 = maxOf(sx0 + 1, (x + 1) * source.width / w)
                var sum = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) sum += source[sx, sy]
                out[x, y] = sum / ((sy1 - sy0) * (sx1 - sx0))
            }
        }
        return out
    }

    fun copy(source: GrayRaster): GrayRaster =
        GrayRaster(source.width, source.height).also { source.pixels.copyInto(it.pixels) }
}

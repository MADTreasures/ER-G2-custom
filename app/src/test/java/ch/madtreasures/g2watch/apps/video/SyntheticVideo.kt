package ch.madtreasures.g2watch.apps.video

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Made-up videos for tests and pictures (no real footage in the repo): 8-bit luma pictures of
 * [WIDTH] × [HEIGHT] (240p, what the watch decodes for the glasses) at 30 pictures a second.
 */
enum class SyntheticVideo {
    /** A slow pan over hills and a lake: smooth areas, some detail, everything moves. */
    LANDSCAPE,

    /** Leaves or a crowd: fine detail everywhere, drifting. The hardest case for the link. */
    DETAIL,

    /** Someone talking in front of a still background: little changes. */
    TALK,
    ;

    fun frame(index: Int): ByteArray = when (this) {
        LANDSCAPE -> landscape(index)
        DETAIL -> detail(index)
        TALK -> talk(index)
    }

    companion object {
        const val WIDTH = 426
        const val HEIGHT = 240
        const val FPS = 30

        private val noise = ValueNoise(7)

        private fun landscape(i: Int): ByteArray {
            val out = ByteArray(WIDTH * HEIGHT)
            val pan = i * 1.5
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val wx = x + pan
                val sky = 70 + 90 * y / HEIGHT
                val sun = if (hypot(x - 300.0, y - 60.0) < 26) 250 else 0
                val ridge = 120 + 30 * sin(wx / 45.0) + 14 * sin(wx / 13.0) + 8 * noise.at(wx / 9.0, 3.0)
                val far = 140 + 12 * sin(wx / 70.0 + 1)
                var v = maxOf(sky, sun).toDouble()
                if (y > far) v = 110.0 + 10 * noise.at(wx / 20.0, y / 20.0)
                if (y > ridge) v = 60 + (y - ridge) / 2 + 25 * noise.at(wx / 6.0, y / 6.0)
                if (y > 195) v = 40 + 50 * (0.5 + 0.5 * sin(wx / 5.0 + y * 0.9 + i * 0.4)) * noise.at(wx / 30.0, y / 4.0)
                out[y * WIDTH + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
            return out
        }

        private fun detail(i: Int): ByteArray {
            val out = ByteArray(WIDTH * HEIGHT)
            val dx = i * 2.0
            val dy = i * 0.7
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val v = 128 + 70 * noise.at((x + dx) / 5.0, (y + dy) / 5.0) + 40 * noise.at((x - dx) / 2.0, (y + dy) / 2.0) +
                    20 * sin((x + y + i * 3) / 7.0)
                out[y * WIDTH + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
            return out
        }

        private fun talk(i: Int): ByteArray {
            val out = ByteArray(WIDTH * HEIGHT)
            val t = i / FPS.toDouble()
            val headX = 213 + 6 * sin(t * 1.3)
            val headY = 120 + 3 * sin(t * 2.1)
            val mouth = 4 + 4 * (0.5 + 0.5 * sin(t * 11))
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                var v = 50 + 40 * noise.at(x / 40.0, y / 40.0) + if (x in 40..120 && y in 30..110) 60 else 0
                val hx = (x - headX) / 55
                val hy = (y - headY) / 75
                if (hx * hx + hy * hy < 1) v = 170 + 20 * noise.at(x / 8.0, y / 8.0)
                if (y > headY + 70) v = 90.0 + 10 * sin(x / 9.0)
                if (hypot(x - headX, (y - headY - 30) * 2.5) < mouth * 3) v = 40.0
                if (hypot(x - headX - 20, y - headY + 15) < 6 || hypot(x - headX + 20, y - headY + 15) < 6) v = 30.0
                out[y * WIDTH + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
            return out
        }
    }
}

/** Smooth random values in −1 … 1, the same for the same seed. */
class ValueNoise(seed: Int) {
    private val size = 256
    private val values = DoubleArray(size * size).also { v ->
        val r = Random(seed)
        for (k in v.indices) v[k] = r.nextDouble() * 2 - 1
    }

    fun at(x: Double, y: Double): Double {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun v(ix: Int, iy: Int) = values[(iy and (size - 1)) * size + (ix and (size - 1))]
        val a = v(x0, y0) + (v(x0 + 1, y0) - v(x0, y0)) * fx
        val b = v(x0, y0 + 1) + (v(x0 + 1, y0 + 1) - v(x0, y0 + 1)) * fx
        return a + (b - a) * fy
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
}

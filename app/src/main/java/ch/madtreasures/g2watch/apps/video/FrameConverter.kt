package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.render.Levels
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.Rect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns decoded video pictures into the raster of an image block of [width] × [height] (03 §10).
 *
 * The decoder (or the test pattern) delivers each picture already scaled to the [grid]: one luma value
 * (0–255) per raster point, the picture letterboxed into [pictureRect], black around it. Per picture the
 * converter
 *  1. stretches the contrast between the 2nd and the 98th percentile, smoothed per second (not per
 *     picture) and with a dead band, so the brightness neither pumps nor creeps in a still scene,
 *  2. maps it through the tone curve [GAMMA] to the profile's grey levels, and keeps a point's previous
 *     level unless the new value is clearly past the middle to the next one: calm parts of a video stay
 *     byte for byte the same from picture to picture, which the link to the glasses rewards,
 *  3. draws every raster point as a cell × cell square (one level of the glasses' 16).
 *
 * No dithering: error diffusion makes still parts shimmer (every picture costs KBs more), and a fixed
 * Bayer pattern smooths gradients but laces every surface with a fine grid, for a fifth to a third
 * more bytes in the simulation; not clearly better (03 §10).
 *
 * Not thread-safe: one converter per video, used from one thread at a time.
 */
class FrameConverter(val width: Int, val height: Int, profile: VideoProfile) {
    /** Raster points: [width] × [height] of [cell] pixels each, placed at ([offsetX], [offsetY]) in the block. */
    class Grid(val width: Int, val height: Int, val cell: Int, val offsetX: Int, val offsetY: Int) {
        val size: Int get() = width * height
    }

    var profile: VideoProfile = profile
        set(value) {
            if (value == field) return
            field = value
            look(value.cell, value.levels)
        }

    private var cell = profile.cell
    private var levels = profile.levels

    /**
     * Stretch the contrast and apply the tone curve (steps 1 and 2), right for camera pictures. Off for
     * pictures made for the glasses that already use the full range, like the test video's grey ramp.
     */
    var adjust = true

    var grid: Grid = gridFor(cell)
        private set

    private var previous: ByteArray? = null
    private var lo = -1f
    private var hi = -1f
    private val histogram = IntArray(256)

    /** The luma range the contrast is stretched over now (−1 before the first picture); for tests. */
    internal val contrast: ClosedFloatingPointRange<Float> get() = lo..hi

    init {
        require(width >= 8 && height >= 8) { "picture $width × $height is too small" }
    }

    /** Where a picture of display size [videoW] × [videoH] sits in the grid: as large as fits, centred. */
    fun pictureRect(videoW: Int, videoH: Int): Rect {
        val g = grid
        if (videoW <= 0 || videoH <= 0) return Rect(0, 0, g.width, g.height)
        return if (videoW.toLong() * g.height >= videoH.toLong() * g.width) {
            val h = (g.width.toLong() * videoH / videoW).toInt().coerceIn(1, g.height)
            Rect(0, (g.height - h) / 2, g.width, h)
        } else {
            val w = (g.height.toLong() * videoW / videoH).toInt().coerceIn(1, g.width)
            Rect((g.width - w) / 2, 0, w, g.height)
        }
    }

    /**
     * Converts [luma] (grid width × height values, row by row) into a new raster of the block's size.
     * Only [picture] counts for the contrast; outside it the raster stays black. [elapsedMs] is the time
     * since the previous picture, for the contrast smoothing.
     */
    fun convert(
        luma: ByteArray,
        picture: Rect = Rect(0, 0, grid.width, grid.height),
        elapsedMs: Long = profile.frameMs,
    ): GrayRaster {
        val g = grid
        require(luma.size >= g.size) { "luma has ${luma.size} values, the grid ${g.size}" }
        val levels = levels
        val lut = levelTable(luma, picture, levels, elapsedMs)

        val last = previous?.takeIf { it.size == g.size }
        val next = ByteArray(g.size)
        val px0 = picture.x.coerceIn(0, g.width)
        val px1 = picture.right.coerceIn(px0, g.width)
        val py0 = picture.y.coerceIn(0, g.height)
        val py1 = picture.bottom.coerceIn(py0, g.height)
        for (y in py0 until py1) {
            var i = y * g.width + px0
            for (x in px0 until px1) {
                // Level times 16, so the hysteresis works in sixteenths of a level.
                val c = lut[luma[i].toInt() and 0xFF]
                var q = (c + 8) shr 4
                if (last != null) {
                    val p = last[i].toInt()
                    if (abs(c - p * 16) <= HOLD) q = p
                }
                next[i] = q.toByte()
                i++
            }
        }
        previous = next
        return draw(next, levels)
    }

    /** Raster points of [cell] pixels in [levels] grey levels, apart from the profiles; for measurements. */
    internal fun look(cell: Int, levels: Int) {
        require(cell >= 1 && levels in 2..16)
        this.cell = cell
        this.levels = levels
        grid = gridFor(cell)
        previous = null
    }

    /** Forgets the previous picture, e.g. after a jump, so the next one is not held to it. */
    fun reset() {
        previous = null
        lo = -1f
        hi = -1f
    }

    /**
     * For every luma value the level (0 until [levels]) times 16: contrast stretched between the 2nd and
     * 98th percentile of [picture], then the tone curve [GAMMA].
     */
    private fun levelTable(luma: ByteArray, picture: Rect, levels: Int, elapsedMs: Long): IntArray {
        val top = (levels - 1) * 16
        if (!adjust) return IntArray(256) { v -> (v * top + 127) / 255 }
        val g = grid
        histogram.fill(0)
        var count = 0
        for (y in picture.y.coerceAtLeast(0) until picture.bottom.coerceAtMost(g.height)) {
            var i = y * g.width + picture.x.coerceAtLeast(0)
            repeat(picture.right.coerceAtMost(g.width) - picture.x.coerceAtLeast(0)) {
                histogram[luma[i++].toInt() and 0xFF]++
                count++
            }
        }
        var newLo = 0f
        var newHi = 255f
        if (count > 0) {
            newLo = percentile(count * 2 / 100).toFloat()
            newHi = percentile(count * 98 / 100).toFloat()
        }
        if (newHi - newLo < MIN_RANGE) {
            val mid = ((newLo + newHi) / 2).coerceIn(MIN_RANGE / 2, 255 - MIN_RANGE / 2)
            newLo = mid - MIN_RANGE / 2
            newHi = mid + MIN_RANGE / 2
        }
        if (lo < 0) {
            lo = newLo
            hi = newHi
        } else {
            val share = 1f - (1f - SMOOTHING_PER_SECOND).pow(elapsedMs.coerceAtLeast(0) / 1000f)
            lo = follow(lo, newLo, share)
            hi = follow(hi, newHi, share)
        }
        return IntArray(256) { v ->
            val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            (t.toDouble().pow(GAMMA) * top).roundToInt()
        }
    }

    /**
     * [share] of the way from [current] to [target], or nowhere while [target] is within [DEAD_BAND]:
     * the noise of a still scene does not shift the tone curve and flip points from picture to picture.
     */
    private fun follow(current: Float, target: Float, share: Float): Float =
        if (abs(target - current) <= DEAD_BAND) current else current + (target - current) * share

    private fun percentile(rank: Int): Int {
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen > rank) return v
        }
        return 255
    }

    /** Every raster point as a cell × cell square of its level; the margins of the block stay black. */
    private fun draw(points: ByteArray, levels: Int): GrayRaster {
        val g = grid
        val out = GrayRaster(width, height)
        val grey = ByteArray(levels) { q -> Levels.of((q * 15 + (levels - 1) / 2) / (levels - 1)).toByte() }
        val row = ByteArray(width)
        for (gy in 0 until g.height) {
            var x = g.offsetX
            val base = gy * g.width
            for (gx in 0 until g.width) {
                row.fill(grey[points[base + gx].toInt()], x, x + g.cell)
                x += g.cell
            }
            val y0 = g.offsetY + gy * g.cell
            for (r in 0 until g.cell) System.arraycopy(row, 0, out.pixels, (y0 + r) * width, width)
        }
        return out
    }

    private fun gridFor(cell: Int): Grid {
        val gw = width / cell
        val gh = height / cell
        return Grid(gw, gh, cell, (width - gw * cell) / 2, (height - gh * cell) / 2)
    }

    companion object {
        /**
         * A point changes its level only when the value is this many sixteenths past the old level (8 would
         * be plain rounding). 10 follows slow changes sooner than 13 did and, in the simulation, costs fewer
         * bytes. But it holds less noise: in still scenes of the made-up videos, codec noise of ±2 luma
         * steps flips 1–3 % of the points per picture (±1 none); in a dull scene stretched to [MIN_RANGE],
         * where one luma step is almost a third of a level, ±1 flips about 4 % and ±2 about 16 % (12: none
         * and 7 %, 13: none and 4 %). If that flickers on the glasses, 12 is the next step.
         */
        const val HOLD = 10

        /** Contrast is never stretched further than to this luma range. */
        const val MIN_RANGE = 72f

        /**
         * How fast the contrast follows the picture: share of the difference per second, the same at 5 or
         * 15 pictures a second (a cut is followed within about a second, without pumping).
         */
        const val SMOOTHING_PER_SECOND = 0.75f

        /** Luma steps the 2nd/98th percentile may wander before the contrast follows (sensor and codec noise). */
        const val DEAD_BAND = 4f

        /**
         * The tone curve: level = stretched luma ^ GAMMA. Video luma is coded for screens with a gamma of
         * about 2.2, while the lens adds light about linearly per level (level 1 already gives about 1/15
         * of full, firmware-uebergabe/wissen/02-display.md). Taken as is, mid-tones come out too bright and
         * the picture flat and milky against the see-through background. 1.4 takes them part of the way
         * down and keeps the contrast; a full 2.2 would sink the dark half into the background. Not yet
         * compared on the glasses.
         */
        const val GAMMA = 1.4

        /**
         * Scales a [srcW] × [srcH] luma picture (row stride [stride]) by area averaging into [out], a
         * grid of [gridW] values per row, at [dst]. For tests and pictures that do not come from the GPU.
         */
        fun scaleInto(src: ByteArray, srcW: Int, srcH: Int, stride: Int, out: ByteArray, gridW: Int, dst: Rect) {
            for (y in 0 until dst.h) {
                val sy0 = y * srcH / dst.h
                val sy1 = maxOf(sy0 + 1, (y + 1) * srcH / dst.h)
                for (x in 0 until dst.w) {
                    val sx0 = x * srcW / dst.w
                    val sx1 = maxOf(sx0 + 1, (x + 1) * srcW / dst.w)
                    var sum = 0
                    for (sy in sy0 until sy1) for (sx in sx0 until sx1) sum += src[sy * stride + sx].toInt() and 0xFF
                    out[(dst.y + y) * gridW + dst.x + x] = (sum / ((sy1 - sy0) * (sx1 - sx0))).toByte()
                }
            }
        }
    }
}

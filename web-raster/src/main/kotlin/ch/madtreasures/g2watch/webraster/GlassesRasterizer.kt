package ch.madtreasures.g2watch.webraster

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How text is drawn. */
enum class TextMode {
    /** Bright text on see-through ground; negative where the ground is busy or the window overloaded. */
    AUTO,

    /** Always bright text. */
    NORMAL,

    /** Always negative: dark letters in a lit plate. */
    NEGATIVE,
}

/** Settings of the conversion; the defaults are what the glasses need. */
data class RasterOptions(
    /** Width of the result; the capture is scaled to it (576 = the app area of the glasses). */
    val width: Int = 576,
    val text: TextMode = TextMode.AUTO,
    /** Share of the window that pictures and lit areas may cover before it counts as overloaded. */
    val overloadShare: Float = 0.35f,
    /** Differences to the background below this (0–255) are dropped: faint card edges and shades do not glow. */
    val deadZone: Int = 28,
    /** Brightness of pictures (0–1). */
    val pictureGain: Float = 0.85f,
    /** In an overloaded window a picture is dimmed until its mean brightness (0–255) is at most this. */
    val overloadMean: Int = 80,
    /** Glasses level (0–15) of the plate behind negative text. */
    val plateLevel: Int = 12,
    /** A text line gets a plate when the ground around it is brighter (mean, 0–255) … */
    val busyMean: Int = 70,
    /** … or more restless (standard deviation, 0–255) than this. */
    val busyDeviation: Int = 40,
    /** Guess pictures from the pixels when the capture brings no DOM information at all. */
    val detectPictures: Boolean = true,
)

/** What happened, for tests, the probe's measurements and the log. */
data class RasterReport(
    /** Pictures and lit areas cover more than [RasterOptions.overloadShare] of the window. */
    val overloaded: Boolean,
    val load: Float,
    val pictureShare: Float,
    /** Share of the page (outside pictures) whose ground was light and is now see-through. */
    val lightGroundShare: Float,
    val textRuns: Int,
    val negativeRuns: Int,
    val millis: Double,
)

/** 8-bit gray pixels for the glasses (0 = see-through), every value one of the 16 levels. */
class GlassesRaster(val width: Int, val height: Int, val pixels: ByteArray, val report: RasterReport) {
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * Turns a rendered web page into the picture of the glasses, where lit green is all there is and
 * black is see-through, so that text is always readable:
 *
 * 1. **Ground becomes see-through.** Every pixel outside pictures is measured against the ground
 *    around it (the most common brightness nearby, or the CSS background of its element): white
 *    pages and dark pages alike lose their ground, and what differs from it lights up. Small
 *    differences (shades, card edges) stay dark.
 * 2. **Pictures stay pictures**: positive, contrast-stretched, dithered to the 16 levels.
 * 3. **Text is drawn anew** from its glyphs at full contrast (also blue links or grey captions).
 *    Where the ground around a line is bright or restless (text over a photo), and in a window
 *    **overloaded** with pictures and lit areas, the text turns **negative**: dark letters cut
 *    into a lit plate. Overloaded windows also dim their bright pictures.
 */
object GlassesRasterizer {

    fun rasterize(capture: PageCapture, options: RasterOptions = RasterOptions(), nanoTime: () -> Long = System::nanoTime): GlassesRaster {
        val started = nanoTime()
        val c = if (capture.width == options.width) capture else scale(capture, options.width)
        val w = c.width
        val h = c.height
        val n = w * h
        val lum = IntArray(n) { Levels.luma(c.argb[it]) }

        // Pictures: from the DOM; only a capture without any DOM information is searched for them.
        val picture = BooleanArray(n)
        val boxes = c.pictures.map { it.clip(w, h) }.filter { it.area > 0 }
        for (b in boxes) fill(picture, w, b)
        val noDom = c.pictures.isEmpty() && c.texts.isEmpty() && c.surfaces.isEmpty()
        if (noDom && options.detectPictures) detectPictures(lum, w, h, picture)
        val runs = c.texts.map { it.copy(box = it.box.clip(w, h)) }.filter { it.box.area > 0 }

        // 1. Ground and what stands out of it.
        val ground = groundMap(lum, picture, w, h, c.surfaces)
        val base = FloatArray(n)
        var lightGround = 0
        var surfaceCount = 0
        var lit = 0
        var pictureCount = 0
        for (i in 0 until n) {
            if (picture[i]) {
                pictureCount++
                continue
            }
            surfaceCount++
            val g = ground[i]
            if (g > 127) lightGround++
            val range = max(g, 255 - g).coerceAtLeast(64)
            val d = abs(lum[i] - g)
            val v = if (d < options.deadZone) 0f else ((d - options.deadZone) * 255f / (range - options.deadZone)).coerceIn(0f, 255f)
            base[i] = v
            if (v >= 64f) lit++
        }

        // 2. Pictures, and whether the window is overloaded.
        val load = (pictureCount + lit).toFloat() / n
        val overloaded = load > options.overloadShare
        if (pictureCount > 0) {
            stretchPictures(lum, picture, w, h, boxes, options.pictureGain, if (overloaded) options.overloadMean else 255, base)
        }
        val out = IntArray(n)
        for (i in 0 until n) if (!picture[i]) out[i] = Levels.round(base[i].roundToInt())
        if (pictureCount > 0) Dither.toLevels(base, w, h, picture, out)

        // 3. Text at full contrast, negative where needed.
        var negative = 0
        for (run in runs) {
            if (drawRun(run, lum, base, out, w, h, overloaded, options)) negative++
        }

        val pixels = ByteArray(n) { out[it].toByte() }
        val report = RasterReport(
            overloaded = overloaded,
            load = load,
            pictureShare = pictureCount.toFloat() / n,
            lightGroundShare = if (surfaceCount == 0) 0f else lightGround.toFloat() / surfaceCount,
            textRuns = runs.size,
            negativeRuns = negative,
            millis = (nanoTime() - started) / 1e6,
        )
        return GlassesRaster(w, h, pixels, report)
    }

    // --- Ground ------------------------------------------------------------------------------------

    private const val TILE = 8
    private const val BUCKETS = 32

    /**
     * The ground brightness under every pixel: the most common brightness in the 24 × 24
     * neighbourhood (pictures left out), or the colour of the smallest DOM surface that holds it.
     */
    internal fun groundMap(lum: IntArray, picture: BooleanArray, w: Int, h: Int, surfaces: List<Surface>): IntArray {
        val tw = (w + TILE - 1) / TILE
        val th = (h + TILE - 1) / TILE
        // Histogram per tile, then summed over 3 × 3 tiles.
        val hist = IntArray(tw * th * BUCKETS)
        val sums = LongArray(tw * th * BUCKETS)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (picture[i]) continue
            val t = (y / TILE) * tw + x / TILE
            val b = lum[i] * BUCKETS / 256
            hist[t * BUCKETS + b]++
            sums[t * BUCKETS + b] += lum[i].toLong()
        }
        val tileGround = IntArray(tw * th) { -1 }
        val window = IntArray(BUCKETS)
        val windowSums = LongArray(BUCKETS)
        for (ty in 0 until th) for (tx in 0 until tw) {
            window.fill(0)
            windowSums.fill(0)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = tx + dx
                val ny = ty + dy
                if (nx !in 0 until tw || ny !in 0 until th) continue
                val t = ny * tw + nx
                for (b in 0 until BUCKETS) {
                    window[b] += hist[t * BUCKETS + b]
                    windowSums[b] += sums[t * BUCKETS + b]
                }
            }
            var best = -1
            for (b in 0 until BUCKETS) if (window[b] > 0 && (best < 0 || window[b] > window[best])) best = b
            if (best >= 0) tileGround[ty * tw + tx] = (windowSums[best] / window[best]).toInt()
        }
        fillUnknown(tileGround, tw, th)
        val ground = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) ground[y * w + x] = tileGround[(y / TILE) * tw + x / TILE]
        // The DOM knows the real grounds: the smallest surface wins.
        for (s in surfaces.sortedByDescending { it.box.area }) {
            val b = s.box.clip(w, h)
            if (b.area == 0) continue
            val g = Levels.luma(s.color)
            for (y in b.y until b.bottom) for (x in b.x until b.right) ground[y * w + x] = g
        }
        return ground
    }

    /** Tiles that are all picture take the ground of the nearest known tile; no ground at all is white. */
    private fun fillUnknown(tiles: IntArray, tw: Int, th: Int) {
        if (tiles.none { it < 0 }) return
        if (tiles.all { it < 0 }) {
            tiles.fill(255)
            return
        }
        while (tiles.any { it < 0 }) {
            val next = tiles.copyOf()
            for (ty in 0 until th) for (tx in 0 until tw) {
                if (tiles[ty * tw + tx] >= 0) continue
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nx = tx + dx
                    val ny = ty + dy
                    if (nx in 0 until tw && ny in 0 until th && tiles[ny * tw + nx] >= 0) {
                        next[ty * tw + tx] = tiles[ny * tw + nx]
                        break
                    }
                }
            }
            System.arraycopy(next, 0, tiles, 0, tiles.size)
        }
    }

    // --- Pictures ----------------------------------------------------------------------------------

    /**
     * Without DOM, a 16 × 16 tile counts as picture when its brightness spreads over at least three
     * neighbouring levels (gradients, texture, sensor noise), or over many levels without two
     * dominating ones. Text is two-tone plus a little antialiasing, flat ground is one tone, and
     * the edge between two flat tones or a thin border adds tones far apart, not neighbouring ones.
     * Gaps surrounded by picture are closed.
     */
    internal fun detectPictures(lum: IntArray, w: Int, h: Int, picture: BooleanArray) {
        val size = 16
        val tw = (w + size - 1) / size
        val th = (h + size - 1) / size
        val isPicture = BooleanArray(tw * th)
        val hist = IntArray(BUCKETS)
        for (ty in 0 until th) for (tx in 0 until tw) {
            hist.fill(0)
            var count = 0
            for (y in ty * size until min(h, ty * size + size)) for (x in tx * size until min(w, tx * size + size)) {
                hist[lum[y * w + x] * BUCKETS / 256]++
                count++
            }
            var run = 0
            var longest = 0
            for (b in 0 until BUCKETS) {
                run = if (hist[b] >= count * 0.04f) run + 1 else 0
                longest = max(longest, run)
            }
            val used = hist.count { it >= 2 }
            val sorted = hist.sortedDescending()
            val topTwo = (sorted[0] + sorted[1]).toFloat() / count
            isPicture[ty * tw + tx] = longest >= 3 || (used >= 10 && topTwo < 0.6f)
        }
        val closed = isPicture.copyOf()
        for (ty in 0 until th) for (tx in 0 until tw) {
            if (isPicture[ty * tw + tx]) continue
            var around = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = tx + dx
                val ny = ty + dy
                if (nx in 0 until tw && ny in 0 until th && isPicture[ny * tw + nx]) around++
            }
            if (around >= 5) closed[ty * tw + tx] = true
        }
        for (y in 0 until h) for (x in 0 until w) picture[y * w + x] = closed[(y / size) * tw + x / size]
    }

    /**
     * Positive, stretched between the 2nd and 98th percentile of each picture, times [gain]; bright
     * pictures are dimmed to a mean of at most [maxMean] (dark ones stay as they are).
     */
    private fun stretchPictures(lum: IntArray, picture: BooleanArray, w: Int, h: Int, boxes: List<Box>, gain: Float, maxMean: Int, base: FloatArray) {
        val regions = boxes.ifEmpty { listOf(Box(0, 0, w, h)) }
        val done = BooleanArray(lum.size)
        for (box in regions) {
            val hist = IntArray(256)
            var count = 0
            for (y in box.y until box.bottom) for (x in box.x until box.right) {
                val i = y * w + x
                if (!picture[i] || done[i]) continue
                hist[lum[i]]++
                count++
            }
            if (count == 0) continue
            val lo = percentile(hist, count, 0.02f)
            val hi = percentile(hist, count, 0.98f).coerceAtLeast(lo + 32)
            var sum = 0.0
            for (v in 0..255) sum += hist[v] * ((v - lo) * 255.0 / (hi - lo)).coerceIn(0.0, 255.0)
            val mean = sum / count * gain
            val g = if (mean > maxMean) gain * maxMean / mean.toFloat() else gain
            for (y in box.y until box.bottom) for (x in box.x until box.right) {
                val i = y * w + x
                if (!picture[i] || done[i]) continue
                done[i] = true
                base[i] = ((lum[i] - lo) * 255f / (hi - lo)).coerceIn(0f, 255f) * g
            }
        }
    }

    private fun percentile(hist: IntArray, count: Int, p: Float): Int {
        val target = (count * p).toInt()
        var seen = 0
        for (v in 0..255) {
            seen += hist[v]
            if (seen > target) return v
        }
        return 255
    }

    // --- Text --------------------------------------------------------------------------------------

    /**
     * Draws one line of text; true if negative. The glyphs are found against the line's own
     * ground (its most common brightness), towards the CSS colour or else the far end of its pixels.
     */
    private fun drawRun(run: TextRun, lum: IntArray, base: FloatArray, out: IntArray, w: Int, h: Int, overloaded: Boolean, o: RasterOptions): Boolean {
        val b = run.box
        val hist = IntArray(256)
        for (y in b.y until b.bottom) for (x in b.x until b.right) hist[lum[y * w + x]]++
        val ground = mode(hist, b.area)
        val ink = run.color?.let { Levels.luma(it) }?.takeIf { abs(it - ground) >= 24 }
            ?: farthest(hist, b.area, ground)
        val span = (ink - ground).toFloat()
        val coverage = FloatArray(b.area)
        if (abs(span) >= 12f) {
            for (y in b.y until b.bottom) for (x in b.x until b.right) {
                coverage[(y - b.y) * b.w + (x - b.x)] = ((lum[y * w + x] - ground) / span).coerceIn(0f, 1f)
            }
        }
        // The ground around the letters as the glasses would show it without them.
        val pad = b.inflate(3).clip(w, h)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in pad.y until pad.bottom) for (x in pad.x until pad.right) {
            val inRun = x in b.x until b.right && y in b.y until b.bottom
            if (inRun && coverage[(y - b.y) * b.w + (x - b.x)] > 0.25f) continue
            val v = base[y * w + x].toDouble()
            sum += v
            sumSq += v * v
            count++
        }
        val mean = if (count == 0) 0.0 else sum / count
        val deviation = if (count == 0) 0.0 else sqrt(max(0.0, sumSq / count - mean * mean))
        val negative = when (o.text) {
            TextMode.NORMAL -> false
            TextMode.NEGATIVE -> true
            TextMode.AUTO -> overloaded || mean > o.busyMean || deviation > o.busyDeviation
        }
        if (negative) {
            val plate = Levels.gray(o.plateLevel)
            for (y in pad.y until pad.bottom) for (x in pad.x until pad.right) {
                val inRun = x in b.x until b.right && y in b.y until b.bottom
                val cover = if (inRun) coverage[(y - b.y) * b.w + (x - b.x)] else 0f
                out[y * w + x] = Levels.round((plate * (1f - cover)).roundToInt())
            }
        } else {
            for (y in b.y until b.bottom) for (x in b.x until b.right) {
                val cover = coverage[(y - b.y) * b.w + (x - b.x)]
                val i = y * w + x
                // On a calm ground the text replaces what the ground step made of it.
                val ground = if (cover > 0f) 0 else out[i]
                out[i] = max(ground, Levels.round((cover * 255f).roundToInt()))
            }
        }
        return negative
    }

    private fun mode(hist: IntArray, count: Int): Int {
        // Most common level, from 8-level buckets refined to their mean.
        var best = 0
        var bestCount = -1
        for (bucket in 0 until 32) {
            var c = 0
            for (v in bucket * 8 until bucket * 8 + 8) c += hist[v]
            if (c > bestCount) {
                bestCount = c
                best = bucket
            }
        }
        var s = 0L
        var c = 0
        for (v in best * 8 until best * 8 + 8) {
            s += v.toLong() * hist[v]
            c += hist[v]
        }
        return if (c == 0 || count == 0) 255 else (s / c).toInt()
    }

    /** The brightness at the far end from [ground]: the 3rd or 97th percentile, whichever lies farther. */
    private fun farthest(hist: IntArray, count: Int, ground: Int): Int {
        val dark = percentile(hist, count, 0.03f)
        val light = percentile(hist, count, 0.97f)
        return if (abs(dark - ground) >= abs(light - ground)) dark else light
    }

    // --- Scaling -----------------------------------------------------------------------------------

    /** The capture at [width] (area average when smaller, nearest when larger), boxes along. */
    internal fun scale(c: PageCapture, width: Int): PageCapture {
        val f = width.toFloat() / c.width
        val height = max(1, (c.height * f).roundToInt())
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val sy0 = (y / f).toInt().coerceIn(0, c.height - 1)
            val sy1 = max(sy0 + 1, ((y + 1) / f).toInt()).coerceAtMost(c.height)
            for (x in 0 until width) {
                val sx0 = (x / f).toInt().coerceIn(0, c.width - 1)
                val sx1 = max(sx0 + 1, ((x + 1) / f).toInt()).coerceAtMost(c.width)
                var a = 0L
                var r = 0L
                var g = 0L
                var bl = 0L
                var k = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    val p = c.argb[sy * c.width + sx]
                    a += p ushr 24 and 0xFF
                    r += p shr 16 and 0xFF
                    g += p shr 8 and 0xFF
                    bl += p and 0xFF
                    k++
                }
                out[y * width + x] = ((a / k).toInt() shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (bl / k).toInt()
            }
        }
        return PageCapture(
            width, height, out,
            texts = c.texts.map { it.copy(box = it.box.scaled(f)) },
            pictures = c.pictures.map { it.scaled(f) },
            surfaces = c.surfaces.map { it.copy(box = it.box.scaled(f)) },
        )
    }

    private fun fill(mask: BooleanArray, w: Int, b: Box) {
        for (y in b.y until b.bottom) for (x in b.x until b.right) mask[y * w + x] = true
    }
}

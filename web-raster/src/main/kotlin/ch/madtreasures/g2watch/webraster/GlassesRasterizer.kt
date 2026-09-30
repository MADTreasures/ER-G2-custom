package ch.madtreasures.g2watch.webraster

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How text is drawn. */
enum class TextMode {
    /** Bright text on see-through ground; set apart ([Contrast]) where the ground is busy or the window overloaded. */
    AUTO,

    /** Always bright text. */
    NORMAL,

    /** Always set apart as [RasterOptions.contrast] says. */
    NEGATIVE,
}

/** How a line on a busy ground (a photo, an overloaded window) is set apart from it. */
enum class Contrast {
    /** Negative only at the text: dark letters in a thin lit outline hugging each glyph; the picture stays visible around them. */
    OUTLINE,

    /** Lit letters in a thin see-through outline cut into the picture around each glyph. */
    HALO,

    /** Negative on a plate: dark letters cut into a lit bar behind the whole line. */
    PLATE,
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
    /** How text on a busy ground is set apart. */
    val contrast: Contrast = Contrast.OUTLINE,
    /** Width in pixels of the outline around each glyph (one less for small text, one more for large). */
    val outlineWidth: Int = 2,
    /** Glasses level (0–15) of the lit outline ([Contrast.OUTLINE]). */
    val outlineLevel: Int = 15,
    /** Glasses level (0–15) of the plate behind negative text ([Contrast.PLATE]). */
    val plateLevel: Int = 12,
    /** A text line is set apart when the ground around it is brighter (mean, 0–255) … */
    val busyMean: Int = 70,
    /** … or more restless (standard deviation, 0–255) than this. */
    val busyDeviation: Int = 40,
    /** In an overloaded window a line is set apart already when its ground is lit above this (mean) … */
    val overloadBusyMean: Int = 16,
    /** … or restless above this; text on plain see-through ground stays bright, it is readable as it is. */
    val overloadBusyDeviation: Int = 20,
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
 * 2. **Photos stay pictures**: positive, contrast-stretched, dithered to the 16 levels; a margin
 *    in the colour of the ground around them (a product shot on white) becomes see-through.
 *    **Graphics** (logos, icons: few flat tones) are measured against the ground around them
 *    like everything else: on a dark bar they stay as they are, on a white page they light up.
 * 3. **Text is drawn anew** from its glyphs at full contrast (also blue links or grey captions).
 *    Where the ground around a line is bright or restless (text over a photo), or anything lit
 *    in a window **overloaded** with pictures and lit areas, the text is set apart: by default
 *    **negative only at the letters**, dark glyphs in a thin lit outline ([Contrast]). Overloaded
 *    windows also dim their bright pictures.
 */
object GlassesRasterizer {

    fun rasterize(capture: PageCapture, options: RasterOptions = RasterOptions(), nanoTime: () -> Long = System::nanoTime): GlassesRaster {
        val started = nanoTime()
        val c = if (capture.width == options.width) capture else scale(capture, options.width)
        val w = c.width
        val h = c.height
        val n = w * h
        val lum = IntArray(n) { Levels.luma(c.argb[it]) }
        val runs = c.texts.map { it.copy(box = it.box.clip(w, h)) }.filter { it.box.area > 0 }
        // The page without its text, when captured: grounds, pictures and graphics are judged on
        // it, so large letters neither skew a photo's contrast nor count as ground. Only where a
        // reported line lies, because only those lines come back in step 3.
        val plain = c.textless?.let { t ->
            val lines = BooleanArray(n)
            for (run in runs) fill(lines, w, run.box.inflate(2).clip(w, h))
            IntArray(n) { if (lines[it]) Levels.luma(t[it]) else lum[it] }
        } ?: lum

        // Pictures: from the DOM; only a capture without any DOM information is searched for them.
        val domPictures = c.pictures.map { it.clip(w, h) }.filter { it.area > 0 }
        val domGraphics = c.graphics.map { it.clip(w, h) }.filter { it.area > 0 }
        val picture = BooleanArray(n)
        val noDom = c.pictures.isEmpty() && c.graphics.isEmpty() && c.texts.isEmpty() && c.surfaces.isEmpty()
        if (noDom && options.detectPictures) detectPictures(plain, w, h, picture)

        // 1. Ground and what stands out of it. Pictures and graphics do not tell the ground; they
        // sit on the ground around them.
        val excluded = picture.copyOf()
        for (b in domPictures + domGraphics) fill(excluded, w, b)
        val ground = groundMap(plain, excluded, w, h, c.surfaces)
        val rings = HashMap<Box, Int>()
        fun ring(b: Box) = rings.getOrPut(b) { ringGround(ground, excluded, w, h, b) }
        // Photo or graphic, judged on what stands out of the ground around it: a logo or icon is a
        // few flat tones there and is measured against that ground like text (on a dark bar it
        // keeps its tones, on a white page it lights up); a photo stays a positive picture.
        val (flat, photos) = domPictures.partition { flatGraphic(plain, w, it, ring(it)) }
        for (b in photos) fill(picture, w, b)
        for (b in domGraphics + flat) {
            val g = ring(b)
            // An icon lying on a photo belongs to the photo.
            for (y in b.y until b.bottom) for (x in b.x until b.right) if (!picture[y * w + x]) ground[y * w + x] = g
        }
        for (b in photos) clearMargin(plain, picture, ground, w, b, ring(b))
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
            val d = abs(plain[i] - g)
            val v = if (d < options.deadZone) 0f else ((d - options.deadZone) * 255f / (range - options.deadZone)).coerceIn(0f, 255f)
            base[i] = v
            if (v >= 64f) lit++
        }

        // 2. Pictures, and whether the window is overloaded.
        val load = (pictureCount + lit).toFloat() / n
        val overloaded = load > options.overloadShare
        if (pictureCount > 0) {
            stretchPictures(plain, picture, w, h, photos, options.pictureGain, if (overloaded) options.overloadMean else 255, base)
        }
        val out = IntArray(n)
        for (i in 0 until n) if (!picture[i]) out[i] = Levels.round(base[i].roundToInt())
        if (pictureCount > 0) Dither.toLevels(base, w, h, picture, out)

        // 3. Text at full contrast, set apart where needed.
        val text = BooleanArray(n)
        for (run in runs) fill(text, w, run.box)
        var negative = 0
        for (run in runs) {
            val coverage = glyphs(run, c.argb, c.textless, lum, w)
            if (drawRun(run, coverage, base, out, text, w, h, overloaded, options)) negative++
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

    /** A DOM surface counts in a tile where at least this share of its pixels show the surface's colour. */
    private const val SURFACE_AGREEMENT = 0.15f

    /** How close (0–255) a pixel must be to a neighbouring tile's ground to count as part of it. */
    private const val EDGE_TOLERANCE = 24

    /** Surfaces less opaque than this (alpha 0–255) are veils over something else, not a ground. */
    private const val OPAQUE = 230

    /**
     * The ground brightness under every pixel: the most common brightness in the 24 × 24
     * neighbourhood (pictures left out), or the colour of the smallest DOM surface that holds it.
     * A surface only counts where the pixels around confirm its colour: the DOM also reports
     * backgrounds hidden behind others, painted over by background images, or semi-transparent.
     */
    internal fun groundMap(lum: IntArray, excluded: BooleanArray, w: Int, h: Int, surfaces: List<Surface>): IntArray {
        val tw = (w + TILE - 1) / TILE
        val th = (h + TILE - 1) / TILE
        // Histogram per tile, then summed over 3 × 3 tiles.
        val hist = IntArray(tw * th * BUCKETS)
        val sums = LongArray(tw * th * BUCKETS)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (excluded[i]) continue
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
        // The DOM knows the real grounds (the smallest surface wins), where the pixels agree. The
        // tile's own 8 × 8 pixels decide, so an unreported dark bar next to a light surface stays
        // dark up to its edge.
        fun agrees(t: Int, bucket: Int): Boolean {
            var count = 0
            var near = 0
            for (b in 0 until BUCKETS) {
                val c = hist[t * BUCKETS + b]
                count += c
                if (b in bucket - 1..bucket + 1) near += c
            }
            return count > 0 && near >= count * SURFACE_AGREEMENT
        }
        // The tile's most common tone is the surface's colour.
        fun dominates(t: Int, bucket: Int): Boolean {
            var best = -1
            for (b in 0 until BUCKETS) if (hist[t * BUCKETS + b] > 0 && (best < 0 || hist[t * BUCKETS + b] > hist[t * BUCKETS + best])) best = b
            return best >= 0 && abs(best - bucket) <= 1
        }
        for (s in surfaces.sortedByDescending { it.box.area }) {
            if (s.color ushr 24 < OPAQUE) continue
            val b = s.box.clip(w, h)
            if (b.area == 0) continue
            val g = Levels.luma(s.color)
            val bucket = g * BUCKETS / 256
            val tx0 = b.x / TILE
            val ty0 = b.y / TILE
            val cw = (b.right - 1) / TILE - tx0 + 1
            val ch = (b.bottom - 1) / TILE - ty0 + 1
            val ok = BooleanArray(cw * ch) { k -> agrees((ty0 + k / cw) * tw + tx0 + k % cw, bucket) }
            val surfaceMode = BooleanArray(cw * ch) { k -> dominates((ty0 + k / cw) * tw + tx0 + k % cw, bucket) }
            shapesOnSurface(ok, surfaceMode, cw, ch)
            fun okAt(tx: Int, ty: Int): Boolean =
                if (tx - tx0 in 0 until cw && ty - ty0 in 0 until ch) ok[(ty - ty0) * cw + tx - tx0] else agrees(ty * tw + tx, bucket)
            val others = IntArray(8)
            for (ty in ty0 until ty0 + ch) for (tx in tx0 until tx0 + cw) {
                if (!okAt(tx, ty)) continue
                // Grounds of neighbouring tiles that do not show this surface: an unreported
                // background reaching into this tile keeps its own ground up to its edge.
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = tx + dx
                    val ny = ty + dy
                    if ((dx == 0 && dy == 0) || nx !in 0 until tw || ny !in 0 until th) continue
                    val n = ny * tw + nx
                    if (!okAt(nx, ny) && abs(tileGround[n] - g) > EDGE_TOLERANCE) others[count++] = tileGround[n]
                }
                for (y in max(b.y, ty * TILE) until min(b.bottom, ty * TILE + TILE)) {
                    for (x in max(b.x, tx * TILE) until min(b.right, tx * TILE + TILE)) {
                        val i = y * w + x
                        var gi = g
                        if (abs(lum[i] - g) > EDGE_TOLERANCE) {
                            for (k in 0 until count) if (abs(lum[i] - others[k]) <= EDGE_TOLERANCE) {
                                gi = others[k]
                                break
                            }
                        }
                        ground[i] = gi
                    }
                }
            }
        }
        return ground
    }

    /**
     * Patches of tiles dominated by another tone than a surface's colour that lie inside it, away
     * from its border, and are small (a logo, an icon, a thick letter) are shapes on that surface,
     * not another ground: they are marked as agreeing, so their inside lights up instead of only
     * their outline. A patch reaching the border (an unreported bar) keeps its own ground.
     */
    private fun shapesOnSurface(ok: BooleanArray, surfaceMode: BooleanArray, cw: Int, ch: Int) {
        val seen = BooleanArray(ok.size)
        val queue = IntArray(ok.size)
        for (start in ok.indices) {
            if (surfaceMode[start] || seen[start]) continue
            var head = 0
            var tail = 0
            var enclosed = true
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val k = queue[head++]
                val cx = k % cw
                val cy = k / cw
                if (cx == 0 || cy == 0 || cx == cw - 1 || cy == ch - 1) enclosed = false
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until cw || ny !in 0 until ch) continue
                    val m = ny * cw + nx
                    if (!surfaceMode[m] && !seen[m]) {
                        seen[m] = true
                        queue[tail++] = m
                    }
                }
            }
            if (enclosed && tail <= MAX_SHAPE_TILES) for (i in 0 until tail) ok[queue[i]] = true
        }
    }

    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** Up to this many tiles (36 = about 48 × 48 pixels) an enclosed patch counts as a shape. */
    private const val MAX_SHAPE_TILES = 36

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
     * The ground just around [b]: the median of a ring 2 pixels outside it (leaving out other
     * pictures), where a picture or graphic sits. Without such a ring, the ground inside.
     */
    private fun ringGround(ground: IntArray, excluded: BooleanArray, w: Int, h: Int, b: Box): Int {
        val r = b.inflate(2)
        fun ring(free: Boolean): IntArray {
            val values = ArrayList<Int>()
            fun take(x: Int, y: Int) {
                if (x !in 0 until w || y !in 0 until h) return
                val i = y * w + x
                if (!free || !excluded[i]) values += ground[i]
            }
            for (x in r.x until r.right) {
                take(x, r.y)
                take(x, r.bottom - 1)
            }
            for (y in r.y + 1 until r.bottom - 1) {
                take(r.x, y)
                take(r.right - 1, y)
            }
            return values.toIntArray()
        }
        val values = ring(free = true).takeIf { it.isNotEmpty() } ?: ring(free = false).takeIf { it.isNotEmpty() }
            ?: IntArray(b.area) { k -> ground[(b.y + k / b.w) * w + b.x + k % b.w] }
        values.sort()
        return values[values.size / 2]
    }

    /**
     * A photo's margin in the colour of a light ground around it (a product shot on white, the
     * clear parts of a PNG) is ground, not picture: pixels reachable from the box's edge that stay
     * within [MARGIN_TOLERANCE] of [g] turn see-through. On a dark ground there is nothing to gain
     * (dark is see-through anyway) and dark parts of the photo would be cut out.
     */
    private fun clearMargin(lum: IntArray, picture: BooleanArray, ground: IntArray, w: Int, b: Box, g: Int) {
        if (g < LIGHT_MARGIN) return
        val seen = BooleanArray(b.area)
        val queue = IntArray(b.area)
        var tail = 0
        fun push(x: Int, y: Int) {
            if (x !in b.x until b.right || y !in b.y until b.bottom) return
            val k = (y - b.y) * b.w + (x - b.x)
            val i = y * w + x
            if (seen[k] || !picture[i] || abs(lum[i] - g) > MARGIN_TOLERANCE) return
            seen[k] = true
            queue[tail++] = k
        }
        for (x in b.x until b.right) {
            push(x, b.y)
            push(x, b.bottom - 1)
        }
        for (y in b.y until b.bottom) {
            push(b.x, y)
            push(b.right - 1, y)
        }
        var head = 0
        while (head < tail) {
            val k = queue[head++]
            val x = b.x + k % b.w
            val y = b.y + k / b.w
            push(x - 1, y)
            push(x + 1, y)
            push(x, y - 1)
            push(x, y + 1)
        }
        for (t in 0 until tail) {
            val i = (b.y + queue[t] / b.w) * w + b.x + queue[t] % b.w
            picture[i] = false
            ground[i] = g
        }
    }

    /** How close (0–255) a photo's margin must stay to the ground around it to be cleared. */
    private const val MARGIN_TOLERANCE = 10

    /** Only margins on a ground at least this light (0–255) are cleared. */
    private const val LIGHT_MARGIN = 160

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
     * An icon, a logo, a flat graphic: of the pixels that stand out of the ground [g] around [b],
     * two tones cover most, or at most three levels are really used. A photo spreads over many.
     */
    internal fun flatGraphic(lum: IntArray, w: Int, b: Box, g: Int): Boolean {
        val hist = IntArray(BUCKETS)
        var ink = 0
        for (y in b.y until b.bottom) for (x in b.x until b.right) {
            val v = lum[y * w + x]
            if (abs(v - g) <= FLAT_GROUND) continue
            hist[v * BUCKETS / 256]++
            ink++
        }
        if (ink <= b.area * 0.02f) return true
        val sorted = hist.sortedDescending()
        return (sorted[0] + sorted[1]) >= ink * 0.8f || hist.count { it >= ink * 0.02f } <= 3
    }

    /** Pixels this close (0–255) to the ground around a picture are its background, not its content. */
    private const val FLAT_GROUND = 28

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
     * How much of each pixel of [run] is glyph (0–1). With the page captured once more without
     * text, the difference between both is exactly the glyphs, also on photos. Without it the
     * glyphs are found against the line's own ground (its most common brightness), towards the CSS
     * colour or else the far end of its pixels.
     */
    private fun glyphs(run: TextRun, argb: IntArray, textless: IntArray?, lum: IntArray, w: Int): FloatArray {
        val b = run.box
        val coverage = FloatArray(b.area)
        if (textless != null) {
            // The ink: the CSS colour, or else the pixel that changed most.
            var ink = run.color?.takeIf { it ushr 24 != 0 }
            if (ink == null) {
                var most = -1
                for (y in b.y until b.bottom) for (x in b.x until b.right) {
                    val i = y * w + x
                    val d = distance(argb[i], textless[i])
                    if (d > most) {
                        most = d
                        ink = argb[i]
                    }
                }
            }
            // A translucent colour (rgba(0, 0, 0, 0.56) for grey captions) only goes that far.
            val opacity = ((ink ?: -1) ushr 24) / 255f
            for (y in b.y until b.bottom) for (x in b.x until b.right) {
                val i = y * w + x
                val d = distance(argb[i], textless[i])
                if (d < GLYPH_NOISE) continue
                val span = distance(ink ?: argb[i], textless[i]) * opacity
                coverage[(y - b.y) * b.w + (x - b.x)] = if (span < 24) 1f else (d / span).coerceIn(0f, 1f)
            }
            return coverage
        }
        val hist = IntArray(256)
        for (y in b.y until b.bottom) for (x in b.x until b.right) hist[lum[y * w + x]]++
        // With the CSS colour known, the ground is what the line shows most apart from that colour:
        // bold white letters must not become the ground of a light photo.
        val css = run.color?.let { Levels.luma(it) }
        val rest = hist.copyOf()
        if (css != null) for (v in max(0, css - 24)..min(255, css + 24)) rest[v] = 0
        val restCount = rest.sum()
        val ground = if (restCount > 0) mode(rest, restCount) else mode(hist, b.area)
        val ink = css?.takeIf { abs(it - ground) >= 24 } ?: farthest(hist, b.area, ground)
        val span = (ink - ground).toFloat()
        if (abs(span) >= 12f) {
            for (y in b.y until b.bottom) for (x in b.x until b.right) {
                coverage[(y - b.y) * b.w + (x - b.x)] = ((lum[y * w + x] - ground) / span).coerceIn(0f, 1f)
            }
        }
        return coverage
    }

    /** Pixels that differ less than this (per channel, 0–255) between both captures are not glyph. */
    private const val GLYPH_NOISE = 10

    private fun distance(a: Int, b: Int): Int = max(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        max(abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    /**
     * Draws one line of text from its glyph [coverage]; true if it was set apart from a busy
     * ground (see [Contrast]).
     */
    private fun drawRun(run: TextRun, coverage: FloatArray, base: FloatArray, out: IntArray, text: BooleanArray, w: Int, h: Int, overloaded: Boolean, o: RasterOptions): Boolean {
        val b = run.box
        // The ground around the letters as the glasses would show it without them.
        val pad = b.inflate(3).clip(w, h)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in pad.y until pad.bottom) for (x in pad.x until pad.right) {
            val inRun = x in b.x until b.right && y in b.y until b.bottom
            if (inRun && coverage[(y - b.y) * b.w + (x - b.x)] > 0.25f) continue
            // Neighbouring lines and links ("new | past") are text, not a restless ground.
            if (!inRun && text[y * w + x]) continue
            val v = base[y * w + x].toDouble()
            sum += v
            sumSq += v * v
            count++
        }
        val mean = if (count == 0) 0.0 else sum / count
        val deviation = if (count == 0) 0.0 else sqrt(max(0.0, sumSq / count - mean * mean))
        val busy = mean > o.busyMean || deviation > o.busyDeviation ||
            (overloaded && (mean > o.overloadBusyMean || deviation > o.overloadBusyDeviation))
        val apart = when (o.text) {
            TextMode.NORMAL -> false
            TextMode.NEGATIVE -> true
            TextMode.AUTO -> busy
        }
        when {
            !apart -> for (y in b.y until b.bottom) for (x in b.x until b.right) {
                val cover = coverage[(y - b.y) * b.w + (x - b.x)]
                val i = y * w + x
                // On a calm ground the text replaces what the ground step made of it.
                val under = if (cover > 0f) 0 else out[i]
                out[i] = max(under, Levels.round((cover * 255f).roundToInt()))
            }
            o.contrast == Contrast.PLATE -> {
                val plate = Levels.gray(o.plateLevel)
                for (y in pad.y until pad.bottom) for (x in pad.x until pad.right) {
                    val inRun = x in b.x until b.right && y in b.y until b.bottom
                    val cover = if (inRun) coverage[(y - b.y) * b.w + (x - b.x)] else 0f
                    out[y * w + x] = Levels.round((plate * (1f - cover)).roundToInt())
                }
            }
            else -> outline(b, coverage, out, w, h, o)
        }
        return apart
    }

    /**
     * The glyphs with a thin outline around each ([Contrast.OUTLINE]: dark letters, lit outline;
     * [Contrast.HALO]: lit letters, see-through outline). Beyond the outline the picture stays.
     */
    private fun outline(b: Box, coverage: FloatArray, out: IntArray, w: Int, h: Int, o: RasterOptions) {
        val r = (o.outlineWidth + when {
            b.h < 18 -> -1
            b.h >= 36 -> 1
            else -> 0
        }).coerceAtLeast(1)
        val area = b.inflate(r).clip(w, h)
        val aw = area.w
        val ah = area.h
        val cover = FloatArray(aw * ah)
        for (y in b.y until b.bottom) for (x in b.x until b.right) {
            cover[(y - area.y) * aw + (x - area.x)] = coverage[(y - b.y) * b.w + (x - b.x)]
        }
        // The glyphs grown by r with a round brush: how much of the outline covers each pixel.
        val grown = FloatArray(aw * ah)
        for (y in 0 until ah) for (x in 0 until aw) {
            var m = 0f
            for (dy in -r..r) {
                val yy = y + dy
                if (yy !in 0 until ah) continue
                for (dx in -r..r) {
                    if (dx * dx + dy * dy > r * r + r) continue
                    val xx = x + dx
                    if (xx !in 0 until aw) continue
                    val c = cover[yy * aw + xx]
                    if (c > m) m = c
                }
            }
            grown[y * aw + x] = m
        }
        val rim = Levels.gray(o.outlineLevel).toFloat()
        for (y in 0 until ah) for (x in 0 until aw) {
            val k = y * aw + x
            val d = grown[k]
            if (d <= 0f) continue
            val c = cover[k]
            val i = (area.y + y) * w + area.x + x
            val under = out[i].toFloat()
            val v = if (o.contrast == Contrast.HALO) under * (1f - d) * (1f - c) + 255f * c else (under * (1f - d) + rim * d) * (1f - c)
            out[i] = Levels.round(v.roundToInt())
        }
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
        return PageCapture(
            width, height, scalePixels(c.argb, c.width, c.height, width, height, f),
            texts = c.texts.map { it.copy(box = it.box.scaled(f)) },
            pictures = c.pictures.map { it.scaled(f) },
            surfaces = c.surfaces.map { it.copy(box = it.box.scaled(f)) },
            textless = c.textless?.let { scalePixels(it, c.width, c.height, width, height, f) },
            graphics = c.graphics.map { it.scaled(f) },
        )
    }

    private fun scalePixels(argb: IntArray, cw: Int, ch: Int, width: Int, height: Int, f: Float): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val sy0 = (y / f).toInt().coerceIn(0, ch - 1)
            val sy1 = max(sy0 + 1, ((y + 1) / f).toInt()).coerceAtMost(ch)
            for (x in 0 until width) {
                val sx0 = (x / f).toInt().coerceIn(0, cw - 1)
                val sx1 = max(sx0 + 1, ((x + 1) / f).toInt()).coerceAtMost(cw)
                var a = 0L
                var r = 0L
                var g = 0L
                var bl = 0L
                var k = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    val p = argb[sy * cw + sx]
                    a += p ushr 24 and 0xFF
                    r += p shr 16 and 0xFF
                    g += p shr 8 and 0xFF
                    bl += p and 0xFF
                    k++
                }
                out[y * width + x] = ((a / k).toInt() shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (bl / k).toInt()
            }
        }
        return out
    }

    private fun fill(mask: BooleanArray, w: Int, b: Box) {
        for (y in b.y until b.bottom) for (x in b.x until b.right) mask[y * w + x] = true
    }
}

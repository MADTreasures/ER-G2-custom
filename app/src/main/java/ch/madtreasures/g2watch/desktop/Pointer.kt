package ch.madtreasures.g2watch.desktop

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The arrow pointer: 0 is transparent, [DARK] is opaque black, [BRIGHT] is lit. The tip is at
 * (0, 0). The arrow has an outline and a fill, and each pixel is drawn against what lies beneath
 * it: over dark desktop pixels the outline is bright and the fill dark, over lit ones the other way
 * round (the "negative"). So the pointer stays visible over text, frames and pictures, and the
 * switch happens in the same frame as the move. [drawInto] puts it into the desktop's frame.
 */
object PointerSprite {
    private val SHAPE = arrayOf(
        "X",
        "XX",
        "X.X",
        "X..X",
        "X...X",
        "X....X",
        "X.....X",
        "X......X",
        "X.......X",
        "X........X",
        "X.....XXXXX",
        "X..X..X",
        "X.X X..X",
        "XX  X..X",
        "X    X..X",
        "     X..X",
        "      XX",
    )

    val width: Int = SHAPE.maxOf { it.length }
    val height: Int = SHAPE.size

    const val BRIGHT = 255

    /** Color-key black: opaque, but not lit. */
    const val DARK = 1

    /** From this desktop brightness on, a pointer pixel is drawn negative: half of full. */
    const val NEGATIVE_FROM = 128

    private const val NONE: Byte = 0
    private const val OUTLINE: Byte = 1
    private const val FILL: Byte = 2

    private val roles = ByteArray(width * height).also { out ->
        for ((y, row) in SHAPE.withIndex()) {
            for ((x, c) in row.withIndex()) {
                out[y * width + x] = when (c) {
                    'X' -> OUTLINE
                    '.' -> FILL
                    else -> NONE
                }
            }
        }
    }

    /** The pointer over a dark background: bright outline, dark fill. */
    val normal: ByteArray = draw { _, _ -> 0 }

    /** The pointer with its tip at ([x], [y]) over [background]. Pixels off the raster count as dark. */
    fun render(background: GrayRaster, x: Int, y: Int): ByteArray = draw { sx, sy ->
        val bx = x + sx
        val by = y + sy
        if (bx in 0 until background.width && by in 0 until background.height) background[bx, by] else 0
    }

    /** Identifies a rendered pointer; part of the desktop frame's fingerprint. */
    fun fingerprint(pixels: ByteArray): String = "pointer:" + java.lang.Long.toHexString(fnv1a64(pixels))

    /**
     * Draws [pixels] (a rendered pointer) with its tip at ([x], [y]) into [target], only inside [clip]:
     * transparent pixels leave the target as it is, [DARK] ones turn black. Returns the block it
     * covered, or null if no part of it lies in [clip].
     */
    fun drawInto(target: GrayRaster, pixels: ByteArray, x: Int, y: Int, clip: Rect): Rect? {
        val box = Rect(x, y, width, height).intersect(clip) ?: return null
        for (py in box.y until box.bottom) {
            for (px in box.x until box.right) {
                val v = pixels[(py - y) * width + (px - x)].toInt() and 0xFF
                if (v != 0) target[px, py] = if (v == DARK) 0 else v
            }
        }
        return box
    }

    private inline fun draw(background: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(width * height)
        for (sy in 0 until height) {
            for (sx in 0 until width) {
                val role = roles[sy * width + sx]
                if (role == NONE) continue
                val negative = background(sx, sy) >= NEGATIVE_FROM
                val lit = (role == OUTLINE) != negative
                out[sy * width + sx] = (if (lit) BRIGHT else DARK).toByte()
            }
        }
        return out
    }
}

/** Pointer position (its tip) in screen pixels, kept inside [bounds]: the stage, where the desktop draws. */
class PointerPosition(private val bounds: Rect) {
    private var fx = bounds.x + bounds.w / 2f
    private var fy = bounds.y + bounds.h / 2f

    val x: Int get() = fx.roundToInt()
    val y: Int get() = fy.roundToInt()

    /** How far the last move wanted to go past the top (negative) or bottom edge. */
    var overflowY = 0f
        private set

    /** Moves by a delta in glasses pixels; true if the rounded position changed. */
    fun moveBy(dx: Float, dy: Float): Boolean {
        val oldX = x
        val oldY = y
        fx = (fx + dx).coerceIn(bounds.x.toFloat(), (bounds.right - 1).toFloat())
        val wantedY = fy + dy
        fy = wantedY.coerceIn(bounds.y.toFloat(), (bounds.bottom - 1).toFloat())
        overflowY = wantedY - fy
        return x != oldX || y != oldY
    }

    fun center() {
        fx = bounds.x + bounds.w / 2f
        fy = bounds.y + bounds.h / 2f
    }
}

/**
 * Watch finger movement to glasses pixels, as tuned on the Pixel Watch in G2 Direct: slow strokes
 * position precisely, fast flicks cross the display.
 */
object PointerMotion {
    /** Glasses pixels per watch dp at speed 1.0 before acceleration. */
    const val BASE_GAIN = 2.6f
    const val MIN_SPEED = 0.3f
    const val MAX_SPEED = 4f

    /** [dxDp]/[dyDp]: finger movement in dp over [dtMs]; returns the glasses-pixel delta. */
    fun toGlasses(dxDp: Float, dyDp: Float, dtMs: Float, speed: Float): Pair<Float, Float> {
        val velocity = hypot(dxDp, dyDp) / dtMs.coerceAtLeast(1f)
        val accel = (0.55f + velocity * 1.2f).coerceIn(0.55f, 2.6f)
        val k = BASE_GAIN * speed.coerceIn(MIN_SPEED, MAX_SPEED) * accel
        return Pair(dxDp * k, dyDp * k)
    }

    /** Glasses pixels per mouse count at speed 1.0 before acceleration. */
    const val MOUSE_GAIN = 0.5f

    /**
     * A Bluetooth mouse's movement to glasses pixels: [dx]/[dy] in the mouse's own counts (raw, without
     * Android's acceleration) over [dtMs]. At the usual 800 to 1000 counts per inch a slow move of
     * about 5 cm crosses the 576 pixels of the app area; a quick flick gains up to 2.4 times as much.
     * [speed] is the same factor as for the finger (crown, settings).
     */
    fun fromMouse(dx: Float, dy: Float, dtMs: Float, speed: Float): Pair<Float, Float> {
        val velocity = hypot(dx, dy) / dtMs.coerceAtLeast(1f)
        val accel = (0.6f + velocity * 0.3f).coerceIn(0.6f, 2.4f)
        val k = MOUSE_GAIN * speed.coerceIn(MIN_SPEED, MAX_SPEED) * accel
        return Pair(dx * k, dy * k)
    }
}

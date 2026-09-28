package ch.madtreasures.g2watch.desktop

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The arrow pointer for a color-key surface: 0 is transparent, [DARK] is opaque black, [BRIGHT]
 * is lit. The tip is at (0, 0). The arrow has an outline and a fill, and each pixel is drawn
 * against what lies beneath it: over dark desktop pixels the outline is bright and the fill
 * dark, over lit ones the other way round (the "negative"). So the pointer stays visible over
 * text, frames and pictures, and the switch happens in the same frame as the move.
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

    /** Identifies a rendered pointer for Faceclaw's compositor; its position is surface geometry. */
    fun fingerprint(pixels: ByteArray): String = "pointer:" + java.lang.Long.toHexString(fnv1a64(pixels))

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

/** Pointer position in screen pixels, kept inside [bounds] (the part of the screen the wearer sees). */
class PointerPosition(private val bounds: Rect) {
    private var fx = bounds.x + bounds.w / 2f
    private var fy = bounds.y + bounds.h / 2f

    val x: Int get() = fx.roundToInt()
    val y: Int get() = fy.roundToInt()

    /** Moves by a delta in glasses pixels; true if the rounded position changed. */
    fun moveBy(dx: Float, dy: Float): Boolean {
        val oldX = x
        val oldY = y
        fx = (fx + dx).coerceIn(bounds.x.toFloat(), (bounds.right - 1).toFloat())
        fy = (fy + dy).coerceIn(bounds.y.toFloat(), (bounds.bottom - 1).toFloat())
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
}

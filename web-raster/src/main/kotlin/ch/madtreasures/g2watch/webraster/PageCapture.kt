package ch.madtreasures.g2watch.webraster

/** An axis-aligned box in capture pixels. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val area: Int get() = if (w > 0 && h > 0) w * h else 0

    fun inflate(d: Int) = Box(x - d, y - d, w + 2 * d, h + 2 * d)

    /** This box cut to 0 … [width] × 0 … [height]; possibly empty. */
    fun clip(width: Int, height: Int): Box {
        val x0 = x.coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        return Box(x0, y0, right.coerceIn(0, width) - x0, bottom.coerceIn(0, height) - y0)
    }

    fun scaled(f: Float) = Box((x * f).toInt(), (y * f).toInt(), kotlin.math.ceil(w * f).toInt(), kotlin.math.ceil(h * f).toInt())
}

/**
 * One line of text as the browser laid it out (a client rect of a DOM text range). [color] is the
 * CSS text colour as ARGB, if known; it makes the glyphs easier to find than the pixels alone.
 */
data class TextRun(val box: Box, val color: Int? = null)

/** An element with an opaque background (CSS `background-color`), from the DOM. */
data class Surface(val box: Box, val color: Int)

/**
 * A rendered page, or the visible part of it: ARGB pixels as a browser engine paints them, plus
 * what its DOM tells about the layout. Everything but the pixels is optional; without it the
 * rasterizer estimates backgrounds and pictures from the pixels.
 */
class PageCapture(
    val width: Int,
    val height: Int,
    /** width × height ARGB pixels, row by row (as `Bitmap.getPixels` gives them). */
    val argb: IntArray,
    val texts: List<TextRun> = emptyList(),
    /** Pictures: `<img>`, `<video>`, `<canvas>`, elements with a background image. */
    val pictures: List<Box> = emptyList(),
    val surfaces: List<Surface> = emptyList(),
) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "capture $width × $height with ${argb.size} pixels" }
    }
}

package ch.madtreasures.g2watch.desktop

/**
 * Where the desktop draws: Faceclaw's compositor feeding the glasses, or a fake in tests.
 * Surfaces are composited in ascending z-order onto black; a color-key surface treats pixel
 * value 0 as transparent and 1 as black.
 */
interface GlassesDisplay {
    fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean)

    /**
     * Replaces the whole content of surface [id]. [fingerprint] identifies that content: the
     * compositor treats an unchanged fingerprint with unchanged geometry as "nothing to send".
     */
    fun submit(id: String, pixels: ByteArray, width: Int, height: Int, fingerprint: String)
}

/** Draws text into a [GrayRaster]; Android's font renderer on the watch, a stub in tests. */
interface TextPainter {
    /** Width in pixels of [text] at a font size of [sizePx]. */
    fun measure(text: String, sizePx: Int, bold: Boolean = false): Int

    /** Draws [text] with the top-left corner of its line box at ([x], [y]). */
    fun draw(target: GrayRaster, text: String, x: Int, y: Int, sizePx: Int, value: Int = 255, bold: Boolean = false)

    /** Height of one line of text at [sizePx], including spacing. */
    fun lineHeight(sizePx: Int): Int = sizePx * 5 / 4
}

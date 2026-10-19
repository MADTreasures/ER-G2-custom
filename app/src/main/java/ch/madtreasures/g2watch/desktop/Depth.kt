package ch.madtreasures.g2watch.desktop

/**
 * "Bildtiefe": how near the picture on the glasses appears, with the three levels of the Even app.
 *
 * Even's firmware 2.3.0.24 places its 576×288 picture per lens at x = 24/32/40 on the right lens and
 * 40/32/24 on the left one for levels 0/1/2 (tables at 0x7c8250 and 0x7c8254, read by 0x4706a4). The
 * custom firmware copies our 640×480 frame as it is and skips that placement, so it always shows the
 * middle level. G2 Watch therefore shifts the whole screen itself with Faceclaw's stereo depth, which
 * moves the left lens by `floor(d / 2)` and the right lens by `-floor((d + 1) / 2)`: ±16 is exactly
 * stock's ±8 per lens. Positive moves both pictures towards the nose (crossed disparity), which reads as
 * nearer. Multiples of 4 keep both shifts even, the firmware's fast copy.
 */
enum class Depth(val label: String, val disparity: Int) {
    /** Stock level 0. */
    NEAR("Nah", 16),

    /** Stock level 1, the default: both lenses at x = 32, no shift. */
    MIDDLE("Mittel", 0),

    /** Stock level 2. */
    FAR("Fern", -16),
    ;

    companion object {
        val DEFAULT = MIDDLE

        /** The level stored under [name], or the default for anything unknown. */
        fun of(name: String?): Depth = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

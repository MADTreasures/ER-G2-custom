package ch.madtreasures.musik

import java.text.BreakIterator

/**
 * Song titles, artists and station names cut to the lines they may take on the glasses. The pages that take
 * gestures cannot scroll, so a title that wrapped once too often would push the time off the page. The host
 * wraps with the watch's font, which a package cannot measure; this estimates each character's width from its
 * kind, a little generously (as the Fluency package does), and wraps like the host (02 §4.2): at spaces, a word
 * wider than a line cut between characters.
 */
object TextFit {
    /** Width of the app area inside the page margins (576 − 2 × 16). */
    const val WIDTH = 544

    /** Bold text (headings) is a little wider. */
    private const val BOLD = 1.04f

    /** [s] tidied, and if it needs more than [maxLines] lines at [sizePx], its beginning with "…". */
    fun fit(s: String, sizePx: Int, bold: Boolean, maxLines: Int, max: Int = WIDTH): String {
        val text = SPACES.replace(s.trim(), " ")
        if (maxLines <= 0) return ""
        if (lines(text, sizePx, bold, max) <= maxLines) return text
        val starts = clusterStarts(text)
        // The longest beginning that fits with "…" after it.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lines(text.substring(0, starts[mid]).trimEnd() + "…", sizePx, bold, max) <= maxLines) lo = mid else hi = mid - 1
        }
        return if (lo == 0) "…" else text.substring(0, starts[lo]).trimEnd() + "…"
    }

    /** Lines that [s] takes at [sizePx] in a column [max] pixels wide. */
    fun lines(s: String, sizePx: Int, bold: Boolean, max: Int = WIDTH): Int {
        val px = sizePx * if (bold) BOLD else 1f
        return s.split('\n').sumOf { paragraphLines(it, px, max.toFloat()) }
    }

    private fun paragraphLines(paragraph: String, px: Float, max: Float): Int {
        val space = em(' '.code) * px
        var count = 0
        var line = -1f // width of the current line; -1: nothing on it yet
        for (word in paragraph.split(' ')) {
            val w = width(word) * px
            val joined = if (line < 0f) w else line + space + w
            if (joined <= max) {
                line = joined
                continue
            }
            if (line >= 0f) count++
            line = w
            // A word wider than the line fills lines of its own, cut between characters.
            if (w > max) {
                var rest = 0f
                for (cluster in clusters(word)) {
                    val cw = width(cluster) * px
                    if (rest > 0f && rest + cw > max) {
                        count++
                        rest = 0f
                    }
                    rest += cw
                }
                line = rest
            }
        }
        return count + 1
    }

    /** Estimated width of [s] in em (multiples of the font size). */
    fun width(s: String): Float {
        var w = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += em(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /** Width of one character in em, measured against Android's Roboto and then made about 8 % wider. */
    private fun em(cp: Int): Float = when {
        // Marks that join the character before them: accents, joiners, variation selectors, skin tones.
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> 0f
        cp == ' '.code -> 0.275f
        cp < 0x10000 && cp.toChar() in NARROW -> 0.31f
        cp < 0x10000 && cp.toChar() in HALF -> 0.42f
        cp == 'm'.code || cp == 'w'.code -> 0.86f
        cp == 'M'.code || cp == 'W'.code || cp == '@'.code || cp == '%'.code || cp == '&'.code -> 0.95f
        cp == 0x2026 || cp == 0x2014 || cp in 0x2190..0x21FF -> 0.85f
        cp in 'a'.code..'z'.code -> 0.55f
        cp in 'A'.code..'Z'.code -> 0.70f
        cp in '0'.code..'9'.code -> 0.60f
        Character.isIdeographic(cp) || cp in 0x1100..0x11FF || cp in 0x2E80..0x303F || cp in 0x3040..0x31FF ||
            cp in 0xAC00..0xD7AF || cp in 0xFF00..0xFFEF -> 1.05f
        cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF -> 1.30f
        Character.isUpperCase(cp) -> 0.73f
        Character.isLetter(cp) -> 0.62f
        else -> 0.66f
    }

    private val SPACES = Regex("\\s+")
    private const val NARROW = "ijlI!|.,;:'`´’‘‚·"
    private const val HALF = "ftr()[]{}\"/\\-*°¹²³«»‹›"

    private fun clusters(s: String): List<String> {
        val starts = clusterStarts(s)
        return (0 until starts.size - 1).map { k -> s.substring(starts[k], starts[k + 1]) }
    }

    /** Where user-perceived characters begin, and the end: an emoji or a letter with its accent stays whole. */
    private fun clusterStarts(s: String): List<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = ArrayList<Int>()
        var at = it.first()
        while (at != BreakIterator.DONE) {
            out += at
            at = it.next()
        }
        return out
    }
}

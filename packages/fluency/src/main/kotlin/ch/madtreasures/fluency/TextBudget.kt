package ch.madtreasures.fluency

import java.text.BreakIterator

/**
 * How much caption text fits into a number of lines on the glasses. The host wraps text with the watch's
 * font, which a package cannot measure; this estimates each character's width from its kind (Latin small
 * letter, capital, digit, Chinese, emoji …) a little generously, so the real text never needs more lines
 * than estimated. Like the host (02 §4.2) it wraps at spaces, starts a line at `\n` and cuts a word that
 * is wider than a line.
 */
object TextBudget {
    /** Width of the app area inside the page margins (576 − 2 × 16). */
    const val WIDTH = 544

    /** Bold text (headings) is a little wider. */
    private const val BOLD = 1.04f

    /** Lines that [s] takes at [sizePx] in a column [max] pixels wide. */
    fun lines(s: String, sizePx: Int, bold: Boolean, max: Int = WIDTH): Int =
        s.split('\n').sumOf { paragraphLines(it, scale(sizePx, bold), max.toFloat()) }

    /** Estimated width of one line of [s] in pixels. */
    fun px(s: String, sizePx: Int, bold: Boolean): Float = width(s) * scale(sizePx, bold)

    private fun scale(sizePx: Int, bold: Boolean) = sizePx * (if (bold) BOLD else 1f)

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
            line = if (w <= max) w else cut(word, px, max).also { count += it.first }.second
        }
        return count + 1
    }

    /** A word wider than the line, cut between characters: the full lines it fills and the width of its rest. */
    private fun cut(word: String, px: Float, max: Float): Pair<Int, Float> {
        var full = 0
        var rest = 0f
        forEachCluster(word) { cluster ->
            val w = width(cluster) * px
            if (rest > 0f && rest + w > max) {
                full++
                rest = 0f
            }
            rest += w
        }
        return full to rest
    }

    /**
     * The end of [s] that fits into [maxLines] lines: whole words dropped from the front (characters, for
     * text without spaces such as Chinese), and "…" in front where something is missing. A paragraph that
     * starts on a line of its own gets no "…". [s] keeps its paragraphs; spaces within them are tidied.
     */
    fun tail(s: String, sizePx: Int, bold: Boolean, maxLines: Int, max: Int = WIDTH): String {
        val text = s.split('\n').map { it.trim().replace(SPACES, " ") }.filter { it.isNotEmpty() }.joinToString("\n")
        if (maxLines <= 0 || text.isEmpty()) return ""
        fun fits(t: String) = lines(t, sizePx, bold, max) <= maxLines
        if (fits(text)) return text
        // The newest words matter: try later and later starts, words first.
        for (i in 1 until text.length) {
            val before = text[i - 1]
            if (before != ' ' && before != '\n') continue
            val candidate = if (before == '\n') text.substring(i) else "…" + text.substring(i)
            if (fits(candidate)) return candidate
        }
        // Even the last word is too long: drop characters, whole ones (an emoji or a letter with its accent stays together).
        val starts = clusterStarts(text)
        for (i in starts) {
            if (i == 0) continue
            val candidate = "…" + text.substring(i).trimStart()
            if (fits(candidate)) return candidate
        }
        return ""
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

    /**
     * Width of one character in em. Measured against Android's Roboto (Robolectric, LivePageFitTest), Latin
     * text comes out about 8 % wider than it is, as a reserve for the watch's font; Chinese, Japanese and
     * Korean characters are a full em wide, an emoji a little more.
     */
    fun em(cp: Int): Float = when {
        // Marks that join the character before them: accents, joiners, variation selectors, skin tones.
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> 0f
        cp == ' '.code -> 0.275f
        cp < 0x10000 && cp.toChar() in NARROW -> 0.31f
        cp < 0x10000 && cp.toChar() in HALF -> 0.42f
        cp == 'm'.code || cp == 'w'.code -> 0.86f
        cp == 'M'.code || cp == 'W'.code || cp == '@'.code || cp == '%'.code || cp == '&'.code -> 0.95f
        // The ellipsis this class puts in front, dashes and arrows.
        cp == 0x2026 || cp == 0x2014 || cp in 0x2190..0x21FF -> 0.85f
        cp in 'a'.code..'z'.code -> 0.55f
        cp in 'A'.code..'Z'.code -> 0.70f
        cp in '0'.code..'9'.code -> 0.60f
        Character.isIdeographic(cp) || cp in 0x1100..0x11FF || cp in 0x2E80..0x303F || cp in 0x3040..0x31FF ||
            cp in 0xAC00..0xD7AF || cp in 0xFF00..0xFFEF -> 1.05f
        cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF -> 1.30f
        Character.isUpperCase(cp) -> 0.73f
        Character.UnicodeScript.of(cp) in WIDE_SCRIPTS -> 0.80f
        Character.isLetter(cp) -> 0.60f
        else -> 0.66f
    }

    private val SPACES = Regex("\\s+")
    private const val NARROW = "ijlI!|.,;:'`´’‘‚·"
    private const val HALF = "ftr()[]{}\"/\\-*°¹²³«»‹›"

    /** Scripts whose letters are wider than Latin ones (Indian scripts, Thai and its neighbours, Tibetan …). */
    private val WIDE_SCRIPTS = setOf(
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
        Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW,
    )

    private inline fun forEachCluster(s: String, action: (String) -> Unit) {
        val starts = clusterStarts(s)
        for (k in starts.indices) action(s.substring(starts[k], if (k + 1 < starts.size) starts[k + 1] else s.length))
    }

    /** Where user-perceived characters begin (BreakIterator), so an emoji or a letter with its accent stays whole. */
    private fun clusterStarts(s: String): List<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = ArrayList<Int>()
        var at = it.first()
        while (at != BreakIterator.DONE && at < s.length) {
            out += at
            at = it.next()
        }
        return out
    }
}

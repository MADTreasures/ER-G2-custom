package ch.madtreasures.g2watch.desktop

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

/**
 * Where a text has emoji, the way phones decide it. An emoji is one user-perceived character (a
 * grapheme cluster, with its skin tone, joiners, flag or keycap parts) that contains a character
 * shown as a picture by default (😀 ⭐ 🇨🇭), an emoji above U+1F000, which no text font draws (🌧),
 * or U+FE0F asking for the picture (❤️ 1️⃣). U+FE0E asks for the letter form and keeps a cluster
 * text, as do a plain ❤, ☀, © and digits.
 */
internal object EmojiText {
    /** The emoji in [text], in order, neighbours joined into one range; empty when it has none. */
    fun ranges(text: String): List<IntRange> {
        if (!hasMark(text, 0, text.length)) return emptyList()
        val out = ArrayList<IntRange>()
        val clusters = BreakIterator.getCharacterInstance()
        clusters.setText(text)
        var start = clusters.first()
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            if (hasMark(text, start, end) && text.indexOf(TEXT_STYLE, start) !in start until end) {
                val last = out.lastOrNull()
                if (last != null && last.last + 1 == start) out[out.lastIndex] = last.first until end else out += start until end
            }
            start = end
            end = clusters.next()
        }
        return out
    }

    /** True if a code point in [start] until [end] of [text] makes its cluster an emoji. */
    private fun hasMark(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (marks(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun marks(cp: Int): Boolean = when {
        cp < 0x2000 -> false // letters and punctuation; ©, ® and digits become emoji only with U+FE0F
        cp == EMOJI_STYLE || cp == KEYCAP -> true
        cp >= 0x1F000 -> UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)
        else -> UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)
    }

    private const val EMOJI_STYLE = 0xFE0F
    private const val TEXT_STYLE = '︎'
    private const val KEYCAP = 0x20E3
}

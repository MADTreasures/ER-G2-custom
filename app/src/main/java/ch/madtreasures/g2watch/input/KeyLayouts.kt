package ch.madtreasures.g2watch.input

import android.view.KeyEvent
import java.util.Locale

/**
 * What one key types: alone, with Shift, with AltGr (right Alt) and with Shift+AltGr. A combining
 * accent (U+0300 …) is a dead key: it changes the next letter ([DeadKeys]).
 */
class KeyChars(
    val base: Char,
    val shift: Char,
    val altGr: Char? = null,
    val shiftAltGr: Char? = null,
    val caps: Caps = Caps.NONE,
) {
    /** What Caps Lock does to the key. */
    enum class Caps {
        NONE,

        /** Letters: Caps Lock swaps lower and upper case, Shift swaps back. */
        INVERT,

        /** Swiss ü, ö, ä: Caps Lock capitalises what the key types, with or without Shift (Ü, È). */
        UPPER,
    }

    fun of(shift: Boolean, altGr: Boolean, capsLock: Boolean): Char? {
        if (altGr) return if (shift) shiftAltGr ?: this.altGr else this.altGr
        return when {
            !capsLock || caps == Caps.NONE -> if (shift) this.shift else base
            caps == Caps.INVERT -> if (shift) base else this.shift
            else -> (if (shift) this.shift else base).uppercaseChar()
        }
    }
}

/**
 * Keyboard layouts of our own, by Linux scan code (the key's place on the keyboard, the same for every
 * layout). Wear OS has no setting for the layout of a hardware keyboard, so Android types with its
 * US "Generic" map, and a Swiss keyboard would swap Y and Z and type no umlauts. These tables follow
 * Android's own `keyboard_layout_swiss_german.kcm` and `keyboard_layout_german.kcm`
 * (frameworks/base/packages/InputDevices), and `Generic.kcm` for US; only keys that type are here.
 */
object KeyLayouts {
    private fun letter(c: Char) = KeyChars(c, c.uppercaseChar(), caps = KeyChars.Caps.INVERT)

    private fun letters(row: String, firstScan: Int): Map<Int, KeyChars> =
        row.withIndex().associate { (i, c) -> firstScan + i to letter(c) }

    /** The letters of a QWERTY keyboard; Swiss and German swap Y (scan 21) and Z (scan 44). */
    private val qwertyLetters = letters("qwertyuiop", 16) + letters("asdfghjkl", 30) + letters("zxcvbnm", 44)

    private val qwertzLetters = qwertyLetters + mapOf(21 to letter('z'), 44 to letter('y'))

    private const val SPACE_SCAN = 57

    val US: Map<Int, KeyChars> = qwertyLetters + mapOf(
        41 to KeyChars('`', '~'),
        2 to KeyChars('1', '!'), 3 to KeyChars('2', '@'), 4 to KeyChars('3', '#'), 5 to KeyChars('4', '$'),
        6 to KeyChars('5', '%'), 7 to KeyChars('6', '^'), 8 to KeyChars('7', '&'), 9 to KeyChars('8', '*'),
        10 to KeyChars('9', '('), 11 to KeyChars('0', ')'), 12 to KeyChars('-', '_'), 13 to KeyChars('=', '+'),
        26 to KeyChars('[', '{'), 27 to KeyChars(']', '}'),
        39 to KeyChars(';', ':'), 40 to KeyChars('\'', '"'), 43 to KeyChars('\\', '|'),
        51 to KeyChars(',', '<'), 52 to KeyChars('.', '>'), 53 to KeyChars('/', '?'),
        86 to KeyChars('\\', '|'),
        SPACE_SCAN to KeyChars(' ', ' '),
    )

    val SWISS: Map<Int, KeyChars> = qwertzLetters + mapOf(
        18 to KeyChars('e', 'E', altGr = '€', caps = KeyChars.Caps.INVERT),
        41 to KeyChars('§', '°'),
        2 to KeyChars('1', '+', '¦'), 3 to KeyChars('2', '"', '@'), 4 to KeyChars('3', '*', '#'),
        5 to KeyChars('4', 'ç', '°'), 6 to KeyChars('5', '%', '§'), 7 to KeyChars('6', '&', '¬'),
        8 to KeyChars('7', '/', '|'), 9 to KeyChars('8', '(', '¢'), 10 to KeyChars('9', ')'), 11 to KeyChars('0', '='),
        12 to KeyChars('\'', '?', DeadKeys.ACUTE),
        13 to KeyChars(DeadKeys.CIRCUMFLEX, DeadKeys.GRAVE, DeadKeys.TILDE),
        26 to KeyChars('ü', 'è', '[', caps = KeyChars.Caps.UPPER),
        27 to KeyChars(DeadKeys.DIAERESIS, '!', ']'),
        39 to KeyChars('ö', 'é', caps = KeyChars.Caps.UPPER),
        40 to KeyChars('ä', 'à', '{', caps = KeyChars.Caps.UPPER),
        43 to KeyChars('$', '£', '}'),
        51 to KeyChars(',', ';'), 52 to KeyChars('.', ':'), 53 to KeyChars('-', '_'),
        86 to KeyChars('<', '>', '\\'),
        SPACE_SCAN to KeyChars(' ', ' '),
    )

    val GERMAN: Map<Int, KeyChars> = qwertzLetters + mapOf(
        16 to KeyChars('q', 'Q', altGr = '@', caps = KeyChars.Caps.INVERT),
        18 to KeyChars('e', 'E', altGr = '€', caps = KeyChars.Caps.INVERT),
        50 to KeyChars('m', 'M', altGr = 'µ', caps = KeyChars.Caps.INVERT),
        41 to KeyChars(DeadKeys.CIRCUMFLEX, '°'),
        2 to KeyChars('1', '!'), 3 to KeyChars('2', '"', '²'), 4 to KeyChars('3', '§', '³'), 5 to KeyChars('4', '$'),
        6 to KeyChars('5', '%'), 7 to KeyChars('6', '&'), 8 to KeyChars('7', '/', '{'), 9 to KeyChars('8', '(', '['),
        10 to KeyChars('9', ')', ']'), 11 to KeyChars('0', '=', '}'),
        12 to KeyChars('ß', '?', '\\', 'ẞ'),
        13 to KeyChars(DeadKeys.ACUTE, DeadKeys.GRAVE),
        26 to KeyChars('ü', 'Ü', caps = KeyChars.Caps.INVERT),
        27 to KeyChars('+', '*', '~'),
        39 to KeyChars('ö', 'Ö', caps = KeyChars.Caps.INVERT),
        40 to KeyChars('ä', 'Ä', caps = KeyChars.Caps.INVERT),
        43 to KeyChars('#', '\''),
        51 to KeyChars(',', ';'), 52 to KeyChars('.', ':'), 53 to KeyChars('-', '_'),
        86 to KeyChars('<', '>', '|'),
        SPACE_SCAN to KeyChars(' ', ' '),
    )

    /**
     * Key code, meta state and the character Android's US "Generic" map types for it. A keyboard whose
     * own map types all of these has no layout of its own: ours may replace it. A Swiss or German map
     * types other characters on at least Y, Z, [ or ;.
     */
    private val GENERIC_PROBES = listOf(
        Triple(KeyEvent.KEYCODE_Y, 0, 'y'),
        Triple(KeyEvent.KEYCODE_Z, 0, 'z'),
        Triple(KeyEvent.KEYCODE_LEFT_BRACKET, 0, '['),
        Triple(KeyEvent.KEYCODE_SEMICOLON, 0, ';'),
        Triple(KeyEvent.KEYCODE_APOSTROPHE, 0, '\''),
        Triple(KeyEvent.KEYCODE_MINUS, 0, '-'),
        Triple(KeyEvent.KEYCODE_GRAVE, 0, '`'),
        Triple(KeyEvent.KEYCODE_2, KeyEvent.META_SHIFT_ON, '@'),
    )

    /** Whether [charOf] (the keyboard's `KeyCharacterMap.get`) is Android's US Generic map. */
    fun isGeneric(charOf: (keyCode: Int, meta: Int) -> Int): Boolean =
        GENERIC_PROBES.all { (code, meta, c) -> charOf(code, meta) == c.code }
}

/** The layout chosen on the watch (Einstellungen → Tastatur & Maus); [label] is German. */
enum class KeyLayout(val id: String, val label: String) {
    /** By the watch's language: Swiss German → [SWISS], other German → [GERMAN], else the keyboard's own map. */
    AUTO("auto", "Automatisch"),
    SWISS("de-CH", "Schweiz"),
    GERMAN("de-DE", "Deutschland"),
    US("en-US", "USA"),
    ;

    /** The table for a watch set to [locale]; null keeps the keyboard's own map. */
    fun table(locale: Locale): Map<Int, KeyChars>? = when (this) {
        AUTO -> forLocale(locale)?.table(locale)
        SWISS -> KeyLayouts.SWISS
        GERMAN -> KeyLayouts.GERMAN
        US -> KeyLayouts.US
    }

    companion object {
        fun of(id: String?): KeyLayout = entries.firstOrNull { it.id == id } ?: AUTO

        /** What [AUTO] means on a watch set to [locale]: null for the keyboard's own map. */
        fun forLocale(locale: Locale): KeyLayout? = when {
            (locale.language == "de" || locale.language == "gsw") && locale.country == "CH" -> SWISS
            locale.language == "de" -> GERMAN
            else -> null
        }
    }
}

/**
 * Dead keys (´ ` ^ ~ ¨): they type nothing themselves, but put their accent on the next letter
 * (é, è, ê, ñ, ü). Space after one, or a letter that takes no such accent, types the accent itself.
 */
object DeadKeys {
    const val GRAVE = '̀'
    const val ACUTE = '́'
    const val CIRCUMFLEX = '̂'
    const val TILDE = '̃'
    const val DIAERESIS = '̈'

    private val SPACING = mapOf(GRAVE to '`', ACUTE to '´', CIRCUMFLEX to '^', TILDE to '~', DIAERESIS to '¨')

    fun isDead(c: Char): Boolean = c in '̀'..'ͯ'

    /** The accent as a character of its own. */
    fun spacing(accent: Char): Char = SPACING[accent] ?: accent

    /** [c] with [accent]: one character where Unicode has it (é), else the accent and [c] ("´x"). */
    fun compose(accent: Char, c: Char): String {
        if (c == ' ') return spacing(accent).toString()
        val composed = java.text.Normalizer.normalize("$c$accent", java.text.Normalizer.Form.NFC)
        return if (composed.length == 1) composed else "${spacing(accent)}$c"
    }
}

package ch.madtreasures.g2watch.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Keys

/**
 * A key of a Bluetooth keyboard as the app host uses it: the web's `KeyboardEvent.key` name ([Keys], or
 * the character), what it types, and the modifiers. [alt] is the left Alt only; AltGr is part of the
 * character. The same fields as [AppEvent.Key].
 */
data class KeyPress(
    val key: String,
    val text: String = "",
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val repeat: Boolean = false,
) {
    /** With Ctrl, Alt or Meta the key is a shortcut, not typing. */
    val plain: Boolean get() = !ctrl && !alt && !meta

    fun toEvent() = AppEvent.Key(key, text, shift, ctrl, alt, meta, repeat)
}

/** A key as Android's KeyEvent describes it, reduced to what [HardwareInput] needs. */
data class RawKey(
    val keyCode: Int,
    /** The Linux scan code: where the key sits on the keyboard, whatever its layout. */
    val scanCode: Int,
    /** KeyEvent meta state (Shift, Ctrl, Alt left/right, Meta, Caps Lock). */
    val meta: Int = 0,
    val repeat: Int = 0,
    /**
     * What the keyboard's own key map types for this key with Shift, AltGr and Caps Lock (not Ctrl,
     * Meta or left Alt): 0 for nothing; a dead key has [KeyCharacterMap.COMBINING_ACCENT] set.
     */
    val deviceChar: Int = 0,
)

/** The mouse buttons the host uses. */
enum class MouseButton { PRIMARY, SECONDARY, TERTIARY, BACK, FORWARD }

/**
 * Keyboard and mouse input in plain Kotlin (only Android's constants), so the JVM tests cover it:
 * a key becomes a [KeyPress] (with our own layout when the keyboard has none, and dead keys), the mouse
 * button state becomes the buttons just pressed, the wheel whole notches. Not thread-safe: the main
 * thread calls it.
 */
class HardwareInput {
    private var pendingAccent: Char? = null
    private var buttons = 0
    private var wheelRest = 0f
    private var lastBackMs = Long.MIN_VALUE / 2
    private var lastBackFromKey = false
    private var lastMoveMs = Long.MIN_VALUE / 2

    /**
     * [raw] (a key going down) as the host sees it, or null for keys that are no input of their own:
     * Shift, Ctrl and the other modifiers, a dead key waiting for its letter, keys without a meaning.
     * [table] replaces the keyboard's own characters (null: keep them, see [KeyLayout.table]).
     */
    fun key(raw: RawKey, table: Map<Int, KeyChars>? = null): KeyPress? {
        val m = raw.meta
        val shift = m and KeyEvent.META_SHIFT_ON != 0
        val ctrl = m and KeyEvent.META_CTRL_ON != 0
        val alt = m and KeyEvent.META_ALT_LEFT_ON != 0
        val meta = m and KeyEvent.META_META_ON != 0
        val repeat = raw.repeat > 0
        if (raw.keyCode in MODIFIERS) return null
        NAMES[raw.keyCode]?.let { name ->
            pendingAccent = null
            return KeyPress(name, "", shift, ctrl, alt, meta, repeat)
        }
        val shortcut = ctrl || alt || meta
        val c = charOf(raw, table) ?: return null
        if (DeadKeys.isDead(c)) {
            if (shortcut) return null
            val pending = pendingAccent
            pendingAccent = null
            // The same dead key twice types the accent itself.
            if (pending == c) return typed(DeadKeys.spacing(c).toString(), shift, repeat)
            pendingAccent = c
            return null
        }
        val pending = pendingAccent
        pendingAccent = null
        if (shortcut) return KeyPress(c.toString(), "", shift, ctrl, alt, meta, repeat)
        val text = if (pending != null) DeadKeys.compose(pending, c) else c.toString()
        return typed(text, shift, repeat)
    }

    private fun typed(text: String, shift: Boolean, repeat: Boolean) =
        KeyPress(if (text.length == 1) text else text.last().toString(), text, shift = shift, repeat = repeat)

    private fun charOf(raw: RawKey, table: Map<Int, KeyChars>?): Char? {
        val m = raw.meta
        table?.get(raw.scanCode)?.let { chars ->
            return chars.of(
                shift = m and KeyEvent.META_SHIFT_ON != 0,
                altGr = m and KeyEvent.META_ALT_RIGHT_ON != 0,
                capsLock = m and KeyEvent.META_CAPS_LOCK_ON != 0,
            )
        }
        val d = raw.deviceChar
        if (d == 0) return null
        if (d and KeyCharacterMap.COMBINING_ACCENT != 0) return (d and KeyCharacterMap.COMBINING_ACCENT_MASK).toChar()
        // Control characters (Escape, Enter …) have names; the rest types nothing visible.
        return d.toChar().takeIf { it >= ' ' && it != '\u007f' }
    }

    // --- Mouse ----------------------------------------------------------------------------------

    /** The buttons pressed since the last [buttonState] (a MotionEvent's `buttonState`). */
    fun pressed(buttonState: Int): List<MouseButton> = buttons(buttonState).first

    /** The buttons pressed and the buttons let go since the last [buttonState]. */
    fun buttons(buttonState: Int): Pair<List<MouseButton>, List<MouseButton>> {
        val down = buttonState and buttons.inv()
        val up = buttons and buttonState.inv()
        buttons = buttonState
        fun of(bits: Int) = BUTTONS.filter { (bit, _) -> bits and bit != 0 }.map { it.second }
        return Pair(of(down), of(up))
    }

    /**
     * [vscroll] (AXIS_VSCROLL: positive towards the top) as whole notches, positive further down. Parts
     * of a notch (smooth wheels, the Magic Mouse) add up.
     */
    fun wheel(vscroll: Float): Int {
        wheelRest += vscroll
        val notches = wheelRest.toInt()
        wheelRest -= notches
        return -notches
    }

    /**
     * Whether a "back" of the mouse at [nowMs] should act: one press of the back button reports itself
     * twice, as a button and as Android's own Back key ([fromKey]), at the same time. The other report of
     * the press that just acted, within [BACK_TWICE_MS], is dropped; a second press counts again, however
     * quick.
     */
    fun mouseBack(nowMs: Long, fromKey: Boolean): Boolean {
        if (fromKey != lastBackFromKey && nowMs - lastBackMs < BACK_TWICE_MS) {
            // The press is complete: the next report starts a new one.
            lastBackMs = Long.MIN_VALUE / 2
            return false
        }
        lastBackMs = nowMs
        lastBackFromKey = fromKey
        return true
    }

    /** Milliseconds since the last mouse move at [nowMs], for the pointer's acceleration (1 to [MOVE_GAP_MS]). */
    fun moveInterval(nowMs: Long): Float {
        val dt = (nowMs - lastMoveMs).coerceIn(1L, MOVE_GAP_MS)
        lastMoveMs = nowMs
        return dt.toFloat()
    }

    companion object {
        /** How far apart the two reports of one press of the mouse's back button may be. */
        const val BACK_TWICE_MS = 200L

        /** A move after a pause counts as slow: no acceleration from the time before. */
        const val MOVE_GAP_MS = 100L

        private val BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to MouseButton.PRIMARY,
            MotionEvent.BUTTON_SECONDARY to MouseButton.SECONDARY,
            MotionEvent.BUTTON_TERTIARY to MouseButton.TERTIARY,
            MotionEvent.BUTTON_BACK to MouseButton.BACK,
            MotionEvent.BUTTON_FORWARD to MouseButton.FORWARD,
        )

        /** Keys that only change others, or switch a lock. */
        private val MODIFIERS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
        )

        /** Keys that type nothing, by their web name. */
        private val NAMES: Map<Int, String> = mapOf(
            KeyEvent.KEYCODE_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_ESCAPE to Keys.ESCAPE,
            KeyEvent.KEYCODE_DEL to Keys.BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to Keys.DELETE,
            KeyEvent.KEYCODE_TAB to Keys.TAB,
            KeyEvent.KEYCODE_DPAD_UP to Keys.ARROW_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Keys.ARROW_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Keys.ARROW_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Keys.ARROW_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP to Keys.PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to Keys.PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME to Keys.HOME,
            KeyEvent.KEYCODE_MOVE_END to Keys.END,
            KeyEvent.KEYCODE_INSERT to Keys.INSERT,
            KeyEvent.KEYCODE_MENU to Keys.CONTEXT_MENU,
            KeyEvent.KEYCODE_F1 to Keys.F1,
            KeyEvent.KEYCODE_F2 to Keys.F2,
            KeyEvent.KEYCODE_F3 to Keys.F3,
            KeyEvent.KEYCODE_F4 to Keys.F4,
            KeyEvent.KEYCODE_F5 to Keys.F5,
            KeyEvent.KEYCODE_F6 to Keys.F6,
            KeyEvent.KEYCODE_F7 to Keys.F7,
            KeyEvent.KEYCODE_F8 to Keys.F8,
            KeyEvent.KEYCODE_F9 to Keys.F9,
            KeyEvent.KEYCODE_F10 to Keys.F10,
            KeyEvent.KEYCODE_F11 to Keys.F11,
            KeyEvent.KEYCODE_F12 to Keys.F12,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Keys.MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK to Keys.MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY to Keys.MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE to Keys.MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP to Keys.MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT to Keys.MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to Keys.MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Keys.MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND to Keys.MEDIA_REWIND,
            KeyEvent.KEYCODE_BACK to Keys.BROWSER_BACK,
            KeyEvent.KEYCODE_FORWARD to Keys.BROWSER_FORWARD,
        )
    }
}

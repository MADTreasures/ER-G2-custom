package ch.madtreasures.g2watch.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Keys, layouts, dead keys, mouse buttons and the wheel of a Bluetooth keyboard and mouse (03 §5.3). */
class HardwareInputTest {
    private val input = HardwareInput()

    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val altGr = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val caps = KeyEvent.META_CAPS_LOCK_ON

    /** A key at Linux scan code [scan] with the Swiss table. */
    private fun swiss(scan: Int, meta: Int = 0) = input.key(RawKey(KeyEvent.KEYCODE_UNKNOWN, scan, meta), KeyLayouts.SWISS)

    private fun german(scan: Int, meta: Int = 0) = input.key(RawKey(KeyEvent.KEYCODE_UNKNOWN, scan, meta), KeyLayouts.GERMAN)

    private fun typed(press: KeyPress?) = press?.text

    @Test
    fun `keys that type nothing get their web names`() {
        val names = mapOf(
            KeyEvent.KEYCODE_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to Keys.ENTER,
            KeyEvent.KEYCODE_ESCAPE to Keys.ESCAPE,
            KeyEvent.KEYCODE_DEL to Keys.BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to Keys.DELETE,
            KeyEvent.KEYCODE_TAB to Keys.TAB,
            KeyEvent.KEYCODE_DPAD_UP to Keys.ARROW_UP,
            KeyEvent.KEYCODE_DPAD_LEFT to Keys.ARROW_LEFT,
            KeyEvent.KEYCODE_PAGE_DOWN to Keys.PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME to Keys.HOME,
            KeyEvent.KEYCODE_MENU to Keys.CONTEXT_MENU,
            KeyEvent.KEYCODE_F10 to Keys.F10,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Keys.MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BACK to Keys.BROWSER_BACK,
        )
        for ((code, name) in names) {
            // Android's map gives Enter and Tab a control character; they still type nothing.
            assertEquals(KeyPress(name), input.key(RawKey(code, 0, deviceChar = '\n'.code)))
        }
        assertEquals(KeyPress(Keys.TAB, shift = true), input.key(RawKey(KeyEvent.KEYCODE_TAB, 15, shift)))
        assertEquals(KeyPress(Keys.ARROW_LEFT, alt = true, repeat = true), input.key(RawKey(KeyEvent.KEYCODE_DPAD_LEFT, 105, alt, repeat = 2)))
    }

    @Test
    fun `modifiers and locks alone are no keys`() {
        for (code in listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_META_LEFT)) {
            assertNull(input.key(RawKey(code, 42)))
        }
    }

    @Test
    fun `without a table the keyboard's own map types`() {
        assertEquals(KeyPress("a", "a"), input.key(RawKey(KeyEvent.KEYCODE_A, 30, deviceChar = 'a'.code)))
        assertEquals(KeyPress("A", "A", shift = true), input.key(RawKey(KeyEvent.KEYCODE_A, 30, shift, deviceChar = 'A'.code)))
        assertEquals(KeyPress(" ", " "), input.key(RawKey(KeyEvent.KEYCODE_SPACE, 57, deviceChar = ' '.code)))
        // A key the map has nothing for is no input.
        assertNull(input.key(RawKey(KeyEvent.KEYCODE_UNKNOWN, 0)))
        // A table without the key (the number pad) falls back to the keyboard's map.
        assertEquals("7", typed(input.key(RawKey(KeyEvent.KEYCODE_NUMPAD_7, 71, deviceChar = '7'.code), KeyLayouts.SWISS)))
    }

    @Test
    fun `with Ctrl, Alt or Meta a key is a shortcut that types nothing`() {
        assertEquals(KeyPress("l", ctrl = true), input.key(RawKey(KeyEvent.KEYCODE_L, 38, ctrl, deviceChar = 'l'.code)))
        assertEquals(KeyPress("L", shift = true, ctrl = true), input.key(RawKey(KeyEvent.KEYCODE_L, 38, ctrl or shift, deviceChar = 'L'.code)))
        assertEquals(KeyPress("d", ctrl = true), swiss(32, ctrl))
        assertEquals(KeyPress("l", alt = true), swiss(38, alt))
        assertFalse(KeyPress("l", ctrl = true).plain)
        assertTrue(KeyPress("l", "l").plain)
    }

    @Test
    fun `the Swiss layout swaps Y and Z and types umlauts and its symbols`() {
        assertEquals("z", typed(swiss(21)))
        assertEquals("y", typed(swiss(44)))
        assertEquals("Z", typed(swiss(21, shift)))
        assertEquals("ü", typed(swiss(26)))
        assertEquals("è", typed(swiss(26, shift)))
        assertEquals("ö", typed(swiss(39)))
        assertEquals("é", typed(swiss(39, shift)))
        assertEquals("à", typed(swiss(40, shift)))
        assertEquals("\"", typed(swiss(3, shift)))
        assertEquals("@", typed(swiss(3, altGr)))
        assertEquals("€", typed(swiss(18, altGr)))
        assertEquals("<", typed(swiss(86)))
        assertEquals("\\", typed(swiss(86, altGr)))
        assertEquals("$", typed(swiss(43)))
        assertEquals("-", typed(swiss(53)))
        assertEquals("/", typed(swiss(8, shift)))
        assertEquals("ç", typed(swiss(5, shift)))
    }

    @Test
    fun `caps lock capitalises letters and the Swiss accented keys`() {
        assertEquals("A", typed(swiss(30, caps)))
        assertEquals("a", typed(swiss(30, caps or shift)))
        assertEquals("Ü", typed(swiss(26, caps)))
        assertEquals("È", typed(swiss(26, caps or shift)))
        assertEquals("1", typed(swiss(2, caps)))
        assertEquals("Ü", typed(german(26, caps)))
        assertEquals("ü", typed(german(26, caps or shift)))
    }

    @Test
    fun `the German layout`() {
        assertEquals("z", typed(german(21)))
        assertEquals("ß", typed(german(12)))
        assertEquals("?", typed(german(12, shift)))
        assertEquals("@", typed(german(16, altGr)))
        assertEquals("{", typed(german(8, altGr)))
        assertEquals("#", typed(german(43)))
        assertEquals("|", typed(german(86, altGr)))
        assertEquals("µ", typed(german(50, altGr)))
    }

    @Test
    fun `dead keys put their accent on the next letter`() {
        // Swiss: ^ alone, ´ with AltGr on the key right of 0, ¨ right of ü.
        assertNull(swiss(13))
        assertEquals("ê", typed(swiss(18)))
        assertNull(swiss(12, altGr))
        assertEquals(KeyPress("é", "é"), swiss(18))
        assertNull(swiss(27))
        assertEquals("ä", typed(swiss(30)))
        assertNull(swiss(13, shift))
        assertEquals("À", typed(swiss(30, shift)))
        // Space types the accent itself, as does the dead key twice; a letter without such a form gets both.
        assertNull(swiss(13))
        assertEquals("^", typed(swiss(57)))
        assertNull(swiss(13))
        assertEquals("^", typed(swiss(13)))
        assertNull(german(13))
        assertEquals("´x", typed(german(45)))
        // The German ^ sits left of 1.
        assertNull(german(41))
        assertEquals("û", typed(german(22)))
    }

    @Test
    fun `a key that types nothing forgets a waiting accent`() {
        assertNull(swiss(13))
        assertEquals(KeyPress(Keys.ARROW_LEFT), input.key(RawKey(KeyEvent.KEYCODE_DPAD_LEFT, 105), KeyLayouts.SWISS))
        assertEquals("e", typed(swiss(18)))
    }

    @Test
    fun `the keyboard's own dead keys work the same`() {
        val acute = KeyCharacterMap.COMBINING_ACCENT or 0x0301
        assertNull(input.key(RawKey(KeyEvent.KEYCODE_EQUALS, 13, deviceChar = acute)))
        assertEquals("é", typed(input.key(RawKey(KeyEvent.KEYCODE_E, 18, deviceChar = 'e'.code))))
    }

    @Test
    fun `Android's US Generic map is recognised by what it types`() {
        val generic = mapOf(
            KeyEvent.KEYCODE_Y to 'y', KeyEvent.KEYCODE_Z to 'z', KeyEvent.KEYCODE_LEFT_BRACKET to '[',
            KeyEvent.KEYCODE_SEMICOLON to ';', KeyEvent.KEYCODE_APOSTROPHE to '\'', KeyEvent.KEYCODE_MINUS to '-',
            KeyEvent.KEYCODE_GRAVE to '`',
        )
        fun us(code: Int, meta: Int) = if (code == KeyEvent.KEYCODE_2 && meta == KeyEvent.META_SHIFT_ON) '@'.code else generic[code]?.code ?: 0
        assertTrue(KeyLayouts.isGeneric(::us))
        // A Swiss map types z on KEYCODE_Y and ü on the bracket.
        assertFalse(KeyLayouts.isGeneric { code, meta -> if (code == KeyEvent.KEYCODE_Y) 'z'.code else us(code, meta) })
        assertFalse(KeyLayouts.isGeneric { code, meta -> if (code == KeyEvent.KEYCODE_LEFT_BRACKET) 'ü'.code else us(code, meta) })
    }

    @Test
    fun `automatic layout by the watch's language`() {
        assertEquals(KeyLayout.SWISS, KeyLayout.forLocale(Locale.forLanguageTag("de-CH")))
        assertEquals(KeyLayout.SWISS, KeyLayout.forLocale(Locale.forLanguageTag("gsw-CH")))
        assertEquals(KeyLayout.GERMAN, KeyLayout.forLocale(Locale.GERMANY))
        assertEquals(KeyLayout.GERMAN, KeyLayout.forLocale(Locale.forLanguageTag("de-AT")))
        assertNull(KeyLayout.forLocale(Locale.US))
        assertNull(KeyLayout.forLocale(Locale.forLanguageTag("fr-CH")))
        assertEquals(KeyLayouts.SWISS, KeyLayout.AUTO.table(Locale.forLanguageTag("de-CH")))
        assertNull(KeyLayout.AUTO.table(Locale.US))
        assertEquals(KeyLayouts.US, KeyLayout.US.table(Locale.forLanguageTag("de-CH")))
        assertEquals(KeyLayouts.GERMAN, KeyLayout.GERMAN.table(Locale.US))
        assertEquals(KeyLayout.AUTO, KeyLayout.of(null))
        assertEquals(KeyLayout.AUTO, KeyLayout.of("fantasie"))
        assertEquals(KeyLayout.SWISS, KeyLayout.of("de-CH"))
    }

    @Test
    fun `the US table types what Android's Generic map types`() {
        val us = HardwareInput()
        fun key(scan: Int, meta: Int = 0) = us.key(RawKey(KeyEvent.KEYCODE_UNKNOWN, scan, meta), KeyLayouts.US)?.text
        assertEquals("y", key(21))
        assertEquals("@", key(3, shift))
        assertEquals("[", key(26))
        assertEquals("?", key(53, shift))
    }

    @Test
    fun `a key press becomes the app event with the same fields`() {
        assertEquals(AppEvent.Key("é", "é", shift = true, repeat = true), KeyPress("é", "é", shift = true, repeat = true).toEvent())
    }

    @Test
    fun `mouse buttons count once when they go down`() {
        assertEquals(listOf(MouseButton.PRIMARY), input.pressed(MotionEvent.BUTTON_PRIMARY))
        assertEquals(emptyList<MouseButton>(), input.pressed(MotionEvent.BUTTON_PRIMARY))
        assertEquals(listOf(MouseButton.SECONDARY), input.pressed(MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY))
        assertEquals(emptyList<MouseButton>(), input.pressed(0))
        assertEquals(listOf(MouseButton.TERTIARY, MouseButton.BACK), input.pressed(MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK))
        assertEquals(listOf(MouseButton.FORWARD), input.pressed(MotionEvent.BUTTON_FORWARD))
    }

    @Test
    fun `wheel parts add up to notches, positive downwards`() {
        assertEquals(1, input.wheel(-1f))
        assertEquals(-2, input.wheel(2f))
        assertEquals(0, input.wheel(0.4f))
        assertEquals(-1, input.wheel(0.7f))
        assertEquals(0, input.wheel(-0.05f))
    }

    @Test
    fun `the back button counts once although Android reports it twice`() {
        assertTrue(input.mouseBack(1_000))
        assertFalse(input.mouseBack(1_050))
        assertTrue(input.mouseBack(1_000 + HardwareInput.BACK_TWICE_MS))
    }

    @Test
    fun `the time between mouse moves is kept within bounds`() {
        assertEquals(HardwareInput.MOVE_GAP_MS.toFloat(), input.moveInterval(5_000))
        assertEquals(8f, input.moveInterval(5_008))
        assertEquals(1f, input.moveInterval(5_008))
    }

    @Test
    fun `input devices are told apart by their Bluetooth class or appearance`() {
        assertEquals(HidKind.KEYBOARD, HidKind.ofClass(0x0540))
        assertEquals(HidKind.MOUSE, HidKind.ofClass(0x0580))
        assertEquals(HidKind.COMBO, HidKind.ofClass(0x05C0))
        assertEquals(HidKind.OTHER, HidKind.ofClass(0x0508))
        assertNull(HidKind.ofClass(0x0418))
        assertEquals(HidKind.KEYBOARD, HidKind.ofAppearance(0x03C1))
        assertEquals(HidKind.MOUSE, HidKind.ofAppearance(0x03C2))
        assertEquals(HidKind.OTHER, HidKind.ofAppearance(0x03C4))
        assertNull(HidKind.ofAppearance(0x0040))
        val advertisement = byteArrayOf(0x02, 0x01, 0x06, 0x03, 0x19, 0xC2.toByte(), 0x03, 0x03, 0x03, 0x12, 0x18)
        assertEquals(0x03C2, HidKind.appearance(advertisement))
        assertEquals(-1, HidKind.appearance(byteArrayOf(0x02, 0x01, 0x06)))
        assertEquals(-1, HidKind.appearance(null))
    }
}

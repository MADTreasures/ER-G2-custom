package ch.madtreasures.g2watch.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.host.HardwareSink
import ch.madtreasures.g2watch.apps.host.InputRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Android's key and mouse events through [HardwareEvents] to the router (Robolectric's KeyEvent and MotionEvent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HardwareEventsTest {
    private val calls = mutableListOf<String>()
    private val gestures = mutableListOf<Gesture>()
    private var gestureMode = false

    private val sink = object : HardwareSink {
        override val gestureMode get() = this@HardwareEventsTest.gestureMode
        override val speed = 1f

        override fun movePointer(dx: Float, dy: Float) {
            calls += "move"
        }

        override fun click() {
            calls += "click"
        }

        override fun press() {
            calls += "press"
        }

        override fun release() {
            calls += "release"
        }

        override fun menu() {
            calls += "menu"
        }

        override fun back() {
            calls += "back"
        }

        override fun wheel(notches: Int) {
            calls += "wheel $notches"
        }

        override fun key(press: KeyPress) {
            calls += "key ${press.key}" + if (press.text.isNotEmpty() && press.text != press.key) " ${press.text}" else ""
        }
    }

    private var keyboard = true
    private var mouse = false
    private var generic = true

    private val events = HardwareEvents(
        InputRouter(sink) { gestures += it },
        layout = { KeyLayout.AUTO },
        locale = { Locale.forLanguageTag("de-CH") },
        isGeneric = { generic },
        hasKeys = { keyboard },
        isMouse = { mouse },
    )

    private fun key(code: Int, scan: Int, action: Int = KeyEvent.ACTION_DOWN, meta: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return events.key(KeyEvent(now, now, action, code, 0, meta, 7, scan))
    }

    private fun mouseEvent(
        action: Int,
        buttons: Int = 0,
        x: Float = 0f,
        y: Float = 0f,
        relX: Float = 0f,
        relY: Float = 0f,
        vscroll: Float = 0f,
        source: Int = InputDevice.SOURCE_MOUSE,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, relX)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, relY)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coords), 0, buttons, 1f, 1f, 7, 0, source, 0)
    }

    @Test
    fun `a keyboard's keys go to the glasses in our layout, key ups are swallowed`() {
        assertTrue(key(KeyEvent.KEYCODE_Y, 21))
        assertTrue(key(KeyEvent.KEYCODE_Y, 21, KeyEvent.ACTION_UP))
        assertTrue(key(KeyEvent.KEYCODE_SHIFT_LEFT, 42))
        assertTrue(key(KeyEvent.KEYCODE_ENTER, 28))
        assertEquals(listOf("key z", "key ${Keys.ENTER}"), calls)
        // A keyboard with a layout of its own types with it.
        generic = false
        key(KeyEvent.KEYCODE_Y, 21)
        assertEquals("key y", calls.last())
    }

    @Test
    fun `volume keys and the watch's own buttons stay the system's`() {
        assertFalse(key(KeyEvent.KEYCODE_VOLUME_UP, 115))
        keyboard = false
        assertFalse(key(KeyEvent.KEYCODE_STEM_PRIMARY, 0))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the mouse's back button counts once, as button and as Android's back key`() {
        keyboard = false
        mouse = true
        assertTrue(key(KeyEvent.KEYCODE_BACK, 0))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_BACK)))
        assertTrue(key(KeyEvent.KEYCODE_BACK, 0, KeyEvent.ACTION_UP))
        assertTrue(key(KeyEvent.KEYCODE_FORWARD, 0))
        assertEquals(listOf("back"), calls)
        // Pressed again right away (the test clock stands still): a second back.
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE)))
        assertTrue(key(KeyEvent.KEYCODE_BACK, 0))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_BACK)))
        assertEquals(listOf("back", "back"), calls)
    }

    @Test
    fun `without capture the mouse's relative axes move the pointer, buttons press and let go, the wheel scrolls`() {
        mouse = true
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x = 455f, y = 455f, relX = 6f, relY = -2f)))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY)))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_UP)))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY)))
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_SCROLL, vscroll = -1f)))
        // At the edge of the watch the cursor stands still, the relative axes go on.
        assertTrue(events.motion(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x = 455f, y = 455f)))
        assertEquals(listOf("move", "press", "release", "menu", "wheel 1"), calls)
        // A finger on the watch is no mouse.
        assertFalse(events.motion(mouseEvent(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN)))
    }

    @Test
    fun `with a keyboard and a mouse there, the watch's own touchpad and crown stay the watch's`() {
        mouse = true
        // A finger on the touchpad: it moves, lifts, and taps twice (a click).
        val finger = listOf(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
        )
        for (action in finger) {
            assertFalse(events.motion(mouseEvent(action, x = 200f, y = 220f, source = InputDevice.SOURCE_TOUCHSCREEN)))
        }
        // The crown (pointer speed).
        assertFalse(events.motion(mouseEvent(MotionEvent.ACTION_SCROLL, vscroll = 1f, source = InputDevice.SOURCE_ROTARY_ENCODER)))
        assertTrue(calls.isEmpty())
        assertTrue(gestures.isEmpty())
    }

    @Test
    fun `keys and touches from no device outside the watch are never taken`() {
        // Android's own predicates: the watch's buttons, screen and crown are no external devices.
        val own = HardwareEvents(InputRouter(sink) { gestures += it }, layout = { KeyLayout.AUTO })
        val now = SystemClock.uptimeMillis()
        assertFalse(own.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STEM_PRIMARY, 0)))
        assertFalse(own.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)))
        assertFalse(own.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)))
        assertFalse(own.motion(mouseEvent(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `captured, the movement is in x and y, and with gestures the buttons and wheel are gestures`() {
        assertTrue(events.captured(mouseEvent(MotionEvent.ACTION_MOVE, x = 5f, y = -3f)))
        assertEquals(listOf("move"), calls)
        gestureMode = true
        events.captured(mouseEvent(MotionEvent.ACTION_MOVE, x = 5f))
        events.captured(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY))
        events.captured(mouseEvent(MotionEvent.ACTION_SCROLL, vscroll = 1f))
        assertEquals(listOf("move"), calls)
        assertEquals(
            listOf(Gesture(GestureKind.CLICK, InputSource.WATCH), Gesture(GestureKind.SCROLL_UP, InputSource.WATCH)),
            gestures,
        )
    }

    @Test
    fun `the typing meta state keeps Shift, the locks and AltGr, not the shortcut keys`() {
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
            KeyEvent.META_CAPS_LOCK_ON
        assertEquals(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_CAPS_LOCK_ON, HardwareEvents.typingMeta(meta))
        assertEquals(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, HardwareEvents.typingMeta(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON))
        assertEquals(0, HardwareEvents.typingMeta(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_META_ON))
    }
}

package ch.madtreasures.g2watch.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.apps.host.InputRouter
import java.util.Locale

/**
 * Android's key and mouse events of a Bluetooth keyboard and mouse, for the glasses (03 §5.4): what
 * comes from an external device becomes [HardwareInput]'s keys, buttons, wheel notches and moves for
 * [router]. The activity hands events here only while the touchpad shows and the glasses are
 * connected; elsewhere the watch's own screens get them as usual. Main thread.
 *
 * [isMouse] and [hasKeys] tell the devices apart (by default from Android's InputDevice); [layout]
 * and [locale] choose our own key map ([KeyLayout.table]), which replaces the keyboard's only where
 * that is Android's US Generic map ([isGeneric]).
 */
class HardwareEvents(
    private val router: InputRouter,
    private val layout: () -> KeyLayout,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val isGeneric: (InputDevice?) -> Boolean = { false },
    private val hasKeys: (InputDevice?) -> Boolean = InputDevices::hasKeys,
    private val isMouse: (InputDevice?) -> Boolean = InputDevices::isMouse,
) {
    private val input = HardwareInput()

    /** A key event of the activity; true if it was taken for the glasses. */
    fun key(event: KeyEvent): Boolean {
        val device = event.device
        val mouse = isMouse(device)
        if (!mouse && !hasKeys(device)) return false
        val code = event.keyCode
        // Volume and power stay the system's.
        if (code in SYSTEM_KEYS) return false
        if (mouse && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_FORWARD)) {
            // Android adds these keys to the mouse's back and forward buttons; the button already counts.
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && input.mouseBack(event.eventTime, fromKey = true)) {
                router.onMouseButton(MouseButton.BACK)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val meta = event.metaState
        val raw = RawKey(code, event.scanCode, meta, event.repeatCount, event.getUnicodeChar(typingMeta(meta)))
        val table = if (isGeneric(device)) layout().table(locale()) else null
        input.key(raw, table)?.let(router::onKey)
        return true
    }

    /** A mouse event that reached the activity (no pointer capture); true if it was taken. */
    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) || !isMouse(event.device)) return false
        mouse(event, captured = false)
        return true
    }

    /** A mouse event while the touchpad has the pointer captured: its x and y are the movement. */
    fun captured(event: MotionEvent): Boolean {
        mouse(event, captured = true)
        return true
    }

    private fun mouse(event: MotionEvent, captured: Boolean) {
        for (button in input.pressed(event.buttonState)) {
            if (button == MouseButton.BACK && !input.mouseBack(event.eventTime, fromKey = false)) continue
            router.onMouseButton(button)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> router.onWheel(input.wheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL)))
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = 0f
                var dy = 0f
                for (i in 0 until event.historySize) {
                    dx += if (captured) event.getHistoricalX(i) else event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i)
                    dy += if (captured) event.getHistoricalY(i) else event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i)
                }
                dx += if (captured) event.x else event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                dy += if (captured) event.y else event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) router.onMouseMove(dx, dy, input.moveInterval(event.eventTime))
            }
        }
    }

    companion object {
        private val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        )

        /** The meta state for the character a key types: Shift, Caps/Num Lock and AltGr, no shortcut keys. */
        fun typingMeta(meta: Int): Int {
            val kept = meta and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON)
            val altGr = if (meta and KeyEvent.META_ALT_RIGHT_ON != 0) KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON else 0
            return kept or altGr
        }
    }
}

/**
 * Pointer capture for the touchpad while a mouse is there: Android then sends the mouse's own counts
 * to [onEvent] and shows no cursor on the watch, and the movement does not stop at the watch's edge.
 * Capture ends whenever the window loses its focus (another screen, the watch's text input) and is
 * asked for again when it comes back. Without capture the mouse events reach the activity, which
 * reads their relative axes instead ([HardwareEvents.motion]). Main thread.
 */
class MouseCapture(private val onEvent: (MotionEvent) -> Boolean) {
    private var view: View? = null
    private var wanted = false

    /** Capture on [view] (the focused view of the touchpad), if [wanted]. */
    fun set(view: View, wanted: Boolean) {
        if (this.view !== view) {
            this.view?.let(::release)
            this.view = view
            view.setOnCapturedPointerListener { _, event -> onEvent(event) }
        }
        this.wanted = wanted
        if (wanted) request() else if (view.hasPointerCapture()) view.releasePointerCapture()
    }

    /** The touchpad left: no capture, no listener. */
    fun clear(view: View) {
        if (this.view !== view) return
        release(view)
        this.view = null
        wanted = false
    }

    fun windowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && wanted) request()
    }

    private fun request() {
        val v = view ?: return
        if (v.hasWindowFocus() && !v.hasPointerCapture()) v.requestPointerCapture()
    }

    private fun release(v: View) {
        v.setOnCapturedPointerListener(null)
        if (v.hasPointerCapture()) v.releasePointerCapture()
    }
}

/**
 * Keeps the system back that Android makes of a mouse's back button off the watch (03 §5.4). Android
 * reports that button also as its Back key. With predictive back (the default for this app's target SDK
 * from Android 16 on) the window hands that key straight to the back callbacks, before
 * `dispatchKeyEvent` sees it, and the touchpad has none: G2 Watch would go to the background. So from
 * the moment the button goes down until [RELEASE_MS] after it is up, [arm] (true) asks the activity for a
 * back callback that swallows that one back ([used]); at any other time the watch's own back gesture
 * finds no such callback and works as before. Main thread.
 */
class MouseBackGuard(private val scheduler: Scheduler, private val arm: (Boolean) -> Unit) {
    private var down = false
    private var armed = false
    private var generation = 0

    /** The button state of a mouse event on the touchpad. */
    fun buttons(buttonState: Int) {
        val nowDown = buttonState and MotionEvent.BUTTON_BACK != 0
        if (nowDown == down) return
        down = nowDown
        if (nowDown) {
            set(true)
            // Should the release get lost (the window lost its focus meanwhile), the guard still ends.
            disarmAfter(HOLD_LIMIT_MS)
        } else if (armed) {
            disarmAfter(RELEASE_MS)
        }
    }

    /** The back callback swallowed the mouse's back. */
    fun used() = set(false)

    private fun disarmAfter(ms: Long) {
        val g = ++generation
        scheduler.postDelayed(ms) { if (g == generation) set(false) }
    }

    private fun set(on: Boolean) {
        if (!on) generation++
        if (on == armed) return
        armed = on
        arm(on)
    }

    companion object {
        /** Android's Back key follows the button's release at once; this leaves room for a busy main thread. */
        const val RELEASE_MS = 500L

        const val HOLD_LIMIT_MS = 10_000L
    }
}

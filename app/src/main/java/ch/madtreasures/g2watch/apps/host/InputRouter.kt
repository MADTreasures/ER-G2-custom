package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.desktop.PointerMotion
import ch.madtreasures.g2watch.input.KeyPress
import ch.madtreasures.g2watch.input.MouseButton
import com.faceclaw.app.BleProtocol
import kotlin.math.abs

/** A gesture of the wearer, from the watch, a temple or the ring. */
data class Gesture(val kind: GestureKind, val source: InputSource)

/**
 * Where a Bluetooth keyboard and mouse go besides gestures (03 §5.4): the desktop for the pointer and
 * its clicks, the app host for keys, the wheel and the app menu. Any thread.
 */
interface HardwareSink {
    /** The page on the glasses takes gestures, without a pointer (02 §7). */
    val gestureMode: Boolean

    /** Pointer speed factor (crown, settings), also for the mouse. */
    val speed: Float

    /** Moves the pointer by glasses pixels. */
    fun movePointer(dx: Float, dy: Float)

    /** A click at the pointer. */
    fun click()

    /** The app menu of the app on the glasses. */
    fun menu()

    /** Back: closes the window on the desktop, back in an app. */
    fun back()

    /** The wheel turned by [notches], positive further down. */
    fun wheel(notches: Int)

    fun key(press: KeyPress)

    companion object {
        val NONE: HardwareSink = object : HardwareSink {
            override val gestureMode = false
            override val speed = 1f

            override fun movePointer(dx: Float, dy: Float) = Unit

            override fun click() = Unit

            override fun menu() = Unit

            override fun back() = Unit

            override fun wheel(notches: Int) = Unit

            override fun key(press: KeyPress) = Unit
        }
    }
}

/**
 * Turns what the glasses report through Faceclaw's `onRingEvent` into [Gesture]s (03 §5.1, after
 * Faceclaw's own translation, wissen/03 §2.11.2), drops the ring's repeated reports like Faceclaw does
 * (§2.11.3), and passes the watch touchpad's gestures on. Everything goes to [sink]; the app host
 * decides what a gesture means where. Keyboard and mouse go to [hardware], or as gestures of the watch
 * to [sink] while the page takes gestures. Not thread-safe: call it from one thread (the main thread).
 */
class InputRouter(private val hardware: HardwareSink = HardwareSink.NONE, private val sink: (Gesture) -> Unit) {
    /** Ring clock of the last accepted ring report; 0 = none yet. */
    private var lastRingTick = 0L

    /**
     * One input report of the glasses. [ringTick] and [ringType] are the ring's own metadata when the
     * firmware forwarded a ring report (tick ≥ 0), else -1 and 0. Returns the gesture passed on, or null
     * when the report is no gesture or a repeat.
     */
    fun onGlassesEvent(kind: String?, eventType: Int, eventSource: Int, ringTick: Long = -1, ringType: Int = 0): Gesture? {
        if (ringTick >= 0 && !acceptRing(ringTick, ringType)) return null
        val gesture = translate(kind, eventType, eventSource) ?: return null
        sink(gesture)
        return gesture
    }

    /** A gesture of the watch touchpad in gesture mode. */
    fun onWatchGesture(kind: GestureKind) = sink(Gesture(kind, InputSource.WATCH))

    /** A key of a Bluetooth keyboard; the app host decides what it means on the page it shows. */
    fun onKey(press: KeyPress) = hardware.key(press)

    /**
     * The mouse moved by [dx]/[dy] of its own counts within [dtMs]: the pointer follows. With gestures
     * there is no pointer, and moving means nothing.
     */
    fun onMouseMove(dx: Float, dy: Float, dtMs: Float) {
        if (hardware.gestureMode || (dx == 0f && dy == 0f)) return
        val (gx, gy) = PointerMotion.fromMouse(dx, dy, dtMs, hardware.speed)
        hardware.movePointer(gx, gy)
    }

    /**
     * A mouse button went down: left clicks at the pointer (a tap in gesture mode), right opens the app
     * menu, the middle and the back button go back.
     */
    fun onMouseButton(button: MouseButton) {
        when (button) {
            MouseButton.PRIMARY -> if (hardware.gestureMode) onWatchGesture(GestureKind.CLICK) else hardware.click()
            MouseButton.SECONDARY -> hardware.menu()
            MouseButton.TERTIARY, MouseButton.BACK -> hardware.back()
            MouseButton.FORWARD -> Unit
        }
    }

    /**
     * The wheel turned by [notches] (positive further down): the page or the web page under the pointer
     * scrolls; in gesture mode each notch is a swipe on the watch (finger up = [GestureKind.SCROLL_DOWN]),
     * at most [MAX_WHEEL_GESTURES] at once.
     */
    fun onWheel(notches: Int) {
        if (notches == 0) return
        if (!hardware.gestureMode) {
            hardware.wheel(notches)
            return
        }
        val kind = if (notches > 0) GestureKind.SCROLL_DOWN else GestureKind.SCROLL_UP
        repeat(minOf(abs(notches), MAX_WHEEL_GESTURES)) { onWatchGesture(kind) }
    }

    /** Forgets the ring clock, e.g. after a new connection. */
    fun reset() {
        lastRingTick = 0L
    }

    /**
     * Faceclaw's filter on the ring clock: the ring repeats a report for a while, and the firmware
     * forwards every repeat. A report within 100 ticks of the last accepted one is dropped (and does
     * not move the clock); a release (type 8) always passes, a press (type 10) never filters nor moves it.
     */
    private fun acceptRing(tick: Long, type: Int): Boolean {
        if (type != RING_RELEASE && type != RING_PRESS && lastRingTick != 0L &&
            ((tick - lastRingTick) and 0xFFFF_FFFFL) < RING_WINDOW
        ) {
            return false
        }
        if (type != RING_PRESS) lastRingTick = tick
        return true
    }

    companion object {
        const val RING_WINDOW = 100L

        /** A fast spin of the wheel is not a dozen jumps of a video. */
        const val MAX_WHEEL_GESTURES = 3
        private const val RING_RELEASE = 8
        private const val RING_PRESS = 10

        /** The raw report as a gesture (03 §5.1), or null if it is none. */
        fun translate(kind: String?, eventType: Int, eventSource: Int): Gesture? = when (kind) {
            "sys-event" -> when (eventType) {
                BleProtocol.EVENT_CLICK -> Gesture(GestureKind.CLICK, side(eventSource))
                BleProtocol.EVENT_DOUBLE_CLICK -> Gesture(GestureKind.DOUBLE_CLICK, side(eventSource))
                BleProtocol.EVENT_SCROLL_TOP -> Gesture(GestureKind.SCROLL_UP, side(eventSource))
                BleProtocol.EVENT_SCROLL_BOTTOM -> Gesture(GestureKind.SCROLL_DOWN, side(eventSource))
                BleProtocol.EVENT_RING_LONG_PRESS -> Gesture(GestureKind.LONG_PRESS, side(eventSource))
                BleProtocol.EVENT_RING_LONG_PRESS_RELEASE -> Gesture(GestureKind.LONG_PRESS_RELEASE, side(eventSource))
                BleProtocol.EVENT_SHORT_THEN_LONG_PRESS -> Gesture(GestureKind.SHORT_THEN_LONG_PRESS, side(eventSource))
                BleProtocol.EVENT_HEAD_UP -> Gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN)
                EVENT_PRESS -> Gesture(
                    GestureKind.PRESS,
                    if (eventSource == 0 || eventSource == BleProtocol.EVENT_SOURCE_RING) InputSource.RING else InputSource.UNKNOWN,
                )
                else -> null
            }
            // A swipe on a temple arrives as a scroll on the text container, without a side.
            "text-click" -> when (eventType) {
                BleProtocol.EVENT_SCROLL_TOP -> Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN)
                BleProtocol.EVENT_SCROLL_BOTTOM -> Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)
                else -> null
            }
            // Only the head-up; a double tap that woke the display is no input for an app.
            "display-wake" -> if (eventType == BleProtocol.EVENT_HEAD_UP) Gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN) else null
            else -> null
        }

        /** Touch begins, forwarded by the custom firmware (Faceclaw's `ring-press`). */
        const val EVENT_PRESS = 14

        private fun side(source: Int): InputSource = when (source) {
            BleProtocol.EVENT_SOURCE_GLASSES_R -> InputSource.RIGHT
            BleProtocol.EVENT_SOURCE_RING -> InputSource.RING
            BleProtocol.EVENT_SOURCE_GLASSES_L -> InputSource.LEFT
            else -> InputSource.UNKNOWN
        }
    }
}

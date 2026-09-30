package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource

/** One gesture from the glasses, after translation and de-duplication. */
data class GlassesInput(val gesture: GestureKind, val source: InputSource) {
    /** German, for the status line on the watch. */
    val label: String get() = "${gestureLabel(gesture)} (${sourceLabel(source)})"

    companion object {
        fun gestureLabel(g: GestureKind): String = when (g) {
            GestureKind.CLICK -> "Tipp"
            GestureKind.DOUBLE_CLICK -> "Doppeltipp"
            GestureKind.SCROLL_UP -> "Wisch vor"
            GestureKind.SCROLL_DOWN -> "Wisch zurück"
            GestureKind.LONG_PRESS -> "Halten"
            GestureKind.LONG_PRESS_RELEASE -> "Loslassen"
            GestureKind.SHORT_THEN_LONG_PRESS -> "Tipp + Halten"
            GestureKind.PRESS -> "Berührung"
            GestureKind.HEAD_UP -> "Kopf hoch"
            GestureKind.SWIPE_LEFT -> "Wisch links"
            GestureKind.SWIPE_RIGHT -> "Wisch rechts"
        }

        fun sourceLabel(s: InputSource): String = when (s) {
            InputSource.WATCH -> "Uhr"
            InputSource.LEFT -> "linker Bügel"
            InputSource.RIGHT -> "rechter Bügel"
            InputSource.RING -> "Ring"
            InputSource.UNKNOWN -> "Brille"
        }
    }
}

/**
 * Turns what Faceclaw's session reports in `onRingEvent` into gestures (03 §5.1, after
 * wissen/03 §2.11.2), and drops the ring's duplicate reports the way Faceclaw's input monitor does
 * (wissen/03 §2.11.3): the custom firmware forwards ring reports before the stock 100-tick
 * suppression. Not thread-safe; the connection calls it on the main thread.
 */
class InputRouter {
    /** Ring clock of the last accepted report; 0 = none yet. */
    private var lastTick = 0L

    /** After tap-then-hold the release that follows belongs to it and is dropped (as in Faceclaw). */
    private var swallowRelease = false

    /**
     * The gesture for one event, or null for events that are no gesture, unknown, or a duplicate.
     * [ringTick] is the ring's 32-bit clock and [ringType] its raw report type; −1/any when the
     * event carries no ring metadata.
     */
    fun translate(kind: String?, eventType: Int, eventSource: Int, ringTick: Long = -1, ringType: Int = 0): GlassesInput? {
        if (ringTick >= 0 && !acceptRing(ringTick, ringType)) return null
        val input = when (kind) {
            "sys-event" -> sysEvent(eventType, eventSource)
            // Swipes on a temple arrive as text events without a side.
            "text-click" -> when (eventType) {
                SCROLL_TOP -> GlassesInput(GestureKind.SCROLL_UP, InputSource.UNKNOWN)
                SCROLL_BOTTOM -> GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)
                else -> null
            }
            "display-wake" -> if (eventType == HEAD_UP) GlassesInput(GestureKind.HEAD_UP, InputSource.UNKNOWN) else null
            else -> null
        } ?: return null
        if (input.gesture == GestureKind.LONG_PRESS_RELEASE && swallowRelease) {
            swallowRelease = false
            return null
        }
        swallowRelease = input.gesture == GestureKind.SHORT_THEN_LONG_PRESS
        return input
    }

    private fun sysEvent(type: Int, source: Int): GlassesInput? {
        val side = when (source) {
            SOURCE_RIGHT -> InputSource.RIGHT
            SOURCE_RING -> InputSource.RING
            SOURCE_LEFT -> InputSource.LEFT
            else -> InputSource.UNKNOWN
        }
        val gesture = when (type) {
            CLICK -> GestureKind.CLICK
            DOUBLE_CLICK -> GestureKind.DOUBLE_CLICK
            SCROLL_TOP -> GestureKind.SCROLL_UP
            SCROLL_BOTTOM -> GestureKind.SCROLL_DOWN
            LONG_PRESS -> GestureKind.LONG_PRESS
            LONG_PRESS_RELEASE -> GestureKind.LONG_PRESS_RELEASE
            SHORT_THEN_LONG_PRESS -> GestureKind.SHORT_THEN_LONG_PRESS
            // A touch-down only comes from the ring; Faceclaw drops it from anywhere else.
            PRESS -> return if (source == 0 || source == SOURCE_RING) GlassesInput(GestureKind.PRESS, InputSource.RING) else null
            else -> return null
        }
        return GlassesInput(gesture, side)
    }

    /** wissen/03 §2.11.3: reports within 100 ticks of the last accepted one are duplicates. */
    private fun acceptRing(tick: Long, ringType: Int): Boolean {
        val passes = ringType == RING_RELEASE || ringType == RING_TOUCH_DOWN
        if (!passes && lastTick != 0L && ((tick - lastTick) and 0xFFFF_FFFFL) < DUPLICATE_TICKS) return false
        if (ringType != RING_TOUCH_DOWN) lastTick = tick
        return true
    }

    /** Forgets the ring clock, e.g. after a new connection. */
    fun reset() {
        lastTick = 0L
        swallowRelease = false
    }

    companion object {
        // Event types and sources as in Faceclaw's BleProtocol (wissen/03 §2.3).
        const val CLICK = 0
        const val SCROLL_TOP = 1
        const val SCROLL_BOTTOM = 2
        const val DOUBLE_CLICK = 3
        const val LONG_PRESS = 9
        const val LONG_PRESS_RELEASE = 10
        const val SHORT_THEN_LONG_PRESS = 11
        const val HEAD_UP = 12
        const val PRESS = 14
        const val SOURCE_RIGHT = 1
        const val SOURCE_RING = 2
        const val SOURCE_LEFT = 3

        /** Raw ring report types that never count as duplicates (release, touch-down). */
        const val RING_RELEASE = 8
        const val RING_TOUCH_DOWN = 10
        const val DUPLICATE_TICKS = 100L
    }
}

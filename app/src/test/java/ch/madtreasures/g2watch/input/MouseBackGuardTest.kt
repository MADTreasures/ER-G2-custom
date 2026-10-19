package ch.madtreasures.g2watch.input

import android.view.MotionEvent
import ch.madtreasures.g2watch.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MouseBackGuard]: the back callback that swallows Android's own back of the mouse's back button is on
 * only around a press of that button, so the watch's own back gesture works as before (03 §5.3).
 */
class MouseBackGuardTest {
    private val scheduler = FakeScheduler()
    private val armed = mutableListOf<Boolean>()
    private val guard = MouseBackGuard(scheduler) { armed += it }

    private val isArmed get() = armed.lastOrNull() ?: false

    @Test
    fun `other buttons and moves arm nothing`() {
        guard.buttons(0)
        guard.buttons(MotionEvent.BUTTON_PRIMARY)
        guard.buttons(MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_FORWARD)
        guard.buttons(0)
        scheduler.advanceBy(MouseBackGuard.HOLD_LIMIT_MS)
        assertEquals(emptyList<Boolean>(), armed)
    }

    @Test
    fun `armed while the back button is down, until Android's back came`() {
        guard.buttons(MotionEvent.BUTTON_BACK)
        assertEquals(listOf(true), armed)
        // Other buttons and moves while it is held change nothing.
        guard.buttons(MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_PRIMARY)
        guard.buttons(0)
        guard.used()
        assertEquals(listOf(true, false), armed)
        // The timer of the release finds nothing left to do.
        scheduler.advanceBy(MouseBackGuard.HOLD_LIMIT_MS)
        assertEquals(listOf(true, false), armed)
    }

    @Test
    fun `without Android's back (it went to dispatchKeyEvent) the guard ends shortly after the release`() {
        guard.buttons(MotionEvent.BUTTON_BACK)
        scheduler.advanceBy(3_000)
        assertEquals(true, isArmed)
        guard.buttons(0)
        scheduler.advanceBy(MouseBackGuard.RELEASE_MS - 1)
        assertEquals(true, isArmed)
        scheduler.advanceBy(1)
        assertEquals(false, isArmed)
    }

    @Test
    fun `a second press right after the release stays armed`() {
        guard.buttons(MotionEvent.BUTTON_BACK)
        guard.buttons(0)
        scheduler.advanceBy(MouseBackGuard.RELEASE_MS / 2)
        guard.buttons(MotionEvent.BUTTON_BACK)
        scheduler.advanceBy(MouseBackGuard.RELEASE_MS)
        assertEquals(true, isArmed)
        assertEquals(listOf(true), armed)
    }

    @Test
    fun `a release that never came ends the guard after a while`() {
        guard.buttons(MotionEvent.BUTTON_BACK)
        scheduler.advanceBy(MouseBackGuard.HOLD_LIMIT_MS)
        assertEquals(false, isArmed)
    }
}

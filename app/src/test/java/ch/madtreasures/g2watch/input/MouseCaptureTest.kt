package ch.madtreasures.g2watch.input

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MouseCapture] on a view that stands in for the touchpad's: the mouse is captured only while one is
 * there (and the window has the focus), asked for again when the focus comes back, and let go with
 * the mouse or the touchpad (03 §5.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MouseCaptureTest {
    /** Android's side of the capture: it holds while the window has the focus. */
    private class TouchpadView(context: Context) : View(context) {
        var windowFocus = true
        var captured = false
        var requests = 0
        var releases = 0

        override fun hasWindowFocus() = windowFocus

        override fun hasPointerCapture() = captured

        override fun requestPointerCapture() {
            requests++
            if (windowFocus) captured = true
        }

        override fun releasePointerCapture() {
            releases++
            captured = false
        }

        /** The window lost its focus (the watch's text input on top): Android ends the capture. */
        fun loseFocus() {
            windowFocus = false
            captured = false
        }
    }

    private val events = mutableListOf<MotionEvent>()
    private val capture = MouseCapture { events += it; true }
    private val view = TouchpadView(RuntimeEnvironment.getApplication())

    private fun relativeMove(): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 3f, -2f, 0).apply { source = InputDevice.SOURCE_MOUSE_RELATIVE }
    }

    @Test
    fun `without a mouse nothing is captured`() {
        capture.set(view, wanted = false)
        capture.windowFocusChanged(true)
        assertEquals(0, view.requests)
        assertFalse(view.captured)
    }

    @Test
    fun `with a mouse the touchpad captures it, and its events come here`() {
        capture.set(view, wanted = true)
        assertEquals(1, view.requests)
        assertTrue(view.captured)
        assertTrue(view.dispatchCapturedPointerEvent(relativeMove()))
        assertEquals(1, events.size)
        // Asked again while it holds: no second request.
        capture.set(view, wanted = true)
        assertEquals(1, view.requests)
    }

    @Test
    fun `the capture comes back with the window's focus`() {
        capture.set(view, wanted = true)
        view.loseFocus()
        capture.windowFocusChanged(false)
        assertFalse(view.captured)
        view.windowFocus = true
        capture.windowFocusChanged(true)
        assertEquals(2, view.requests)
        assertTrue(view.captured)
    }

    @Test
    fun `a window without the focus asks once it has it`() {
        view.windowFocus = false
        capture.set(view, wanted = true)
        assertFalse(view.captured)
        view.windowFocus = true
        capture.windowFocusChanged(true)
        assertTrue(view.captured)
    }

    @Test
    fun `the mouse gone, or the touchpad left, lets the pointer go`() {
        capture.set(view, wanted = true)
        capture.set(view, wanted = false)
        assertFalse(view.captured)
        assertEquals(1, view.releases)
        capture.set(view, wanted = true)
        capture.clear(view)
        assertFalse(view.captured)
        assertEquals(2, view.releases)
        // No listener left, and the focus coming back asks for nothing.
        capture.windowFocusChanged(true)
        assertFalse(view.captured)
        assertFalse(view.dispatchCapturedPointerEvent(relativeMove()))
        assertEquals(0, events.size)
    }
}

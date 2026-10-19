package ch.madtreasures.g2watch.input

import android.content.Context
import android.hardware.input.InputManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [MouseCapture] on a view that stands in for the touchpad's: the mouse is captured only while one is
 * there (and the window has the focus), asked for again when the focus comes back, and let go with
 * the mouse or the touchpad (03 §5.4).
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

    /** An external mouse as Android describes it, by its hidden InputDevice.Builder. */
    private fun mouseDevice(sources: Int, generation: Int): InputDevice {
        val builder = Class.forName("android.view.InputDevice\$Builder").getConstructor().newInstance()
        fun set(name: String, type: Class<*>, value: Any) = builder.javaClass.getMethod(name, type).invoke(builder, value)
        set("setId", Int::class.javaPrimitiveType!!, MOUSE_ID)
        set("setGeneration", Int::class.javaPrimitiveType!!, generation)
        set("setName", String::class.java, "Magic Mouse")
        set("setExternal", Boolean::class.javaPrimitiveType!!, true)
        set("setSources", Int::class.javaPrimitiveType!!, sources)
        return builder.javaClass.getMethod("build").invoke(builder) as InputDevice
    }

    @Test
    fun `a captured mouse stays a mouse, so the capture holds`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(InputManager::class.java))
        val devices = InputDevices(context)
        // As the touchpad does it: capture while the device list has a mouse.
        fun follow() = capture.set(view, wanted = devices.devices.value.any { it.mouse })

        manager.addInputDevice(mouseDevice(InputDevice.SOURCE_MOUSE, generation = 1))
        devices.onInputDeviceAdded(MOUSE_ID)
        follow()
        assertTrue(view.captured)
        // Captured, Android reports the mouse as a relative one and tells of a changed device.
        manager.removeInputDevice(MOUSE_ID)
        manager.addInputDevice(mouseDevice(InputDevice.SOURCE_MOUSE_RELATIVE, generation = 2))
        devices.onInputDeviceChanged(MOUSE_ID)
        follow()
        assertEquals(listOf(true), devices.devices.value.map { it.mouse })
        assertTrue(view.captured)
        assertEquals(1, view.requests)
        assertEquals(0, view.releases)
    }

    private companion object {
        const val MOUSE_ID = 7
    }
}

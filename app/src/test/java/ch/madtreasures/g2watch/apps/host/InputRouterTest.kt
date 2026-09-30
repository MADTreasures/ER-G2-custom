package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import com.faceclaw.app.BleProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every row of the gesture table in 03 §5.1, and Faceclaw's ring filter (wissen/03 §2.11.3). */
class InputRouterTest {
    private val routed = mutableListOf<Gesture>()
    private val router = InputRouter { routed += it }

    private fun sys(type: Int, source: Int = BleProtocol.EVENT_SOURCE_GLASSES_R) = router.onGlassesEvent("sys-event", type, source)

    @Test
    fun `system events become gestures with their side`() {
        assertEquals(Gesture(GestureKind.CLICK, InputSource.RIGHT), sys(0, 1))
        assertEquals(Gesture(GestureKind.CLICK, InputSource.RING), sys(0, 2))
        assertEquals(Gesture(GestureKind.CLICK, InputSource.LEFT), sys(0, 3))
        assertEquals(Gesture(GestureKind.DOUBLE_CLICK, InputSource.LEFT), sys(3, 3))
        assertEquals(Gesture(GestureKind.SCROLL_UP, InputSource.RIGHT), sys(1, 1))
        assertEquals(Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN), sys(2, 0))
        assertEquals(Gesture(GestureKind.LONG_PRESS, InputSource.RING), sys(9, 2))
        assertEquals(Gesture(GestureKind.LONG_PRESS_RELEASE, InputSource.RING), sys(10, 2))
        assertEquals(Gesture(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RIGHT), sys(11, 1))
        assertEquals(Gesture(GestureKind.PRESS, InputSource.RING), sys(14, 0))
        assertEquals(Gesture(GestureKind.PRESS, InputSource.RING), sys(14, 2))
        assertEquals(Gesture(GestureKind.PRESS, InputSource.UNKNOWN), sys(14, 1))
        assertEquals(12, routed.size)
    }

    @Test
    fun `temple swipes arrive as text scrolls without a side`() {
        assertEquals(Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN), router.onGlassesEvent("text-click", 1, 0))
        assertEquals(Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN), router.onGlassesEvent("text-click", 2, 0))
        assertNull(router.onGlassesEvent("text-click", 0, 0))
    }

    @Test
    fun `head-up counts, the wake double tap and page events do not`() {
        assertEquals(Gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN), router.onGlassesEvent("display-wake", 12, 0))
        assertNull(router.onGlassesEvent("display-wake", 3, 0))
        for (type in listOf(4, 5, 6, 7, 8)) assertNull(sys(type))
        assertNull(router.onGlassesEvent("list-click", 0, 0))
        assertNull(router.onGlassesEvent("even-ai", 1, 0))
        assertNull(router.onGlassesEvent(null, 0, 0))
        assertEquals(1, routed.size)
    }

    private fun ring(type: Int, tick: Long, ringType: Int = 1) = router.onGlassesEvent("sys-event", type, 2, tick, ringType)

    @Test
    fun `ring repeats within 100 ticks are dropped`() {
        assertEquals(GestureKind.CLICK, ring(0, 1_000)?.kind)
        assertNull(ring(0, 1_050))
        // A dropped report does not move the clock: 1 100 is 100 after the accepted one.
        assertEquals(GestureKind.CLICK, ring(0, 1_100)?.kind)
        assertNull(ring(0, 1_199))
        assertEquals(2, routed.size)
    }

    @Test
    fun `a ring release always passes, a press never filters nor moves the clock`() {
        ring(9, 5_000)
        assertEquals(GestureKind.LONG_PRESS_RELEASE, ring(10, 5_010, ringType = 8)?.kind)
        // The release moved the clock: 5 050 is within 100 of it.
        assertNull(ring(0, 5_050))
        assertEquals(GestureKind.PRESS, ring(14, 5_060, ringType = 10)?.kind)
        assertEquals(GestureKind.PRESS, ring(14, 5_061, ringType = 10)?.kind)
        // The presses did not move it either: 5 110 is 100 after the release.
        assertEquals(GestureKind.CLICK, ring(0, 5_110)?.kind)
    }

    @Test
    fun `the ring clock wraps at 32 bits`() {
        ring(0, 0xFFFF_FFF0L)
        assertNull(ring(0, 0x10L))
        assertEquals(GestureKind.CLICK, ring(0, 0x60L)?.kind)
    }

    @Test
    fun `an unknown ring report is dropped but moves the clock`() {
        assertNull(ring(127, 7_000))
        assertNull(ring(0, 7_050))
        assertEquals(GestureKind.CLICK, ring(0, 7_100)?.kind)
    }

    @Test
    fun `reports without ring metadata are never filtered`() {
        repeat(3) { assertEquals(GestureKind.CLICK, router.onGlassesEvent("sys-event", 0, 2)?.kind) }
    }

    @Test
    fun `watch gestures pass with their source`() {
        router.onWatchGesture(GestureKind.SWIPE_RIGHT)
        assertEquals(listOf(Gesture(GestureKind.SWIPE_RIGHT, InputSource.WATCH)), routed)
    }
}

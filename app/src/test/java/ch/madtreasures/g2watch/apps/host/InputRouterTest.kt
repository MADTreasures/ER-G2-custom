package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every row of the table in 03 §5.1, and the ring de-duplication of wissen/03 §2.11.3. */
class InputRouterTest {
    private val router = InputRouter()

    private fun sys(type: Int, source: Int) = router.translate("sys-event", type, source)

    @Test
    fun `sys events map to gestures with their side`() {
        assertEquals(GlassesInput(GestureKind.CLICK, InputSource.RIGHT), sys(0, 1))
        assertEquals(GlassesInput(GestureKind.CLICK, InputSource.RING), sys(0, 2))
        assertEquals(GlassesInput(GestureKind.CLICK, InputSource.LEFT), sys(0, 3))
        assertEquals(GlassesInput(GestureKind.DOUBLE_CLICK, InputSource.LEFT), sys(3, 3))
        assertEquals(GlassesInput(GestureKind.SCROLL_UP, InputSource.RIGHT), sys(1, 1))
        assertEquals(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN), sys(2, 0))
        assertEquals(GlassesInput(GestureKind.LONG_PRESS, InputSource.RING), sys(9, 2))
        assertEquals(GlassesInput(GestureKind.LONG_PRESS_RELEASE, InputSource.RING), sys(10, 2))
        assertEquals(GlassesInput(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RIGHT), sys(11, 1))
    }

    @Test
    fun `a touch-down counts only from the ring`() {
        assertEquals(GlassesInput(GestureKind.PRESS, InputSource.RING), sys(14, 2))
        assertEquals(GlassesInput(GestureKind.PRESS, InputSource.RING), sys(14, 0))
        assertNull(sys(14, 1))
        assertNull(sys(14, 3))
    }

    @Test
    fun `temple swipes come as text events without a side`() {
        assertEquals(GlassesInput(GestureKind.SCROLL_UP, InputSource.UNKNOWN), router.translate("text-click", 1, 0))
        assertEquals(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN), router.translate("text-click", 2, 0))
        assertNull(router.translate("text-click", 0, 0))
    }

    @Test
    fun `head up is a display wake with code 12`() {
        assertEquals(GlassesInput(GestureKind.HEAD_UP, InputSource.UNKNOWN), router.translate("display-wake", 12, 0))
        assertNull(router.translate("display-wake", 3, 0))
    }

    @Test
    fun `other kinds and codes are no gestures`() {
        assertNull(router.translate("list-click", 0, 0))
        assertNull(router.translate("even-ai", 1, 0))
        assertNull(router.translate(null, 0, 0))
        // Foreground enter/exit, system exit, IMU and the unknown ring type 127.
        for (code in listOf(4, 5, 6, 7, 8, 13, 127)) assertNull("code $code", sys(code, 1))
    }

    @Test
    fun `ring reports within 100 ticks of the last one are duplicates`() {
        fun ring(type: Int, ringType: Int, tick: Long) = router.translate("sys-event", type, 2, tick, ringType)
        assertEquals(GestureKind.CLICK, ring(0, 1, 1_000)?.gesture)
        // The same tap again via the other arm: dropped, and the clock does not move.
        assertNull(ring(0, 1, 1_050))
        assertNull(ring(0, 1, 1_099))
        assertEquals(GestureKind.CLICK, ring(0, 1, 1_100)?.gesture)
        // Release (8) and touch-down (10) always pass; touch-down does not move the clock.
        assertEquals(GestureKind.PRESS, ring(14, 10, 1_110)?.gesture)
        assertEquals(GestureKind.LONG_PRESS_RELEASE, ring(10, 8, 1_120)?.gesture)
        assertNull(ring(3, 2, 1_150))
        // The 32-bit ring clock wraps around.
        router.reset()
        assertEquals(GestureKind.SCROLL_UP, ring(1, 4, 0xFFFF_FFF0L)?.gesture)
        assertNull(ring(1, 4, 0x10L))
        assertEquals(GestureKind.SCROLL_UP, ring(1, 4, 0x60L)?.gesture)
    }

    @Test
    fun `an unknown ring report still moves the clock`() {
        assertNull(router.translate("sys-event", 127, 2, 5_000, 99))
        assertNull(router.translate("sys-event", 0, 2, 5_050, 1))
    }

    @Test
    fun `the release after tap-then-hold belongs to it`() {
        assertEquals(GestureKind.SHORT_THEN_LONG_PRESS, sys(11, 1)?.gesture)
        assertNull(sys(10, 1))
        assertEquals(GestureKind.LONG_PRESS_RELEASE, sys(10, 1)?.gesture)
    }

    @Test
    fun `labels are German for the status line`() {
        assertEquals("Wisch vor (Brille)", router.translate("text-click", 1, 0)!!.label)
        assertEquals("Tipp (Ring)", sys(0, 2)!!.label)
    }
}

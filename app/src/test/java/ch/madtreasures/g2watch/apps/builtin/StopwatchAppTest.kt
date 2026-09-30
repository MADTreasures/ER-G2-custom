package ch.madtreasures.g2watch.apps.builtin

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.builtin.stopwatch.StopwatchApp
import ch.madtreasures.g2watch.apps.textOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchAppTest {
    private var now = 0L
    private val app = StopwatchApp { now }
    private val ui = FakeAppContext()

    private fun click(block: String) = app.onEvent(AppEvent.Click(StopwatchApp.PAGE, block), ui)

    private fun page() = ui.page(StopwatchApp.PAGE)

    @Test
    fun `starts on its page at zero`() {
        app.onEvent(AppEvent.Start, ui)
        assertEquals(StopwatchApp.PAGE, ui.currentPage?.id)
        assertEquals("0:00,0", page().textOf(StopwatchApp.TIME))
        assertEquals("Start", page().textOf(StopwatchApp.START_STOP))
    }

    @Test
    fun `runs in whole seconds with a timer, stops with tenths`() {
        app.onEvent(AppEvent.Start, ui)
        click(StopwatchApp.START_STOP)
        assertEquals("Stopp", page().textOf(StopwatchApp.START_STOP))
        assertTrue(StopwatchApp.TICK in ui.timers)
        assertTrue(ui.timers.getValue(StopwatchApp.TICK).repeat)

        now = 65_400
        app.onEvent(AppEvent.Timer(StopwatchApp.TICK), ui)
        assertEquals("1:05", page().textOf(StopwatchApp.TIME))

        click(StopwatchApp.START_STOP)
        assertFalse(StopwatchApp.TICK in ui.timers)
        assertEquals("1:05,4", page().textOf(StopwatchApp.TIME))
        assertEquals("Weiter", page().textOf(StopwatchApp.START_STOP))

        click(StopwatchApp.RESET)
        assertEquals("0:00,0", page().textOf(StopwatchApp.TIME))
        assertEquals("Start", page().textOf(StopwatchApp.START_STOP))
    }

    @Test
    fun `keeps counting while hidden, without ticking`() {
        app.onEvent(AppEvent.Start, ui)
        click(StopwatchApp.START_STOP)
        app.onEvent(AppEvent.Hidden, ui)
        assertFalse(StopwatchApp.TICK in ui.timers)
        now = 3_600_000 + 2_000
        app.onEvent(AppEvent.Visible, ui)
        assertTrue(StopwatchApp.TICK in ui.timers)
        assertEquals("1:00:02", page().textOf(StopwatchApp.TIME))
    }
}

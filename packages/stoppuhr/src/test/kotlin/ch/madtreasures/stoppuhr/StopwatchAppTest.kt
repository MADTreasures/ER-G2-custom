package ch.madtreasures.stoppuhr

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.textOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchAppTest {
    private var now = 1_000_000L
    private val app = StopwatchApp { now }
    private val ui = FakeAppContext.forApp(app)

    private fun click(block: String) = app.onEvent(AppEvent.Click(StopwatchApp.PAGE, block), ui)

    private val page get() = ui.page(StopwatchApp.PAGE)

    @Test
    fun `start, stop and reset`() {
        app.onEvent(AppEvent.Start, ui)
        assertEquals(StopwatchApp.PAGE, ui.current?.id)
        assertEquals("0:00,0", page.textOf("zeit"))

        click("startstop")
        assertEquals("Stopp", page.textOf("startstop"))
        assertEquals(Pair(500L, true), ui.timers["tick"])

        now += 65_300
        app.onEvent(AppEvent.Timer("tick"), ui)
        assertEquals("1:05,3", page.textOf("zeit"))

        click("startstop")
        assertEquals("Start", page.textOf("startstop"))
        assertTrue(ui.timers.isEmpty())
        now += 10_000
        click("startstop")
        now += 1_000
        app.onEvent(AppEvent.Timer("tick"), ui)
        assertEquals("1:06,3", page.textOf("zeit"))

        click("reset")
        assertEquals("0:00,0", page.textOf("zeit"))
        assertEquals("Start", page.textOf("startstop"))
        assertTrue(ui.timers.isEmpty())
    }

    @Test
    fun `hidden it keeps time but does not tick`() {
        app.onEvent(AppEvent.Start, ui)
        click("startstop")
        app.onEvent(AppEvent.Hidden, ui)
        assertTrue(ui.timers.isEmpty())
        now += 3_000
        app.onEvent(AppEvent.Visible, ui)
        assertEquals("0:03,0", page.textOf("zeit"))
        assertEquals(Pair(500L, true), ui.timers["tick"])
    }

    @Test
    fun `the time reads minutes, seconds and tenths`() {
        assertEquals("0:00,0", StopwatchApp.format(0))
        assertEquals("0:09,9", StopwatchApp.format(9_999))
        assertEquals("61:01,5", StopwatchApp.format(3_661_500))
    }
}

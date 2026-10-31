package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.textOf
import ch.madtreasures.g2watch.input.KeyPress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app in the real app host of the watch app: the temples work its pages, the questions go to the watch or,
 * with a keyboard, to the glasses, its requests and background work run, the GPS line opens and closes, and the
 * host refuses none of its commands.
 */
class FahrplanHostTest {
    private val scheduler = FakeScheduler()
    private val ports = FakePorts()
    private val host = AppHost(
        scheduler, FakeScreen(), FakeText(), ports,
        listOf({ FahrplanApp(clock = { Fixtures.MONDAY + scheduler.now }, zone = Fixtures.ZONE) }),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    /** Runs the app thread and the background work until both are idle. */
    private fun settle() {
        repeat(20) {
            scheduler.runPending()
            scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
            if (ports.work.isEmpty()) return
            ports.runWork()
        }
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun page() = host.currentPageOf(FahrplanApp.ID)!!

    /** Moves the focus along the page with the temple until [block] has it, then clicks it. */
    private fun press(block: String) {
        for (direction in listOf(GestureKind.SCROLL_DOWN, GestureKind.SCROLL_UP)) {
            repeat(80) {
                if (host.state.value.focus == FocusTarget(block)) {
                    temple(GestureKind.CLICK)
                    return
                }
                temple(direction)
            }
        }
        throw AssertionError("$block never got the focus on ${host.state.value.page}")
    }

    private fun buttonFor(text: String): String = page().blocks.filterIsInstance<Block.Button>().first { it.text.startsWith(text) }.id

    /** The timetable answers every open request with the recorded answer for it. */
    private fun answerRequests() {
        while (ports.requests.isNotEmpty()) {
            val (request, done) = ports.requests.removeAt(0)
            done(HttpResult(200, Fixtures.text(fixtureFor(request.url))))
            settle()
        }
    }

    private fun launch() {
        FahrplanApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        host.launch("watch:${FahrplanApp.ID}")
        settle()
        assertEquals(FahrplanApp.ID, host.state.value.visible)
    }

    /** The wearer answers the question on the watch. */
    private fun type(text: String) {
        ports.questions.last().third(text)
        settle()
    }

    @After
    fun nothingRefused() {
        assertFalse(ports.logs.joinToString("\n"), ports.logs.any { "abgelehnt" in it || "ließ sich nicht lesen" in it })
    }

    @Test
    fun `typed on the watch, a connection with its legs and stops, asked for again every minute`() {
        launch()
        press(FahrplanApp.ST_FROM)
        assertEquals(listOf("Mein Standort"), ports.questions.last().second)
        type("Zürich")
        answerRequests()
        assertEquals(FahrplanApp.P_PICK, host.state.value.page)
        press(buttonFor("Zürich HB"))
        assertEquals(FahrplanApp.P_START, host.state.value.page)
        press(FahrplanApp.ST_TO)
        type("bern")
        answerRequests()
        assertEquals("Nach: Bern", page().textOf(FahrplanApp.ST_TO))
        press(FahrplanApp.ST_SEARCH)
        answerRequests()
        assertEquals(FahrplanApp.P_RESULTS, host.state.value.page)
        // The focus is at the top of the list that came, not on a button that moved down.
        assertEquals(FocusTarget(FahrplanApp.VE_EARLIER), host.state.value.focus)
        assertEquals("08:08 → 09:24 · Gl. 16 · IR 37, IR 16", page().textOf("${FahrplanApp.VE_CONN}3"))
        press("${FahrplanApp.VE_CONN}3")
        assertEquals(FahrplanApp.P_DETAIL, host.state.value.page)
        press("${FahrplanApp.DE_LEG}1")
        assertEquals(FahrplanApp.P_STOPS, host.state.value.page)
        assertEquals("IR 16 nach Bern", page().textOf(FahrplanApp.HA_TITLE))

        // A minute later the connection is asked for again; back on the start page nothing is.
        scheduler.advanceBy(FahrplanApp.REFRESH_MS)
        settle()
        assertEquals(1, ports.requests.size)
        answerRequests()
        repeat(3) { temple(GestureKind.DOUBLE_CLICK) }
        assertEquals(FahrplanApp.P_START, host.state.value.page)
        scheduler.advanceBy(FahrplanApp.REFRESH_MS)
        settle()
        assertTrue(ports.requests.isEmpty())
    }

    @Test
    fun `with a keyboard the question shows on the glasses and the typed name is looked up`() {
        ports.hardwareKeyboard = true
        launch()
        press(FahrplanApp.ST_TO)
        for (c in "bern") host.key(KeyPress(c.toString(), c.toString()))
        host.key(KeyPress(Keys.ENTER))
        settle()
        assertTrue(ports.questions.isEmpty())
        answerRequests()
        assertEquals("Nach: Bern", page().textOf(FahrplanApp.ST_TO))
    }

    @Test
    fun `Mein Standort and the stops nearby come from the watch's GPS`() {
        launch()
        // "Mein Standort" on the start page: the GPS starts ahead of the search.
        val line = ports.location.lines.single()
        assertFalse(line.stopped)
        line.fix(Fixtures.LAT, Fixtures.LON)
        settle()
        assertTrue(line.stopped)

        press(FahrplanApp.ST_NEAR)
        assertEquals(FahrplanApp.P_NEAR, host.state.value.page)
        // The position is recent: the stops come at once.
        answerRequests()
        assertEquals("Zürich, Paradeplatz · 160 m", page().textOf("${FahrplanApp.NA_PLACE}0"))
        press("${FahrplanApp.NA_PLACE}0")
        assertEquals(FahrplanApp.P_BOARD, host.state.value.page)
        answerRequests()
        assertEquals("07:45 S 16 Herrliberg-Feldmeilen", page().textOf("${FahrplanApp.AB_DEP}0"))
    }

    companion object {
        /** The recorded answer ([Fixtures]) for a request of the app. */
        fun fixtureFor(url: String): String = when {
            "/locations?x=" in url -> Fixtures.NEARBY
            "query=Z%C3%BCrich" in url -> Fixtures.ZUERICH
            "query=bern" in url || "query=Bern" in url -> Fixtures.BERN
            "/connections?" in url && "from=8588078" in url -> Fixtures.CENTRAL_ZERMATT
            "/connections?" in url && "from=47." in url -> Fixtures.HERE_LUZERN
            "/connections?" in url && "time=08:33" in url -> Fixtures.ZUERICH_BERN_LATER
            "/connections?" in url -> Fixtures.ZUERICH_BERN
            "/stationboard?" in url -> Fixtures.BOARD
            else -> throw AssertionError("no recorded answer for $url")
        }
    }
}

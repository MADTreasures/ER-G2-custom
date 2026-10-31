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
import ch.madtreasures.g2watch.input.KeyPress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app in the real app host of the watch app: the temples swipe, tap and hold on its drawn pages and double-tap
 * back, the keys of a keyboard type a stop on the glasses or work the pages like the temples, the questions go to
 * the watch, its requests and its drawing in the background run, the GPS line opens and closes, and the host refuses
 * none of its commands (its pictures fit an image block).
 */
class FahrplanHostTest {
    private val scheduler = FakeScheduler()
    private val ports = FakePorts()

    /** The app the host runs (the host also makes one to read its manifest; the last one made is the running one). */
    private lateinit var app: FahrplanApp
    private val host = AppHost(
        scheduler, FakeScreen(), FakeText(), ports,
        listOf({ FahrplanApp(clock = { Fixtures.MONDAY + scheduler.now }, zone = Fixtures.ZONE).also { app = it } }),
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

    private fun temple(kind: GestureKind, times: Int = 1) = repeat(times) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun key(key: String, text: String = "") {
        host.key(KeyPress(key, text))
        settle()
    }

    private fun page(): String? = host.state.value.page

    /** The picture the page on the glasses shows. */
    private fun picture(): String? {
        val shown = host.currentPageOf(FahrplanApp.ID)!!
        return (shown.block(FahrplanApp.image(shown.id)) as Block.Image).src
    }

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
        ports.questions.removeAt(ports.questions.lastIndex).third(text)
        settle()
    }

    @After
    fun nothingRefused() {
        assertFalse(ports.logs.joinToString("\n"), ports.logs.any { "abgelehnt" in it || "ließ sich nicht lesen" in it || "Bild" in it })
    }

    @Test
    fun `with the temples from and to typed on the watch, the connections, Ihre Reise, every minute anew`() {
        launch()
        assertEquals(FahrplanApp.P_PLAN, page())
        assertTrue(picture()!!.startsWith("data:image/png;base64,"))
        temple(GestureKind.SCROLL_UP)
        temple(GestureKind.CLICK)
        assertEquals("Von wo?", ports.questions.last().first)
        assertEquals(listOf("Aktueller Standort"), ports.questions.last().second)
        type("Zürich")
        answerRequests()
        assertEquals(FahrplanApp.P_SEARCH, page())
        temple(GestureKind.CLICK)
        assertEquals(FahrplanApp.P_PLAN, page())
        temple(GestureKind.SCROLL_DOWN)
        temple(GestureKind.CLICK)
        type("bern")
        answerRequests()
        assertEquals(FahrplanApp.P_RESULTS, page())
        val cards = (app.screen(FahrplanApp.P_RESULTS) as ConnectionsScreen).cards
        assertEquals(listOf("07:49", "08:02", "08:06", "08:08", "08:32"), cards.map { it.departure })

        // Swiping past the last one brings later ones.
        temple(GestureKind.SCROLL_DOWN, 5)
        answerRequests()
        assertEquals(10, (app.screen(FahrplanApp.P_RESULTS) as ConnectionsScreen).cards.size)
        temple(GestureKind.SCROLL_UP, 2)
        temple(GestureKind.CLICK)
        assertEquals(FahrplanApp.P_JOURNEY, page())
        assertEquals("08:08", (app.screen(FahrplanApp.P_JOURNEY) as JourneyScreen).departure)

        // A minute later the journey is asked for again; back on Planen nothing is.
        scheduler.advanceBy(FahrplanApp.REFRESH_MS)
        settle()
        assertEquals(1, ports.requests.size)
        answerRequests()
        temple(GestureKind.DOUBLE_CLICK, 2)
        assertEquals(FahrplanApp.P_PLAN, page())
        scheduler.advanceBy(FahrplanApp.REFRESH_MS)
        settle()
        assertTrue(ports.requests.isEmpty())
    }

    @Test
    fun `with a keyboard the stop is typed on the glasses, and the arrows and Enter work the pages`() {
        ports.hardwareKeyboard = true
        launch()
        ports.location.lines.last().fix(Fixtures.LAT, Fixtures.LON)
        settle()
        // Enter taps: "Nach" has the frame.
        key(Keys.ENTER)
        assertEquals(FahrplanApp.P_SEARCH, page())
        key("b", "b")
        key("e", "e")
        scheduler.advanceBy(FahrplanApp.TYPE_MS)
        settle()
        assertEquals(1, ports.requests.size)
        assertTrue(ports.requests.single().first.url.contains("query=be&"))
        answerRequests()
        assertEquals("Bern", (app.screen(FahrplanApp.P_SEARCH) as ListScreen).rows.first().text)
        key(Keys.ENTER)
        // Nothing went to the watch.
        assertTrue(ports.questions.isEmpty())
        answerRequests()
        assertEquals(FahrplanApp.P_RESULTS, page())
        assertEquals("Aktueller Standort → Bern", (app.screen(FahrplanApp.P_RESULTS) as ConnectionsScreen).title)

        // Off the stop page the keys are the temples again: down swipes, Backspace goes back.
        key(Keys.ARROW_DOWN)
        assertEquals(1, (app.screen(FahrplanApp.P_RESULTS) as ConnectionsScreen).selected)
        key(Keys.BACKSPACE)
        assertEquals(FahrplanApp.P_PLAN, page())
    }

    @Test
    fun `Aktueller Standort and the stops nearby come from the watch's GPS`() {
        launch()
        // "Aktueller Standort" on Planen: the GPS starts ahead of the search.
        val line = ports.location.lines.single()
        assertFalse(line.stopped)
        line.fix(Fixtures.LAT, Fixtures.LON)
        settle()
        assertTrue(line.stopped)

        temple(GestureKind.SCROLL_DOWN, FahrplanApp.PLAN_FIXED - 1)
        temple(GestureKind.CLICK)
        assertEquals(FahrplanApp.P_NEAR, page())
        // The position is recent: the stops come at once.
        answerRequests()
        assertEquals("Zürich, Paradeplatz", (app.screen(FahrplanApp.P_NEAR) as ListScreen).rows.first().text)
        temple(GestureKind.CLICK)
        assertEquals(FahrplanApp.P_BOARD, page())
        answerRequests()
        val board = app.screen(FahrplanApp.P_BOARD) as ListScreen
        assertEquals("S 16", board.rows.first().badge)

        // Holding on the departures: a connection from that stop, on Planen.
        temple(GestureKind.LONG_PRESS)
        assertEquals(FahrplanApp.P_PLAN, page())
        assertEquals("Zürich, Paradeplatz", (app.screen(FahrplanApp.P_PLAN) as PlanScreen).from)
    }

    companion object {
        /** The recorded answer ([Fixtures]) for a request of the app. */
        fun fixtureFor(url: String): String = when {
            "/locations?x=" in url -> Fixtures.NEARBY
            "query=Z%C3%BCr" in url -> Fixtures.ZUERICH
            "query=bern" in url || "query=Bern" in url || "query=be&" in url -> Fixtures.BERN
            "/connections?" in url && "from=8588078" in url -> Fixtures.CENTRAL_ZERMATT
            "/connections?" in url && "from=47." in url -> Fixtures.HERE_LUZERN
            "/connections?" in url && "time=08:33" in url -> Fixtures.ZUERICH_BERN_LATER
            "/connections?" in url -> Fixtures.ZUERICH_BERN
            "/stationboard?" in url -> Fixtures.BOARD
            else -> throw AssertionError("no recorded answer for $url")
        }
    }
}

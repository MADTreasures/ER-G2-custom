package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.Sensor
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app against [FakeAppContext], with the recorded answers of the timetable ([Fixtures]): what each drawn page
 * shows ([FahrplanApp.screen]), how swiping, tapping and holding work them as in the SBB app, typing stops on the
 * watch and on the glasses, earlier and later connections, Ihre Reise with its stops, departures and trips, the
 * position, the minute's refresh, the store, the app menu, and what happens when the timetable or the GPS does not
 * answer.
 */
class FahrplanAppTest {
    private var now = Fixtures.MONDAY
    private val app = FahrplanApp(clock = { now }, zone = Fixtures.ZONE)
    private val ui = FakeAppContext.forApp(app)

    private fun send(e: AppEvent) = app.onEvent(e, ui)

    private fun start() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    private fun page(): String = ui.current!!.id

    private fun plan() = app.screen(FahrplanApp.P_PLAN) as PlanScreen

    private fun results() = app.screen(FahrplanApp.P_RESULTS) as ConnectionsScreen

    private fun journey() = app.screen(FahrplanApp.P_JOURNEY) as JourneyScreen

    private fun list(page: String) = app.screen(page) as ListScreen

    private fun gesture(kind: GestureKind, times: Int = 1) = repeat(times) { send(AppEvent.Gesture(kind, InputSource.RIGHT)) }

    private fun down(times: Int = 1) = gesture(GestureKind.SCROLL_DOWN, times)

    private fun up(times: Int = 1) = gesture(GestureKind.SCROLL_UP, times)

    private fun tap() = gesture(GestureKind.CLICK)

    private fun hold() = gesture(GestureKind.LONG_PRESS)

    private fun back() {
        send(AppEvent.Back(page()))
        ui.pages.back()
    }

    /** The wearer answers the open question on the watch. */
    private fun type(text: String?) {
        val q = ui.question ?: throw AssertionError("no question open")
        ui.questionAnswered()
        send(AppEvent.TextInput(q.first, text))
    }

    /** A key of a Bluetooth keyboard. */
    private fun key(key: String, text: String = "") = send(AppEvent.Key(key, text))

    /** The timetable answers the oldest request with a recorded answer. */
    private fun answer(fixture: String) = ui.answer(HttpResult(200, Fixtures.text(fixture)))

    private fun url(): String = ui.requests.first().first.url

    private fun fix() = send(AppEvent.Location(Fixtures.LAT, Fixtures.LON, 8f, now))

    /** The picture of [page] as it was sent last. */
    private fun picture(page: String): String? = (ui.page(page).block(FahrplanApp.image(page)) as Block.Image).src

    /** From Zürich HB (picked among the stops called "Zürich") to Bern (the first hit), as typed on the watch. */
    private fun chooseZurichBern() {
        up(3)
        assertEquals(0, plan().focus)
        tap()
        assertEquals(FahrplanApp.ASK_FROM, ui.question!!.first)
        type("Zürich")
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_SEARCH, page())
        tap()
        assertEquals(FahrplanApp.P_PLAN, page())
        down()
        tap()
        type("bern")
        answer(Fixtures.BERN)
    }

    @Test
    fun `Planen shows from and to with the line between them, when, and the departures`() {
        start()
        assertEquals(FahrplanApp.P_PLAN, page())
        val p = plan()
        assertEquals("Aktueller Standort", p.from)
        assertTrue(p.fromHere)
        assertNull(p.to)
        assertEquals("Jetzt · Abfahrt", p.time)
        assertEquals(listOf("Abfahrten in der Nähe", "Abfahrten einer Haltestelle"), p.rows.map { it.text })
        // Without a destination the frame starts on "Nach".
        assertEquals(1, p.focus)
        assertTrue(picture(FahrplanApp.P_PLAN)!!.startsWith("data:image/png;base64,"))
        // With "Aktueller Standort" the GPS starts at once, so that the position is there for the search.
        assertEquals(setOf(Sensor.LOCATION), ui.subscriptions)

        down(10)
        assertEquals(FahrplanApp.PLAN_FIXED + 1, plan().focus)
        up(10)
        assertEquals(0, plan().focus)
    }

    @Test
    fun `a name typed on the watch becomes a stop, and with the destination the connections show`() {
        start()
        up()
        tap()
        type("Zürich")
        assertEquals("https://transport.opendata.ch/v1/locations?query=Z%C3%BCrich&type=station", url())
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_SEARCH, page())
        val s = list(FahrplanApp.P_SEARCH)
        assertEquals("Von", s.fieldLabel)
        assertEquals("Zürich", s.field)
        assertEquals(listOf("Zürich HB", "Zürich, Hegibachplatz"), s.rows.take(2).map { it.text })
        assertEquals(listOf(Icon.TRAIN, Icon.TRAIN), s.rows.take(2).map { it.icon })
        assertEquals("Anders eingeben", s.rows.last().text)
        tap()
        assertEquals(FahrplanApp.P_PLAN, page())
        assertEquals("Zürich HB", plan().from)
        // A stop of its own: no GPS any more.
        assertTrue(ui.subscriptions.isEmpty())

        down()
        tap()
        // The stop chosen for "Von" is no suggestion for "Nach".
        assertEquals(Triple(FahrplanApp.ASK_TO, "Wohin?", emptyList<String>()), ui.question)
        type("bern")
        answer(Fixtures.BERN)
        // "bern" is the first hit's name: no choice needed, and the connections show at once.
        assertEquals(FahrplanApp.P_RESULTS, page())
        assertTrue(url(), url().startsWith("https://transport.opendata.ch/v1/connections?from=8503000&to=8507000&date=2026-11-02&time=07:40&limit=5"))
    }

    @Test
    fun `the connections as cards, with the journey bar, the lines, the platform and the time`() {
        start()
        chooseZurichBern()
        assertEquals("Suche Verbindungen …", results().note)
        answer(Fixtures.ZUERICH_BERN)
        val r = results()
        assertEquals("Zürich HB → Bern", r.title)
        assertEquals("Heute ab 07:40", r.subtitle)
        assertEquals(5, r.cards.size)
        assertEquals(0, r.selected)
        val first = r.cards[0]
        assertEquals(listOf("IC"), first.badges)
        assertEquals("Richtung Bern", first.direction)
        assertEquals("07:49", first.departure)
        assertEquals("08:50", first.arrival)
        assertEquals("Gl. 15", first.platform)
        assertEquals("1 h 1 min", first.info)
        assertEquals(listOf(Span(0.0, 1.0, false)), first.spans)
        val change = r.cards[3]
        assertEquals(listOf("IR 37", "IR 16"), change.badges)
        assertEquals("1× umsteigen · 1 h 16 min", change.info)
        assertEquals(2, change.spans.size)
        assertEquals(27.0 / 76, change.spans[0].end, 0.001)
        assertEquals("Frühere Verbindungen", r.earlier)
        assertEquals("Spätere Verbindungen", r.later)
        assertTrue(picture(FahrplanApp.P_RESULTS)!!.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `swiping past the last connection loads later ones, past the first earlier ones`() {
        start()
        chooseZurichBern()
        answer(Fixtures.ZUERICH_BERN)
        down(4)
        assertEquals(4, results().selected)
        down()
        // From the last one's departure on.
        assertTrue(url(), url().contains("date=2026-11-02&time=08:33&limit=5"))
        assertEquals("Lade spätere Verbindungen …", results().later)
        answer(Fixtures.ZUERICH_BERN_LATER)
        val r = results()
        assertEquals(10, r.cards.size)
        assertEquals(5, r.selected)
        assertEquals("08:38", r.cards[5].departure)
        assertEquals(listOf("RE 37", "RE 12", "IC 61"), r.cards[5].badges)

        up(5)
        assertEquals(0, results().selected)
        up()
        // Arriving before the first one: those before it.
        assertTrue(url(), url().contains("time=08:49&isArrivalTime=1"))
        ui.answer(HttpResult(200, """{"connections":[]}"""))
        assertEquals("Keine früheren Verbindungen.", ui.toasts.last())
        assertEquals(10, results().cards.size)
    }

    @Test
    fun `a tap opens Ihre Reise, a ride shows its stops, a stop its departures`() {
        start()
        chooseZurichBern()
        answer(Fixtures.ZUERICH_BERN)
        down(3)
        tap()
        assertEquals(FahrplanApp.P_JOURNEY, page())
        val j = journey()
        assertEquals("08:08", j.departure)
        assertEquals("09:24", j.arrival)
        assertEquals(listOf("IR 37", "IR 16"), j.badges)
        assertEquals("Heute · 1 h 16 min · 1× umsteigen", j.info)
        assertEquals(
            listOf(
                JourneyItem.Stop("08:08", null, "Zürich HB", "Gl. 16", false, true, "8503000"),
                JourneyItem.Ride("IR 37", "Richtung Basel SBB", false, 1),
                JourneyItem.Stop("08:35", null, "Aarau", "Gl. 4", false, false, "8502113"),
                JourneyItem.Change("Umsteigen in Aarau · 11 Min."),
                JourneyItem.Stop("08:46", null, "Aarau", "Gl. 5", false, false, "8502113"),
                JourneyItem.Ride("IR 16", "Richtung Bern", false, 1),
                JourneyItem.Stop("09:24", null, "Bern", "Gl. 12", false, true, "8507000"),
            ),
            j.items,
        )
        // The frame starts on the first ride; a tap shows its stops on the way.
        assertEquals(1, j.selected)
        tap()
        val open = journey().items
        assertEquals(JourneyItem.Ride("IR 37", "Richtung Basel SBB", true, 1), open[1])
        assertEquals("Lenzburg", (open[2] as JourneyItem.Pass).name)
        assertEquals("08:27", (open[2] as JourneyItem.Pass).time)

        // The first stop: a tap shows its departures.
        up()
        tap()
        assertEquals(FahrplanApp.P_BOARD, page())
        assertTrue(url(), url().startsWith("https://transport.opendata.ch/v1/stationboard?id=8503000"))
    }

    @Test
    fun `walks, buses and changes on the way to Zermatt`() {
        val central = Place("8588078", "Zürich, Central")
        val zermatt = Place("8501689", "Zermatt")
        ui.storage.set("zuletzt", JsonArray(listOf(FahrplanApp.Route(central, zermatt).toJson())))
        start()
        assertEquals("Zürich, Central → Zermatt", plan().rows.first().text)
        down(3)
        assertEquals(FahrplanApp.PLAN_FIXED, plan().focus)
        tap()
        assertTrue(url(), url().contains("from=8588078&to=8501689"))
        answer(Fixtures.CENTRAL_ZERMATT)
        val card = results().cards.first()
        assertEquals(listOf("IC 8", "EV 4", "R 40"), card.badges)
        assertEquals(listOf(true, false, true, false, true, false), card.spans.map { it.walk })
        tap()
        val items = journey().items
        assertEquals(
            listOf(
                "Stop 07:52 Zürich, Central",
                "Walk 10 Min. zu Fuß bis Zürich HB",
                "Stop 08:02 Zürich HB Gl. 31",
                "Ride IC 8 Richtung Brig",
                "Stop 10:04 Visp Gl. 7",
                "Walk 4 Min. zu Fuß bis Visp, Bahnhof Süd",
                "Stop 10:11 Visp, Bahnhof Süd Kante M",
                "Ride EV 4 Richtung Täsch, Bahnhof",
                "Stop 11:02 Täsch, Bahnhof",
                "Walk 2 Min. zu Fuß bis Täsch",
                "Stop 11:09 Täsch Gl. 1",
                "Ride R 40 Richtung Zermatt",
                "Stop 11:21 Zermatt Gl. 2",
            ),
            items.map {
                when (it) {
                    is JourneyItem.Stop -> listOfNotNull("Stop", it.time, it.name, it.platform).joinToString(" ")
                    is JourneyItem.Ride -> "Ride ${it.badge} ${it.direction}"
                    is JourneyItem.Walk -> "Walk ${it.text}"
                    is JourneyItem.Change -> "Change ${it.text}"
                    is JourneyItem.Pass -> "Pass ${it.name}"
                }
            },
        )
        assertTrue((items.first() as JourneyItem.Stop).end && (items.last() as JourneyItem.Stop).end)
    }

    @Test
    fun `departures of a stop, later ones at the end, the trip of one, a connection from there`() {
        start()
        down(10)
        tap()
        assertEquals(FahrplanApp.ASK_BOARD, ui.question!!.first)
        type("Zürich HB")
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_BOARD, page())
        assertTrue(url(), url().startsWith("https://transport.opendata.ch/v1/stationboard?id=8503000&limit=15&datetime=2026-11-02+07%3A40"))
        assertEquals("Lade die Abfahrten …", list(FahrplanApp.P_BOARD).note)
        answer(Fixtures.BOARD)
        val b = list(FahrplanApp.P_BOARD)
        assertEquals("Zürich HB · ab 07:45", b.title)
        assertEquals(15, b.rows.size)
        assertEquals(Row("Herrliberg-Feldmeilen", Icon.TRAIN, "07:45", null, "S 16", right = "Gl. 43/44"), b.rows[0])
        assertEquals(Row("Bern", Icon.TRAIN, "07:49", null, "IC", right = "Gl. 15"), b.rows[5])
        assertEquals(Pair(FahrplanApp.REFRESH_MS, true), ui.timers[FahrplanApp.T_REFRESH])

        down(14)
        assertEquals(14, list(FahrplanApp.P_BOARD).selected)
        down()
        assertTrue(url(), url().contains("datetime=2026-11-02+07%3A57"))
        answer(Fixtures.BOARD)
        assertEquals("Keine späteren Abfahrten.", ui.toasts.last())

        // A tap shows the trip of the departure in the frame.
        up(9)
        tap()
        assertEquals(FahrplanApp.P_TRIP, page())
        val t = list(FahrplanApp.P_TRIP)
        assertEquals("IC nach Bern", t.title)
        assertEquals("Zürich HB", t.rows.first().text)
        assertEquals("07:49", t.rows.first().time)
        assertEquals("Bern", t.rows.last().text)

        back()
        // Holding: a connection from here.
        hold()
        assertEquals(FahrplanApp.P_PLAN, page())
        assertEquals("Zürich HB", plan().from)
        assertEquals(1, plan().focus)
    }

    @Test
    fun `with a keyboard the stop is typed on the glasses, the stops that fit coming while typing`() {
        ui.keyboard = true
        start()
        tap()
        assertEquals(FahrplanApp.P_SEARCH, page())
        assertTrue(Sensor.KEYS in ui.subscriptions)
        assertNull(ui.question)
        var s = list(FahrplanApp.P_SEARCH)
        assertEquals("Nach", s.fieldLabel)
        assertTrue(s.typing)
        assertEquals("Tippe den Namen der Haltestelle.", s.note)

        key("b", "b")
        assertTrue(ui.requests.isEmpty())
        key("e", "e")
        // A moment after the last key the timetable is asked.
        assertEquals(FahrplanApp.TYPE_MS, ui.timers[FahrplanApp.T_TYPE]!!.first)
        send(AppEvent.Timer(FahrplanApp.T_TYPE))
        assertEquals("https://transport.opendata.ch/v1/locations?query=be&type=station", url())
        answer(Fixtures.BERN)
        s = list(FahrplanApp.P_SEARCH)
        assertEquals("be", s.field)
        assertEquals(listOf("Bern", "Bern, Bahnhof"), s.rows.take(2).map { it.text })
        assertEquals(Icon.TRAM, s.rows[1].icon)
        key(Keys.ARROW_DOWN)
        assertEquals(1, list(FahrplanApp.P_SEARCH).selected)
        key(Keys.ENTER)
        // The destination is there: the connections, from the position of the watch.
        assertEquals(FahrplanApp.P_RESULTS, page())
        assertEquals("Aktueller Standort → Bern, Bahnhof", results().title)
        assertFalse(Sensor.KEYS in ui.subscriptions)
        assertEquals("Suche den Standort …", results().note)
    }

    @Test
    fun `from Aktueller Standort the search waits for the position, and says what to do without one`() {
        start()
        tap()
        type("bern")
        answer(Fixtures.BERN)
        assertEquals("Suche den Standort …", results().note)
        assertTrue(ui.requests.isEmpty())
        assertEquals(setOf(Sensor.LOCATION), ui.subscriptions)
        assertEquals(FahrplanApp.FIX_TIMEOUT_MS, ui.timers[FahrplanApp.T_FIX]!!.first)
        send(AppEvent.Timer(FahrplanApp.T_FIX))
        assertEquals(FahrplanApp.NO_FIX, results().note)
        assertTrue(ui.subscriptions.isEmpty())

        // A tap tries again; the position comes.
        tap()
        fix()
        assertTrue(url(), url().contains("from=47.371200%2C8.538900&to=8507000"))
        assertTrue(ui.subscriptions.isEmpty())
        answer(Fixtures.HERE_LUZERN)
        assertEquals("Aktueller Standort → Bern", results().title)
        assertEquals("07:57", results().cards.first().departure)
        // No way back to a position.
        assertTrue(ui.menu.none { it.text == "Rückfahrt" })
    }

    @Test
    fun `the time is typed, with an for the arrival`() {
        start()
        down(2)
        assertEquals(3, plan().focus)
        tap()
        assertEquals(FahrplanApp.ASK_WHEN, ui.question!!.first)
        type("an 8:30")
        assertEquals("Heute 08:30 · Ankunft", plan().time)
        tap()
        type("bald")
        assertTrue(ui.toasts.last(), ui.toasts.last().startsWith("„bald“ verstehe ich nicht"))

        chooseZurichBern()
        assertTrue(url(), url().contains("date=2026-11-02&time=08:30&isArrivalTime=1"))
        answer(Fixtures.ZUERICH_BERN)
        assertEquals("Heute an 08:30", results().subtitle)
        // Arriving by then: the one closest to it is in the frame.
        assertEquals(4, results().selected)
    }

    @Test
    fun `every minute anew the delays follow, and a chosen journey is followed after it left`() {
        start()
        chooseZurichBern()
        answer(Fixtures.ZUERICH_BERN)
        assertEquals(Pair(FahrplanApp.REFRESH_MS, true), ui.timers[FahrplanApp.T_REFRESH])

        // A minute later: the first train is late and leaves from another platform.
        now += 60_000
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        assertTrue(url(), url().contains("time=07:48"))
        ui.answer(HttpResult(200, late(Fixtures.text(Fixtures.ZUERICH_BERN))))
        val first = results().cards.first()
        assertEquals("+3", first.departureDelay)
        assertEquals("Gl. 16", first.platform)
        assertTrue(first.platformChanged)
        assertEquals(5, results().cards.size)

        tap()
        assertEquals("07:49 +3", journey().departure)
        val stop = journey().items.first() as JourneyItem.Stop
        assertEquals("+3", stop.delay)
        assertTrue(stop.changed)
        // On its page the connection is asked for from its own departure on, and found though it left.
        now += 15 * 60_000
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        assertTrue(url(), url().contains("time=07:48"))
        ui.answer(HttpResult(200, Fixtures.text(Fixtures.ZUERICH_BERN)))
        assertEquals("07:49", journey().departure)
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        ui.answer(HttpResult(200, Fixtures.text(Fixtures.ZUERICH_BERN_LATER)))
        assertEquals("07:49", journey().departure)

        send(AppEvent.Hidden)
        assertNull(ui.timers[FahrplanApp.T_REFRESH])
    }

    /** The first connection 3 minutes late, from platform 16 instead of 15. */
    private fun late(body: String) = body.replaceFirst(
        "\"delay\":null,\"platform\":\"15\",\"prognosis\":{\"platform\":null}",
        "\"delay\":3,\"platform\":\"15\",\"prognosis\":{\"platform\":\"16\"}",
    ).replaceFirst(
        "\"departureTimestamp\":1793602140,\"delay\":null,\"platform\":\"15\",\"prognosis\":{\"platform\":null}",
        "\"departureTimestamp\":1793602140,\"delay\":3,\"platform\":\"15\",\"prognosis\":{\"platform\":\"16\"}",
    )

    @Test
    fun `the stops nearby, from the watch's position`() {
        start()
        down(3)
        tap()
        assertEquals(FahrplanApp.P_NEAR, page())
        assertEquals("Suche den Standort …", list(FahrplanApp.P_NEAR).note)
        fix()
        assertEquals("https://transport.opendata.ch/v1/locations?x=47.371200&y=8.538900&type=station", url())
        answer(Fixtures.NEARBY)
        val n = list(FahrplanApp.P_NEAR)
        assertEquals(listOf(Row("Zürich, Paradeplatz", Icon.TRAM, right = "160 m"), Row("Zürich, Rennweg", Icon.TRAM, right = "210 m")), n.rows.take(2))
        down()
        // A double tap on the watch opens like a tap.
        send(AppEvent.Gesture(GestureKind.DOUBLE_CLICK, InputSource.WATCH))
        assertEquals(FahrplanApp.P_BOARD, page())
        assertTrue(url(), url().contains("stationboard?id=8591316"))
    }

    @Test
    fun `holding on Planen swaps from and to`() {
        start()
        hold()
        assertTrue(ui.toasts.isEmpty())
        chooseZurichBern()
        back()
        hold()
        assertEquals("Bern", plan().from)
        assertEquals("Zürich HB", plan().to)
        // The position cannot be the destination.
        send(AppEvent.Menu(FahrplanApp.M_HERE))
        hold()
        assertEquals("„Aktueller Standort“ kann nicht Ziel sein.", ui.toasts.last())
    }

    @Test
    fun `the same stop for from and to is refused, and Planen shows again`() {
        ui.storage.set("von", Place("8503000", "Zürich HB").toJson())
        start()
        tap()
        type("Zürich")
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_SEARCH, page())
        tap()
        assertEquals("Von und Nach sind dieselbe Haltestelle.", ui.toasts.last())
        assertEquals(FahrplanApp.P_PLAN, page())
        assertTrue(ui.requests.isEmpty())
    }

    @Test
    fun `when the timetable does not answer`() {
        start()
        chooseZurichBern()
        ui.answer(HttpResult(0, "", "timeout"))
        assertEquals(FahrplanApp.OFFLINE, results().note)
        assertTrue(ui.logs.any { it.contains("timeout") })
        tap()
        ui.answer(HttpResult(200, "<html>Wartung</html>"))
        assertEquals(FahrplanApp.UNREADABLE, results().note)
        tap()
        ui.answer(HttpResult(200, """{"connections":[]}"""))
        assertEquals("Keine Verbindung gefunden.", results().note)

        // Looking up a name offline, then one that does not exist.
        back()
        up(3)
        down()
        tap()
        type("Basel")
        ui.answer(HttpResult(503, ""))
        assertEquals(FahrplanApp.P_SEARCH, page())
        assertEquals(FahrplanApp.OFFLINE, list(FahrplanApp.P_SEARCH).note)
        // "Anders eingeben" asks again.
        tap()
        assertEquals(FahrplanApp.ASK_TO, ui.question!!.first)
        type("Xyzzy")
        ui.answer(HttpResult(200, """{"stations":[]}"""))
        assertEquals("Keine Haltestelle „Xyzzy“ gefunden.", list(FahrplanApp.P_SEARCH).note)
    }

    @Test
    fun `the searches and stops used last are kept`() {
        start()
        chooseZurichBern()
        answer(Fixtures.ZUERICH_BERN)
        back()
        assertEquals("Zürich HB → Bern", plan().rows.first().text)

        // A new start of the app finds them in its store.
        val again = FahrplanApp(clock = { now }, zone = Fixtures.ZONE)
        val ui2 = FakeAppContext.forApp(again)
        ui.storage.values.forEach { (k, v) -> ui2.storage.set(k, v) }
        again.onEvent(AppEvent.Start, ui2)
        val p = again.screen(FahrplanApp.P_PLAN) as PlanScreen
        assertEquals("Zürich HB", p.from)
        assertEquals("Bern", p.to)
        assertEquals("Zürich HB → Bern", p.rows.first().text)
        // With a destination the frame starts on "Von".
        assertEquals(0, p.focus)
        assertEquals(2, (ui.storage.get("orte") as JsonArray).size)
    }

    @Test
    fun `the app menu fits the page`() {
        start()
        assertEquals(listOf("Zeit gilt für die Ankunft"), ui.menu.map { it.text })
        chooseZurichBern()
        assertEquals(listOf("Aktualisieren", "Rückfahrt", "Frühere Verbindungen", "Spätere Verbindungen"), ui.menu.map { it.text })
        answer(Fixtures.ZUERICH_BERN)
        send(AppEvent.Menu(FahrplanApp.M_RETURN))
        assertTrue(url(), url().contains("from=8507000&to=8503000"))
        assertEquals("Bern → Zürich HB", results().title)
        answer(Fixtures.ZUERICH_BERN)
        tap()
        assertEquals(listOf("Aktualisieren", "Alle Zwischenhalte zeigen"), ui.menu.map { it.text })
        send(AppEvent.Menu(FahrplanApp.M_STOPS))
        assertTrue(journey().items.filterIsInstance<JourneyItem.Ride>().all { it.open })
        assertEquals("Zwischenhalte ausblenden", ui.menu.last().text)
        back()
        back()
        assertEquals(
            listOf("Von und Nach tauschen", "Von: Aktueller Standort", "Zeit gilt für die Ankunft", "Verlauf löschen"),
            ui.menu.map { it.text },
        )
        send(AppEvent.Menu(FahrplanApp.M_CLEAR))
        assertEquals(listOf("Abfahrten in der Nähe", "Abfahrten einer Haltestelle"), plan().rows.map { it.text })
    }
}

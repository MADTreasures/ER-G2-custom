package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.textOf
import ch.madtreasures.g2watch.apps.valueOf
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app against [FakeAppContext], with the recorded answers of the timetable ([Fixtures]): typing stops and
 * times, choosing among similar names, the connections and one of them, earlier and later ones, the minute's
 * refresh, departures, the stops nearby and "Mein Standort", the store, and what happens when the timetable or
 * the GPS does not answer.
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

    private fun page(): Page = ui.current!!

    /** A click as the host does it: a button with a page shows that page and tells the app. */
    private fun click(block: String) {
        val p = page()
        val b = p.block(block) as? Block.Button ?: throw AssertionError("no button $block on ${p.id}: ${p.blocks.map { it.id }}")
        val target = b.target
        if (target == null) {
            send(AppEvent.Click(p.id, block))
        } else {
            ui.pages.show(target)
            send(AppEvent.Navigate(p.id, target, block))
        }
    }

    private fun back() {
        val p = page().id
        send(AppEvent.Back(p))
        ui.pages.back()
    }

    private fun buttons(): List<String> = page().blocks.filterIsInstance<Block.Button>().map { it.text }

    private fun buttonFor(text: String): String =
        page().blocks.filterIsInstance<Block.Button>().firstOrNull { it.text.startsWith(text) }?.id
            ?: throw AssertionError("no button \"$text\" on ${page().id}: ${buttons()}")

    /** The wearer answers the open question. */
    private fun type(text: String?) {
        val q = ui.question ?: throw AssertionError("no question open")
        ui.questionAnswered()
        send(AppEvent.TextInput(q.first, text))
    }

    /** The timetable answers the oldest request with a recorded answer. */
    private fun answer(fixture: String) = ui.answer(HttpResult(200, Fixtures.text(fixture)))

    private fun url(): String = ui.requests.first().first.url

    private fun fix(lat: Double = Fixtures.LAT, lon: Double = Fixtures.LON) = send(AppEvent.Location(lat, lon, 8f, now))

    /** Zürich HB and Bern chosen as the wearer would: typed, Zürich picked from the list, Bern found at once. */
    private fun chooseZurichBern() {
        click(FahrplanApp.ST_FROM)
        type("Zürich")
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_PICK, page().id)
        click(buttonFor("Zürich HB"))
        click(FahrplanApp.ST_TO)
        type("bern")
        answer(Fixtures.BERN)
    }

    private fun search() {
        click(FahrplanApp.ST_SEARCH)
        answer(Fixtures.ZUERICH_BERN)
    }

    @Test
    fun `the start page asks for from, to and when`() {
        start()
        val p = page()
        assertEquals(FahrplanApp.P_START, p.id)
        assertEquals("Von: Mein Standort", p.textOf(FahrplanApp.ST_FROM))
        assertEquals("Nach: bitte wählen", p.textOf(FahrplanApp.ST_TO))
        assertEquals("Wann: jetzt", p.textOf(FahrplanApp.ST_WHEN))
        assertNull(p.block(FahrplanApp.ST_RECENT))
        // With "Mein Standort" the GPS starts at once, so that the position is there for the search.
        assertEquals(setOf(Sensor.LOCATION), ui.subscriptions)

        click(FahrplanApp.ST_FROM)
        assertEquals(Triple(FahrplanApp.ASK_FROM, "Von wo?", listOf("Mein Standort")), ui.question)
    }

    @Test
    fun `a typed name becomes a stop, the one that fits or a choice among similar ones`() {
        start()
        click(FahrplanApp.ST_FROM)
        type("Zürich")
        assertEquals("https://transport.opendata.ch/v1/locations?query=Z%C3%BCrich&type=station", url())
        // While the timetable looks, the button says so.
        assertEquals("Von: suche „Zürich“ …", page().textOf(FahrplanApp.ST_FROM))
        answer(Fixtures.ZUERICH)
        assertEquals(FahrplanApp.P_PICK, page().id)
        assertEquals("Von welcher Haltestelle?", page().textOf(FahrplanApp.PI_INFO))
        assertEquals(listOf("Mein Standort", "Zürich HB", "Zürich, Hegibachplatz"), buttons().take(3))
        assertEquals("Anders eingeben", buttons().last())
        click(buttonFor("Zürich HB"))
        assertEquals(FahrplanApp.P_START, page().id)
        assertEquals("Von: Zürich HB", page().textOf(FahrplanApp.ST_FROM))
        // A stop of its own: no GPS any more.
        assertTrue(ui.subscriptions.isEmpty())

        click(FahrplanApp.ST_TO)
        // The stop chosen for "Von" is no suggestion for "Nach".
        assertEquals(Triple(FahrplanApp.ASK_TO, "Wohin?", emptyList<String>()), ui.question)
        type("bern")
        answer(Fixtures.BERN)
        // "bern" is the first hit's name: no choice needed.
        assertEquals(FahrplanApp.P_START, page().id)
        assertEquals("Nach: Bern", page().textOf(FahrplanApp.ST_TO))

        // A stop used before is taken without asking the timetable.
        click(FahrplanApp.ST_TO)
        type("zuerich hb")
        assertTrue(ui.requests.isEmpty())
        assertEquals("Nach: Zürich HB", page().textOf(FahrplanApp.ST_TO))
    }

    @Test
    fun `connections with platforms and lines, one of them with its legs and its stops`() {
        start()
        chooseZurichBern()
        click(FahrplanApp.ST_SEARCH)
        assertEquals(FahrplanApp.P_RESULTS, page().id)
        assertTrue(url(), url().startsWith("https://transport.opendata.ch/v1/connections?from=8503000&to=8507000&date=2026-11-02&time=07:40&limit=5"))
        assertEquals("Suche Verbindungen …", page().textOf(FahrplanApp.VE_INFO))
        answer(Fixtures.ZUERICH_BERN)
        val p = page()
        assertEquals("Zürich HB → Bern", p.textOf(FahrplanApp.VE_ROUTE))
        assertEquals("Heute · Abfahrten 07:49 bis 08:32", p.textOf(FahrplanApp.VE_INFO))
        assertEquals(
            listOf(
                "Frühere",
                "07:49 → 08:50 · Gl. 15 · IC",
                "08:02 → 08:58 · Gl. 31 · IC 8",
                "08:06 → 09:24 · Gl. 15 · IR 16",
                "08:08 → 09:24 · Gl. 16 · IR 37, IR 16",
                "08:32 → 09:28 · Gl. 32 · IC 1",
                "Spätere",
                "Rückfahrt",
            ),
            buttons(),
        )
        assertEquals(Timetable.SOURCE, p.textOf(FahrplanApp.VE_SOURCE))

        click("${FahrplanApp.VE_CONN}3")
        val d = page()
        assertEquals(FahrplanApp.P_DETAIL, d.id)
        assertEquals("08:08 → 09:24", d.textOf(FahrplanApp.DE_TIMES))
        assertEquals("1 h 16 min · 1× umsteigen", d.textOf(FahrplanApp.DE_INFO))
        assertEquals("08:08 Zürich HB", d.textOf("${FahrplanApp.DE_DEP}0"))
        assertEquals("Gl. 16", d.valueOf("${FahrplanApp.DE_DEP}0"))
        assertEquals("IR 37 nach Basel SBB", d.textOf("${FahrplanApp.DE_LEG}0"))
        assertEquals("08:35 Aarau", d.textOf("${FahrplanApp.DE_ARR}0"))
        assertEquals("Gl. 4", d.valueOf("${FahrplanApp.DE_ARR}0"))
        assertEquals("11 Min. zum Umsteigen", d.textOf("${FahrplanApp.DE_CHANGE}1"))
        assertEquals("Gl. 5", d.valueOf("${FahrplanApp.DE_DEP}1"))
        assertEquals("09:24 Bern", d.textOf("${FahrplanApp.DE_ARR}1"))

        click("${FahrplanApp.DE_LEG}1")
        val s = page()
        assertEquals(FahrplanApp.P_STOPS, s.id)
        assertEquals("IR 16 nach Bern", s.textOf(FahrplanApp.HA_TITLE))
        assertEquals(listOf("08:46 Aarau", "08:55 Olten", "09:24 Bern"), s.blocks.filterIsInstance<Block.Value>().map { it.text })
    }

    @Test
    fun `walks, buses and changes on the way to Zermatt`() {
        start()
        click(FahrplanApp.ST_FROM)
        type("Zürich")
        answer(Fixtures.ZUERICH)
        click(buttonFor("Zürich, Central"))
        click(FahrplanApp.ST_TO)
        type("Zermatt")
        // The search finds a stop of exactly that name.
        ui.answer(HttpResult(200, """{"stations":[{"id":"8501689","name":"Zermatt"}]}"""))
        click(FahrplanApp.ST_SEARCH)
        answer(Fixtures.CENTRAL_ZERMATT)
        click("${FahrplanApp.VE_CONN}0")
        val texts = page().blocks.map {
            when (it) {
                is Block.Value -> "${it.text} | ${it.value}"
                is Block.Text -> it.text
                is Block.Button -> "[${it.text}]"
                is Block.Heading -> "# ${it.text}"
                else -> it.id
            }
        }
        assertEquals(
            listOf(
                "# 07:52 → 11:21",
                "3 h 29 min · 2× umsteigen",
                "10 Min. zu Fuß bis Zürich HB",
                "08:02 Zürich HB | Gl. 31",
                "[IC 8 nach Brig]",
                "10:04 Visp | Gl. 7",
                "4 Min. zu Fuß bis Visp, Bahnhof Süd",
                "10:11 Visp, Bahnhof Süd | Kante M",
                "[EV 4 nach Täsch, Bahnhof]",
                "11:02 Täsch, Bahnhof | ",
                "2 Min. zu Fuß bis Täsch",
                "11:09 Täsch | Gl. 1",
                "[R 40 nach Zermatt]",
                "11:21 Zermatt | Gl. 2",
                Timetable.SOURCE,
            ),
            texts,
        )
    }

    @Test
    fun `earlier and later connections, and the way back`() {
        start()
        chooseZurichBern()
        search()
        click(FahrplanApp.VE_LATER)
        // From the last one's departure on.
        assertTrue(url(), url().contains("date=2026-11-02&time=08:33&limit=5"))
        answer(Fixtures.ZUERICH_BERN_LATER)
        assertEquals("08:38 → 09:56 · Gl. 16 · RE 37, RE 12, IC 61", buttons()[1])
        click(FahrplanApp.VE_EARLIER)
        // Arriving before the first one: those before it.
        assertTrue(url(), url().contains("time=09:55&isArrivalTime=1"))
        answer(Fixtures.ZUERICH_BERN)
        assertEquals("07:49 → 08:50 · Gl. 15 · IC", buttons()[1])

        click(FahrplanApp.VE_RETURN)
        assertTrue(url(), url().contains("from=8507000&to=8503000"))
        assertEquals("Bern → Zürich HB", page().textOf(FahrplanApp.VE_ROUTE))
        assertEquals(FahrplanApp.P_RESULTS, page().id)
    }

    @Test
    fun `every minute anew, and a chosen connection stays when it has left`() {
        start()
        chooseZurichBern()
        search()
        val timer = ui.timers[FahrplanApp.T_REFRESH]
        assertEquals(Pair(FahrplanApp.REFRESH_MS, true), timer)

        // A minute later: the first train is late and leaves from another platform.
        now += 60_000
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        assertTrue(url(), url().contains("time=07:41"))
        // The old ones show until the new ones are there.
        assertEquals("07:49 → 08:50 · Gl. 15 · IC", buttons()[1])
        ui.answer(HttpResult(200, late(Fixtures.text(Fixtures.ZUERICH_BERN))))
        assertEquals("07:49 +3 → 08:50 · Gl. 16 neu · IC", buttons()[1])

        click("${FahrplanApp.VE_CONN}0")
        assertEquals("07:49 +3 → 08:50", page().textOf(FahrplanApp.DE_TIMES))
        assertEquals("Gl. 16 neu", page().valueOf("${FahrplanApp.DE_DEP}0"))
        // On its page the connection is asked for from its own departure on, and found though it left.
        now += 15 * 60_000
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        assertTrue(url(), url().contains("time=07:48"))
        ui.answer(HttpResult(200, Fixtures.text(Fixtures.ZUERICH_BERN)))
        assertEquals("07:49 → 08:50", page().textOf(FahrplanApp.DE_TIMES))
        // When it is no longer in the answer, it stays as it was.
        send(AppEvent.Timer(FahrplanApp.T_REFRESH))
        ui.answer(HttpResult(200, Fixtures.text(Fixtures.ZUERICH_BERN_LATER)))
        assertEquals("07:49 → 08:50", page().textOf(FahrplanApp.DE_TIMES))

        // Hidden, nothing is asked for.
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
    fun `from Mein Standort the search waits for the watch's position`() {
        start()
        click(FahrplanApp.ST_TO)
        type("bern")
        answer(Fixtures.BERN)
        click(FahrplanApp.ST_SEARCH)
        assertEquals("Suche den Standort …", page().textOf(FahrplanApp.VE_INFO))
        assertTrue(ui.requests.isEmpty())
        assertEquals(setOf(Sensor.LOCATION), ui.subscriptions)
        fix()
        assertTrue(url(), url().contains("from=47.371200%2C8.538900&to=8507000"))
        // The position is there: the GPS rests.
        assertTrue(ui.subscriptions.isEmpty())
        assertEquals("Mein Standort → Bern", page().textOf(FahrplanApp.VE_ROUTE))
        answer(Fixtures.HERE_LUZERN)
        assertEquals("07:57 → 08:51 · Gl. 6 · IR 70", buttons()[1])
        // No way back to a position.
        assertFalse(buttons().contains("Rückfahrt"))
    }

    @Test
    fun `without a position the search says what to do`() {
        start()
        click(FahrplanApp.ST_TO)
        type("bern")
        answer(Fixtures.BERN)
        click(FahrplanApp.ST_SEARCH)
        assertEquals(FahrplanApp.FIX_TIMEOUT_MS, ui.timers[FahrplanApp.T_FIX]!!.first)
        send(AppEvent.Timer(FahrplanApp.T_FIX))
        assertEquals(FahrplanApp.NO_FIX, page().textOf(FahrplanApp.VE_INFO))
        assertTrue(ui.subscriptions.isEmpty())
        assertTrue(buttons().contains("Nochmals"))
    }

    @Test
    fun `when the timetable does not answer`() {
        start()
        chooseZurichBern()
        click(FahrplanApp.ST_SEARCH)
        ui.answer(HttpResult(0, "", "timeout"))
        assertEquals(FahrplanApp.OFFLINE, page().textOf(FahrplanApp.VE_INFO))
        click(FahrplanApp.VE_AGAIN)
        ui.answer(HttpResult(200, "<html>Wartung</html>"))
        assertEquals(FahrplanApp.UNREADABLE, page().textOf(FahrplanApp.VE_INFO))
        click(FahrplanApp.VE_AGAIN)
        ui.answer(HttpResult(200, """{"connections":[]}"""))
        assertEquals("Keine Verbindung gefunden.", page().textOf(FahrplanApp.VE_INFO))
        assertTrue(ui.logs.any { it.contains("timeout") })

        // Looking up a name offline: the pick page offers to try again.
        back()
        click(FahrplanApp.ST_TO)
        type("Basel")
        ui.answer(HttpResult(503, ""))
        assertEquals(FahrplanApp.P_PICK, page().id)
        assertEquals(FahrplanApp.OFFLINE, page().textOf(FahrplanApp.PI_INFO))
        click(FahrplanApp.PI_RETRY)
        ui.answer(HttpResult(200, """{"stations":[]}"""))
        assertEquals("Keine Haltestelle „Basel“ gefunden.", page().textOf(FahrplanApp.PI_INFO))
        click(FahrplanApp.PI_AGAIN)
        assertEquals(FahrplanApp.ASK_TO, ui.question!!.first)
    }

    @Test
    fun `departures of a stop, later ones, and a connection from there`() {
        start()
        click(FahrplanApp.ST_BOARD)
        assertEquals(FahrplanApp.ASK_BOARD, ui.question!!.first)
        type("Zürich")
        answer(Fixtures.ZUERICH)
        click(buttonFor("Zürich HB"))
        assertEquals(FahrplanApp.P_BOARD, page().id)
        assertTrue(url(), url().startsWith("https://transport.opendata.ch/v1/stationboard?id=8503000&limit=15&datetime=2026-11-02+07%3A40"))
        answer(Fixtures.BOARD)
        val p = page()
        assertEquals("Zürich HB", p.textOf(FahrplanApp.AB_STATION))
        assertEquals("Heute ab 07:45", p.textOf(FahrplanApp.AB_INFO))
        assertEquals("07:45 S 16 Herrliberg-Feldmeilen", p.textOf("${FahrplanApp.AB_DEP}0"))
        assertEquals("Gl. 43/44", p.valueOf("${FahrplanApp.AB_DEP}0"))
        assertEquals("07:49 IC Bern", p.textOf("${FahrplanApp.AB_DEP}5"))
        assertEquals(Pair(FahrplanApp.REFRESH_MS, true), ui.timers[FahrplanApp.T_REFRESH])

        click(FahrplanApp.AB_LATER)
        assertTrue(url(), url().contains("datetime=2026-11-02+07%3A57"))
        answer(Fixtures.BOARD)

        click(FahrplanApp.AB_ROUTE)
        assertEquals(FahrplanApp.P_START, page().id)
        assertEquals("Von: Zürich HB", page().textOf(FahrplanApp.ST_FROM))
    }

    @Test
    fun `the stops nearby, from the watch's position`() {
        start()
        click(FahrplanApp.ST_NEAR)
        assertEquals(FahrplanApp.P_NEAR, page().id)
        assertEquals("Suche den Standort …", page().textOf(FahrplanApp.NA_INFO))
        fix()
        assertEquals("https://transport.opendata.ch/v1/locations?x=47.371200&y=8.538900&type=station", url())
        answer(Fixtures.NEARBY)
        assertEquals(
            listOf("Zürich, Paradeplatz · 160 m", "Zürich, Rennweg · 210 m", "Zürich Storchen · 230 m"),
            buttons().take(3),
        )
        click(buttonFor("Zürich, Rennweg"))
        assertEquals(FahrplanApp.P_BOARD, page().id)
        assertTrue(url(), url().contains("stationboard?id=8591316"))
    }

    @Test
    fun `a time typed for the search`() {
        start()
        click(FahrplanApp.ST_WHEN)
        assertEquals(FahrplanApp.ASK_WHEN, ui.question!!.first)
        type("morgen 7:15")
        assertEquals("Wann: morgen 07:15", page().textOf(FahrplanApp.ST_WHEN))
        click(FahrplanApp.ST_WHEN)
        type("bald")
        assertTrue(ui.toasts.last(), ui.toasts.last().startsWith("„bald“ verstehe ich nicht"))
        assertEquals("Wann: morgen 07:15", page().textOf(FahrplanApp.ST_WHEN))
        send(AppEvent.Toggle(FahrplanApp.P_START, FahrplanApp.ST_ARRIVAL, true))

        chooseZurichBern()
        click(FahrplanApp.ST_SEARCH)
        assertTrue(url(), url().contains("date=2026-11-03&time=07:15&isArrivalTime=1"))
    }

    @Test
    fun `searching without a destination asks for it first`() {
        start()
        click(FahrplanApp.ST_SEARCH)
        assertEquals(FahrplanApp.ASK_TO, ui.question!!.first)
        type("bern")
        answer(Fixtures.BERN)
        assertEquals(FahrplanApp.P_RESULTS, page().id)
    }

    @Test
    fun `the searches and stops used last are kept`() {
        start()
        chooseZurichBern()
        search()
        back()
        val p = page()
        assertEquals("Zuletzt gesucht", p.textOf(FahrplanApp.ST_RECENT))
        assertEquals("Zürich HB → Bern", p.textOf("${FahrplanApp.ST_ROUTE}0"))

        // A new start of the app finds them in its store.
        val again = FahrplanApp(clock = { now }, zone = Fixtures.ZONE)
        val ui2 = FakeAppContext.forApp(again)
        (ui.storage as FakeAppContext.MemoryStorage).values.forEach { (k, v) -> ui2.storage.set(k, v) }
        again.onEvent(AppEvent.Start, ui2)
        val start = ui2.current!!
        assertEquals("Von: Zürich HB", start.textOf(FahrplanApp.ST_FROM))
        assertEquals("Nach: Bern", start.textOf(FahrplanApp.ST_TO))
        assertEquals("Zürich HB → Bern", start.textOf("${FahrplanApp.ST_ROUTE}0"))
        assertEquals(2, (ui.storage.get("orte") as JsonArray).size)

        // A search used before starts with one click.
        click("${FahrplanApp.ST_ROUTE}0")
        assertEquals(FahrplanApp.P_RESULTS, page().id)
        assertTrue(url().contains("from=8503000&to=8507000"))
    }

    @Test
    fun `the app menu fits the page`() {
        start()
        assertEquals(emptyList<String>(), ui.menu.map { it.text })
        chooseZurichBern()
        assertEquals(listOf("Von und Nach tauschen", "Von: Mein Standort", "Verlauf löschen"), ui.menu.map { it.text })
        send(AppEvent.Menu(FahrplanApp.M_SWAP))
        assertEquals("Von: Bern", page().textOf(FahrplanApp.ST_FROM))
        assertEquals("Nach: Zürich HB", page().textOf(FahrplanApp.ST_TO))
        send(AppEvent.Menu(FahrplanApp.M_SWAP))
        search()
        assertEquals(listOf("Aktualisieren", "Rückfahrt"), ui.menu.map { it.text })
        send(AppEvent.Menu(FahrplanApp.M_REFRESH))
        assertTrue(url().contains("/connections?"))
        answer(Fixtures.ZUERICH_BERN)
        back()
        send(AppEvent.Menu(FahrplanApp.M_CLEAR))
        assertNull(page().block(FahrplanApp.ST_RECENT))
        send(AppEvent.Menu(FahrplanApp.M_HERE))
        assertEquals("Von: Mein Standort", page().textOf(FahrplanApp.ST_FROM))
    }
}

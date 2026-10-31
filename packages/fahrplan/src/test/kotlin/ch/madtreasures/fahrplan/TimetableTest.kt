package ch.madtreasures.fahrplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Reading what transport.opendata.ch answers (recorded answers, [Fixtures]), and the requests and texts made of it. */
class TimetableTest {
    private val zone = Fixtures.ZONE

    private fun connections(name: String) = Timetable.connections(Fixtures.text(name))

    @Test
    fun `connections keep their times, platforms, lines and changes`() {
        val list = connections(Fixtures.ZUERICH_BERN)
        assertEquals(
            listOf(
                "07:49 → 08:50 · Gl. 15 · IC",
                "08:02 → 08:58 · Gl. 31 · IC 8",
                "08:06 → 09:24 · Gl. 15 · IR 16",
                "08:08 → 09:24 · Gl. 16 · IR 37, IR 16",
                "08:32 → 09:28 · Gl. 32 · IC 1",
            ),
            list.map { Format.row(it, zone) },
        )
        val first = list.first()
        assertEquals("Zürich HB", first.from.name)
        assertEquals("8503000", first.from.id)
        assertEquals("Bern", first.to.name)
        assertEquals("7", first.to.platform)
        assertEquals(61, first.minutes)
        assertEquals(0, first.transfers)
        // A train's running number is no line.
        assertEquals("IC", first.rides.single().line!!.label)
        assertEquals(3, first.rides.single().stops.size)

        val change = list[3]
        assertEquals(1, change.transfers)
        assertEquals(listOf("Basel SBB", "Bern"), change.rides.map { it.line!!.direction })
        assertEquals(listOf("4", "5"), listOf(change.legs[0].arrival.platform, change.legs[1].departure.platform))
        assertEquals("1 h 16 min · 1× umsteigen", "${Format.duration(change.minutes)} · ${Format.transfers(change.transfers)}")
    }

    @Test
    fun `walks, buses and the rail replacement bus to Zermatt`() {
        val list = connections(Fixtures.CENTRAL_ZERMATT)
        assertEquals(5, list.size)
        val c = list.first()
        assertEquals("3 h 29 min · 2× umsteigen", "${Format.duration(c.minutes)} · ${Format.transfers(c.transfers)}")
        assertEquals(
            listOf("10 Min. zu Fuß bis Zürich HB", "IC 8", "4 Min. zu Fuß bis Visp, Bahnhof Süd", "EV 4", "2 Min. zu Fuß bis Täsch", "R 40"),
            c.legs.map { it.line?.label ?: Format.walk(it) },
        )
        val bus = c.legs[3]
        assertEquals("Kante M", Format.platform(bus.departure, bus.line!!))
        assertEquals("", Format.platform(bus.arrival, bus.line!!))
        assertEquals(6, bus.stops.size)

        val second = list[1]
        assertEquals("07:54 → 11:21 · Kante I · Bus 31, IC 8, EV 4, R 40", Format.row(second, zone))
        assertEquals("ICE", list[2].rides[1].line!!.label)
    }

    @Test
    fun `from a position a connection starts with a walk from the address`() {
        val list = connections(Fixtures.HERE_LUZERN)
        val c = list[1]
        assertEquals("Bahnhofstrasse 36, Zürich", c.from.name)
        assertNull(c.from.id)
        assertEquals("3 Min. zu Fuß bis Zürich, Rennweg", Format.walk(c.legs.first()))
        // The tram's stop has no platform.
        assertEquals("08:01 → 08:51 · Tram 7, IR 70", Format.row(c, zone))
    }

    @Test
    fun `delays and a changed platform show where they are known`() {
        val delayed = Fixtures.text(Fixtures.ZUERICH_BERN)
            .replaceFirst("\"delay\":null,\"platform\":\"15\",\"prognosis\":{\"platform\":null}", "\"delay\":3,\"platform\":\"15\",\"prognosis\":{\"platform\":\"16\"}")
        val c = Timetable.connections(delayed).first()
        assertEquals(3, c.from.delay)
        assertEquals("16", c.from.newPlatform)
        assertEquals("07:49 +3 → 08:50 · Gl. 15 · IC", Format.row(c, zone))
        assertEquals("Gl. 16 neu", Format.platform(c.from, c.rides.first().line!!))
        assertEquals("", Format.delay(0))
        assertEquals("", Format.delay(null))
    }

    @Test
    fun `the departures of a stop`() {
        val board = Timetable.board(Fixtures.text(Fixtures.BOARD))
        assertEquals(Place("8503000", "Zürich HB"), board.station)
        assertEquals(15, board.departures.size)
        val first = board.departures.first()
        assertEquals("07:45", Format.clock(first.time, zone))
        assertEquals("S 16", first.line.label)
        assertEquals("Herrliberg-Feldmeilen", first.line.direction)
        assertEquals("Gl. 43/44", Format.platform(first.platform, first.newPlatform, first.line))
        assertEquals("IC", board.departures[5].line.label)
    }

    @Test
    fun `stops by name and around a position`() {
        val zuerich = Timetable.places(Fixtures.text(Fixtures.ZUERICH))
        assertEquals(Timetable.PLACES, zuerich.size)
        assertEquals(Place("8503000", "Zürich HB"), zuerich.first())
        val near = Timetable.places(Fixtures.text(Fixtures.NEARBY))
        // The address itself has no id and is left out.
        assertEquals(Place("8591299", "Zürich, Paradeplatz", 163), near.first())
        assertEquals("160 m", Format.distance(near.first().distance))
        assertEquals("1,2 km", Format.distance(1234))
    }

    @Test
    fun `what is no timetable answer`() {
        assertTrue(Timetable.places("<html>Wartung</html>").isEmpty())
        assertTrue(Timetable.connections("""{"connections":[],"from":null,"to":null}""").isEmpty())
        assertTrue(runCatching { Timetable.connections("<html>") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Timetable.board("[]") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(62, Timetable.minutes("00d01:02:00"))
        assertEquals(1450, Timetable.minutes("01d00:10:00"))
        assertNull(Timetable.minutes("bald"))
    }

    @Test
    fun `requests ask only for what the app reads`() {
        val at = ZonedDateTime.of(2026, 11, 2, 7, 40, 0, 0, zone)
        val url = Timetable.connectionsUrl("8503000", "8507000", at, arrival = false)
        assertTrue(url, url.startsWith("https://transport.opendata.ch/v1/connections?from=8503000&to=8507000&date=2026-11-02&time=07:40&limit=5&fields%5B%5D="))
        assertTrue(url.contains("&fields%5B%5D=connections/sections/journey/passList/platform"))
        assertTrue(Timetable.connectionsUrl(Timetable.position(Fixtures.LAT, Fixtures.LON), "Bern", at, arrival = true)
            .contains("from=47.371200%2C8.538900&to=Bern&date=2026-11-02&time=07:40&isArrivalTime=1"))
        assertEquals("https://transport.opendata.ch/v1/locations?query=Z%C3%BCrich+HB&type=station", Timetable.placesUrl("Zürich HB"))
        assertEquals("https://transport.opendata.ch/v1/locations?x=47.371200&y=8.538900&type=station", Timetable.nearbyUrl(Fixtures.LAT, Fixtures.LON))
        val board = Timetable.boardUrl(Place("8503000", "Zürich HB"), at)
        assertTrue(board, board.startsWith("https://transport.opendata.ch/v1/stationboard?id=8503000&limit=15&datetime=2026-11-02+07%3A40&fields%5B%5D=station/id"))
        assertTrue(Timetable.boardUrl(Place(null, "Bern, Bahnhof"), at).contains("station=Bern%2C+Bahnhof"))
    }
}

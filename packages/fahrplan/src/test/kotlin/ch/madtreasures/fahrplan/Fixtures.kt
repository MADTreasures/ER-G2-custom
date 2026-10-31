package ch.madtreasures.fahrplan

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Answers of transport.opendata.ch (src/test/fixtures), recorded on 31 October 2026 with the fields the app asks
 * for, for Monday 2 November 2026 from 07:45 on: the timetable as planned, without delays. Timetable data:
 * opentransportdata.swiss. Unit tests run in the app module's folder.
 */
object Fixtures {
    private val dir = File("../packages/fahrplan/src/test/fixtures")

    fun text(name: String): String = File(dir, name).readText()

    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

    /** 07:40 on Monday 2 November 2026, when the tests and pictures take place. */
    val MONDAY: Long = ZonedDateTime.of(2026, 11, 2, 7, 40, 0, 0, ZONE).toInstant().toEpochMilli()

    /** Stops called "Zürich", and "bern". */
    const val ZUERICH = "orte-zuerich.json"
    const val BERN = "orte-bern.json"

    /** Stops around Bahnhofstrasse 36 in Zürich (47.3712 N, 8.5389 E). */
    const val NEARBY = "orte-naehe.json"

    /** Zürich HB to Bern from 07:45, and from 08:33 (the "later" ones). */
    const val ZUERICH_BERN = "verbindungen-zuerich-bern.json"
    const val ZUERICH_BERN_LATER = "verbindungen-zuerich-bern-spaeter.json"

    /** Zürich, Central to Zermatt: walks, a bus, a train, the rail replacement bus to Täsch and the shuttle. */
    const val CENTRAL_ZERMATT = "verbindungen-central-zermatt.json"

    /** From the position of [NEARBY] to Luzern: each connection starts with a walk from the address. */
    const val HERE_LUZERN = "verbindungen-standort-luzern.json"

    /** The departures of Zürich HB from 07:45. */
    const val BOARD = "abfahrten-zuerich-hb.json"

    const val LAT = 47.3712
    const val LON = 8.5389
}

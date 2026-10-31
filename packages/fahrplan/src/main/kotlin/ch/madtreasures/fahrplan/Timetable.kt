package ch.madtreasures.fahrplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Swiss timetable of transport.opendata.ch (https://transport.opendata.ch, free, without an account): stops by
 * name or near a place, connections between two of them, and the departures of a stop, with delays and platforms
 * as far as they are known. The data is opentransportdata.swiss's, which is to be named wherever it shows
 * ([SOURCE]). The service has no fixed limit but asks its clients not to repeat a question every few seconds.
 *
 * Every request asks only for the fields the app reads ([CONNECTION_FIELDS], [BOARD_FIELDS]): an answer is then a
 * third of its size, a board a twentieth, which counts over LTE. Times are seconds since 1970.
 */
object Timetable {
    const val BASE = "https://transport.opendata.ch/v1"

    /** Named on every page with data, as the service asks. */
    const val SOURCE = "Fahrplandaten: opentransportdata.swiss"

    /** Connections per request (the service gives at most 16), departures per board, stops per search. */
    const val CONNECTIONS = 5
    const val DEPARTURES = 15
    const val PLACES = 10

    /** Stops whose name matches [query], the best first. */
    fun placesUrl(query: String): String = "$BASE/locations?query=${encode(query)}&type=station"

    /** Stops around a position, the nearest first. */
    fun nearbyUrl(lat: Double, lon: Double): String = "$BASE/locations?x=${coordinate(lat)}&y=${coordinate(lon)}&type=station"

    /**
     * Connections from [from] to [to] – each a stop's id or name, or a position as "lat,lon" (the connection then
     * starts or ends with a walk) – leaving at [at], or with [arrival] arriving by then.
     */
    fun connectionsUrl(from: String, to: String, at: ZonedDateTime, arrival: Boolean): String =
        "$BASE/connections?from=${encode(from)}&to=${encode(to)}&date=${DATE.format(at)}&time=${TIME.format(at)}" +
            (if (arrival) "&isArrivalTime=1" else "") + "&limit=$CONNECTIONS" + fields(CONNECTION_FIELDS)

    /** The departures of [stop] from [at] on. */
    fun boardUrl(stop: Place, at: ZonedDateTime): String =
        "$BASE/stationboard?" + (stop.id?.let { "id=${encode(it)}" } ?: "station=${encode(stop.name)}") +
            "&limit=$DEPARTURES&datetime=${encode(DATE.format(at) + " " + TIME.format(at))}" + fields(BOARD_FIELDS)

    /** "lat,lon" of a position, as [connectionsUrl] takes it. */
    fun position(lat: Double, lon: Double): String = "${coordinate(lat)},${coordinate(lon)}"

    /** The stops of a search answer (addresses and places without an id left out), at most [PLACES]; none for anything else. */
    fun places(body: String): List<Place> {
        val list = (parse(body) as? JsonObject)?.get("stations") as? JsonArray ?: return emptyList()
        return list.mapNotNull { place(it) }.distinctBy { it.id }.take(PLACES)
    }

    /** The connections of an answer; an answer that is no timetable throws [IllegalArgumentException]. */
    fun connections(body: String): List<Connection> {
        val root = parse(body) as? JsonObject ?: throw IllegalArgumentException("no timetable answer")
        return (root["connections"] as? JsonArray).orEmpty().mapNotNull { connection(it) }
    }

    /** The departures of an answer; an answer that is no board throws [IllegalArgumentException]. */
    fun board(body: String): Board {
        val root = parse(body) as? JsonObject ?: throw IllegalArgumentException("no board answer")
        val station = (root["station"] as? JsonObject)?.let { place(it) }
        return Board(station, (root["stationboard"] as? JsonArray).orEmpty().mapNotNull { departure(it) })
    }

    private fun parse(body: String): JsonElement? = try {
        Json.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun place(e: JsonElement): Place? {
        val o = e as? JsonObject ?: return null
        val id = o.text("id") ?: return null
        val name = o.text("name") ?: return null
        return Place(id, name, (o["distance"] as? JsonPrimitive)?.doubleOrNull?.roundToInt())
    }

    private fun connection(e: JsonElement): Connection? {
        val o = e as? JsonObject ?: return null
        val from = stop(o["from"])?.takeIf { it.departure != null } ?: return null
        val to = stop(o["to"])?.takeIf { it.arrival != null } ?: return null
        val legs = (o["sections"] as? JsonArray).orEmpty().mapNotNull { leg(it) }
        if (legs.isEmpty()) return null
        val minutes = minutes(o.text("duration")) ?: ((to.arrival!! - from.departure!!) / 60).toInt()
        val transfers = o.int("transfers") ?: (legs.count { it.line != null } - 1).coerceAtLeast(0)
        return Connection(from, to, minutes, transfers, legs)
    }

    private fun leg(e: JsonElement): Leg? {
        val o = e as? JsonObject ?: return null
        val departure = stop(o["departure"]) ?: return null
        val arrival = stop(o["arrival"]) ?: return null
        val journey = o["journey"] as? JsonObject
        if (journey != null) {
            return Leg(departure, arrival, line(journey), null, (journey["passList"] as? JsonArray).orEmpty().mapNotNull { stop(it) })
        }
        val walk = (o["walk"] as? JsonObject)?.int("duration")
            ?: (arrival.arrival ?: arrival.departure)?.let { a -> departure.departure?.let { d -> (a - d).toInt() } }
        return Leg(departure, arrival, null, walk?.coerceAtLeast(0), emptyList())
    }

    private fun stop(e: JsonElement?): Stop? {
        val o = e as? JsonObject ?: return null
        val station = o["station"] as? JsonObject
        val name = station?.text("name") ?: (o["location"] as? JsonObject)?.text("name") ?: return null
        val planned = o.text("platform")
        val expected = (o["prognosis"] as? JsonObject)?.text("platform")
        return Stop(
            name = name,
            id = station?.text("id"),
            arrival = o.long("arrivalTimestamp"),
            departure = o.long("departureTimestamp"),
            delay = o.int("delay"),
            platform = planned,
            newPlatform = expected?.takeIf { it != planned },
        )
    }

    private fun line(o: JsonObject) = Line(
        category = o.text("category").orEmpty(),
        number = o.text("number"),
        direction = o.text("to").orEmpty(),
        operator = o.text("operator"),
    )

    private fun departure(e: JsonElement): Departure? {
        val o = e as? JsonObject ?: return null
        val s = o["stop"] as? JsonObject ?: return null
        val time = s.long("departureTimestamp") ?: return null
        val planned = s.text("platform")
        val expected = (s["prognosis"] as? JsonObject)?.text("platform")
        return Departure(time, s.int("delay"), planned, expected?.takeIf { it != planned }, line(o))
    }

    /** Minutes of a duration as the service writes it: "00d01:02:00". */
    fun minutes(duration: String?): Int? {
        val m = DURATION.matchEntire(duration ?: return null) ?: return null
        val (d, h, min) = m.destructured
        return d.toInt() * 24 * 60 + h.toInt() * 60 + min.toInt()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun coordinate(v: Double) = "%.6f".format(Locale.ROOT, v)

    private fun fields(names: List<String>) = names.joinToString("") { "&fields%5B%5D=$it" }

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DURATION = Regex("(\\d+)d(\\d{2}):(\\d{2}):(\\d{2})")

    /** The fields of a stop the app reads: name, times, delay, platform (planned and expected). */
    private fun stopFields(path: String, vararg times: String) =
        times.map { "$path/$it" } + listOf("delay", "platform", "prognosis/platform", "station/id", "station/name", "location/name").map { "$path/$it" }

    val CONNECTION_FIELDS: List<String> =
        listOf("connections/duration", "connections/transfers") +
            stopFields("connections/from", "departureTimestamp") +
            stopFields("connections/to", "arrivalTimestamp") +
            listOf("walk", "journey/category", "journey/number", "journey/to", "journey/operator").map { "connections/sections/$it" } +
            listOf("station/name", "arrivalTimestamp", "departureTimestamp", "delay", "platform").map { "connections/sections/journey/passList/$it" } +
            stopFields("connections/sections/departure", "departureTimestamp") +
            stopFields("connections/sections/arrival", "arrivalTimestamp")

    val BOARD_FIELDS: List<String> =
        listOf("station/id", "station/name") +
            listOf("departureTimestamp", "delay", "platform", "prognosis/platform").map { "stationboard/stop/$it" } +
            listOf("category", "number", "to", "operator").map { "stationboard/$it" }
}

/** A stop, or an address of a connection; [id] is the timetable's (null for an address), [distance] in metres from a position. */
data class Place(val id: String?, val name: String, val distance: Int? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("name", name)
    }

    companion object {
        fun fromJson(e: JsonElement?): Place? {
            val o = e as? JsonObject ?: return null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            return Place((o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }, name)
        }
    }
}

/** Where a connection or a ride passes: planned times, the delay in minutes (null: unknown), the platform and a changed one. */
data class Stop(
    val name: String,
    val id: String? = null,
    val arrival: Long? = null,
    val departure: Long? = null,
    val delay: Int? = null,
    val platform: String? = null,
    val newPlatform: String? = null,
)

/**
 * A line as the timetable names it: [category] "IC", "S", "B" (bus), "T" (tram) …, [number] the line ("8", "31")
 * or a train's running number ("001410"), [direction] where it goes.
 */
data class Line(val category: String, val number: String?, val direction: String, val operator: String? = null) {
    private val kind: Kind get() = KINDS[category.uppercase(Locale.ROOT)] ?: Kind.RAIL

    /** "IC 8", "S 12", "Bus 31", "Tram 7", "IC": with the line, not a train's running number. */
    val label: String
        get() {
            val n = number?.takeIf { it.length <= 4 && !it.startsWith("0") }
            val name = when (category.uppercase(Locale.ROOT)) {
                "B", "BUS" -> "Bus"
                "T", "TRAM" -> "Tram"
                else -> when (kind) {
                    Kind.SHIP -> "Schiff"
                    Kind.CABLE -> "Seilbahn"
                    else -> category.ifEmpty { "Linie" }
                }
            }
            return if (n != null) "$name $n" else name
        }

    /** What its platforms are called: Gleis for trains, Kante for buses and trams, Steg for ships. */
    val platformWord: String
        get() = when (kind) {
            Kind.RAIL, Kind.CABLE -> "Gl."
            Kind.ROAD -> "Kante"
            Kind.SHIP -> "Steg"
        }

    private enum class Kind { RAIL, ROAD, SHIP, CABLE }

    private companion object {
        val KINDS: Map<String, Kind> =
            listOf("B", "BUS", "EV", "NFB", "KB", "EXB", "TX", "NB", "T", "TRAM", "NFT", "M").associateWith { Kind.ROAD } +
                listOf("BAT", "BAV", "FAE", "SHIP").associateWith { Kind.SHIP } +
                listOf("FUN", "GB", "PB", "LB", "SL", "ASC", "CC").associateWith { Kind.CABLE }
    }
}

/** A ride ([line] not null, with its [stops]) or a walk of [walkSeconds]. */
data class Leg(val departure: Stop, val arrival: Stop, val line: Line?, val walkSeconds: Int?, val stops: List<Stop>)

/** A connection: leaves at [from] (its departure), arrives at [to] (its arrival), takes [minutes] with [transfers] changes. */
data class Connection(val from: Stop, val to: Stop, val minutes: Int, val transfers: Int, val legs: List<Leg>) {
    val rides: List<Leg> get() = legs.filter { it.line != null }

    /** The same journey as [other], whatever the delays say: same planned times and lines. */
    fun sameAs(other: Connection): Boolean =
        from.departure == other.from.departure && to.arrival == other.to.arrival && rides.map { it.line?.label } == other.rides.map { it.line?.label }
}

/** A departure of a board at [time] (planned). */
data class Departure(val time: Long, val delay: Int?, val platform: String?, val newPlatform: String?, val line: Line)

/** The departures of [station]. */
data class Board(val station: Place?, val departures: List<Departure>)

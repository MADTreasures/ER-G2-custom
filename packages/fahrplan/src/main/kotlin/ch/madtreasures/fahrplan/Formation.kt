package ch.madtreasures.fahrplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.LocalDate

/**
 * The formation of a train, from the Train Formation Service of opentransportdata.swiss
 * (https://opentransportdata.swiss/en/cookbook/realtime-prediction-cookbook/formationsdaten/): at every stop the
 * track, and the cars in the order they stand along the platform from the head of the train, each with its sector,
 * number, class and what it offers (prams, bikes, wheelchairs, low floor). It needs a key of the service (free, in
 * its API manager), covers only the railway undertakings that publish their formations, and knows only today's
 * trains, as it comes from the real-time system.
 */
object Formation {
    const val BASE = "https://api.opentransportdata.swiss/formation/v2"

    /** Named on the formation page, as the service asks. */
    const val SOURCE = "Formationsdaten: opentransportdata.swiss"

    /** The formation of [train] (its number, as "2162") run by [evu] on [date], stop by stop. */
    fun url(evu: String, train: String, date: LocalDate): String =
        "$BASE/formations_stop_based?evu=${encode(evu)}&operationDate=$date&trainNumber=${encode(train)}"

    /** The railway undertaking as the service names it, for an operator as transport.opendata.ch names it; null if it publishes none. */
    fun evu(operator: String?): String? {
        val key = operator?.substringBefore('-')?.uppercase()?.replace("Ö", "OE")?.filter { it.isLetter() } ?: return null
        return EVUS[key]
    }

    /** The number of a train from the name transport.opendata.ch gives its journey ("002162" is 2162). */
    fun trainNumber(name: String?): String? = name?.let { DIGITS.findAll(it).lastOrNull()?.value?.trimStart('0') }?.ifEmpty { null }

    /** The stops of an answer with the formation the train leaves (or arrives) with; an answer that is none throws [IllegalArgumentException]. */
    fun stops(body: String): List<StopFormation> {
        val root = parse(body) as? JsonObject ?: throw IllegalArgumentException("no formation answer")
        val list = root["formationsAtScheduledStops"] as? JsonArray ?: throw IllegalArgumentException("no formation answer")
        return list.mapNotNull { stop(it) }
    }

    private fun parse(body: String): JsonElement? = try {
        Json.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun stop(e: JsonElement): StopFormation? {
        val o = e as? JsonObject ?: return null
        val scheduled = o["scheduledStop"] as? JsonObject ?: return null
        val point = scheduled["stopPoint"] as? JsonObject ?: return null
        val name = point.text("name") ?: return null
        val short = (o["formationShort"] as? JsonObject) ?: return null
        val goals = (short["vehicleGoals"] as? JsonArray).orEmpty().mapNotNull { g ->
            val go = g as? JsonObject ?: return@mapNotNull null
            val to = (go["destinationStopPoint"] as? JsonObject)?.text("name") ?: return@mapNotNull null
            val from = go.int("fromVehicleAtPosition") ?: return@mapNotNull null
            Goal(from, go.int("toVehicleAtPosition") ?: from, to)
        }
        return StopFormation(
            uic = (point["uic"] as? JsonPrimitive)?.longOrNull?.toString() ?: point.text("uic"),
            name = name,
            track = scheduled.text("track")?.takeIf { it != "N/A" },
            cars = cars(short.text("formationShortString").orEmpty()),
            goals = goals,
        )
    }

    /**
     * The cars of the train in a formation short string, in the order they stand from the head of the train; the
     * fictitious cars that fill the platform and parked ones are left out. "@B" starts sector B for the cars
     * after it, "[" and "]" enclose the train, "(" and ")" mark the ends without a way through, before a car
     * "-" says it is closed, "%" a dining car is not served, ">" and "=" it is reserved for groups; then its kind
     * ("2", "FA", "W1" …), ":" its number and "#" what it offers ("KW;NF").
     */
    fun cars(short: String): List<Car> {
        val out = ArrayList<Car>()
        var sector = ""
        var inTrain = false
        for (token in short.split(',')) {
            token.split('@').forEachIndexed { i, piece ->
                var text = piece
                if (i > 0) {
                    sector = text.take(1)
                    text = text.drop(1)
                }
                if ('[' in text) inTrain = true
                val end = ']' in text
                val car = text.filterNot { it in "[]()" }
                if (inTrain && car.isNotEmpty()) car(car, sector)?.let { out += it }
                if (end) inTrain = false
            }
        }
        return out
    }

    private fun car(token: String, sector: String): Car? {
        val status = token.takeWhile { it in "-%>=" }
        val rest = token.drop(status.length)
        val services = rest.substringAfter('#', "").split(';').mapNotNull { SERVICES[it.trim()] }.toSet()
        val head = rest.substringBefore('#')
        val type = head.substringBefore(':')
        if (type == "F" || type == "X") return null
        return Car(
            kind = KINDS[type] ?: CarKind.OTHER,
            number = head.substringAfter(':', "").takeIf { it.isNotEmpty() && it.trimStart('0').isNotEmpty() },
            services = services,
            sector = sector,
            closed = '-' in status,
            notServed = '%' in status,
            groups = '>' in status || '=' in status,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private val DIGITS = Regex("\\d+")

    private val EVUS = mapOf(
        "SBB" to "SBBP", "BLS" to "BLSP", "SOB" to "SOB", "THURBO" to "THURBO", "RHB" to "RhB", "MBC" to "MBC",
        "OEBB" to "OeBB", "TPF" to "TPF", "TRN" to "TRN", "TRANSN" to "TRN", "VDBB" to "VDBB", "ZB" to "ZB",
    )

    private val KINDS = mapOf(
        "1" to CarKind.FIRST, "2" to CarKind.SECOND, "12" to CarKind.BOTH, "FA" to CarKind.FAMILY,
        "WR" to CarKind.DINING, "W1" to CarKind.DINING_FIRST, "W2" to CarKind.DINING_SECOND, "CC" to CarKind.COUCHETTE,
        "WL" to CarKind.SLEEPER, "LK" to CarKind.LOCO, "D" to CarKind.BAGGAGE, "K" to CarKind.OTHER,
    )

    private val SERVICES = mapOf(
        "BHP" to CarService.WHEELCHAIR, "BZ" to CarService.BUSINESS, "FZ" to CarService.FAMILY_ZONE, "KW" to CarService.PRAM,
        "NF" to CarService.LOW_FLOOR, "VH" to CarService.BIKE, "VR" to CarService.BIKE_RESERVATION,
    )
}

/** What a car is: its class, or what else it is for. */
enum class CarKind(val label: String, val text: String) {
    FIRST("1", "1. Klasse"),
    SECOND("2", "2. Klasse"),
    BOTH("1|2", "1. und 2. Klasse"),
    FAMILY("2", "Familienwagen"),
    DINING("", "Speisewagen"),
    DINING_FIRST("1", "Speisewagen und 1. Klasse"),
    DINING_SECOND("2", "Speisewagen und 2. Klasse"),
    COUCHETTE("", "Liegewagen"),
    SLEEPER("", "Schlafwagen"),
    LOCO("", "Lok"),
    BAGGAGE("", "Gepäckwagen"),
    OTHER("", "Wagen"),
}

/** What a car offers, in the order the SBB app lists it. */
enum class CarService(val text: String) {
    PRAM("Kinderwagen"),
    WHEELCHAIR("Rollstuhlplätze"),
    BIKE("Velo"),
    BIKE_RESERVATION("Velo mit Reservation"),
    LOW_FLOOR("Niederflur"),
    FAMILY_ZONE("Familienzone"),
    BUSINESS("Businesszone"),
}

/** A car of a train at a stop: [sector] the one it starts in, [number] the one on its side (null if none). */
data class Car(
    val kind: CarKind,
    val number: String?,
    val services: Set<CarService>,
    val sector: String,
    val closed: Boolean = false,
    val notServed: Boolean = false,
    val groups: Boolean = false,
)

/** Cars [from] to [to] (counted from 1 at the head of the train) go to [destination]. */
data class Goal(val from: Int, val to: Int, val destination: String)

/** The formation at a stop: its [track], the [cars] from the head of the train, where its parts go. */
data class StopFormation(val uic: String?, val name: String, val track: String?, val cars: List<Car>, val goals: List<Goal>) {
    /** The sectors with cars of the train, from the head on. */
    val sectors: List<String> get() = cars.map { it.sector }.filter { it.isNotEmpty() }.distinct()

    /** "Sektor A", "Sektoren A–B" or "Sektoren A, C": where the train stands, in alphabetical order. */
    fun sectorText(): String? {
        val s = sectors.sorted()
        if (s.isEmpty()) return null
        if (s.size == 1) return "Sektor ${s[0]}"
        val contiguous = s.zipWithNext().all { (a, b) -> b[0] - a[0] == 1 }
        return if (contiguous) "Sektoren ${s.first()}–${s.last()}" else "Sektoren ${s.joinToString(", ")}"
    }

    /** Where car [index] (from 0 at the head) goes when the train splits, or null if all go the same way. */
    fun destinationOf(index: Int): String? {
        if (goals.map { it.destination }.distinct().size < 2) return null
        return goals.firstOrNull { index + 1 in it.from..it.to }?.destination
    }
}

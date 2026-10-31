package ch.madtreasures.fahrplan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the pages write times, delays, platforms and connections, in German. Times are seconds since 1970. */
object Format {
    /** "07:49" */
    fun clock(seconds: Long, zone: ZoneId): String = HOURS.format(Instant.ofEpochSecond(seconds).atZone(zone))

    /** " +3" for a delay of 3 minutes; nothing when on time or unknown. */
    fun delay(minutes: Int?): String = if (minutes != null && minutes > 0) " +$minutes" else ""

    /** "07:49 → 08:50", with the delays: "07:49 +3 → 08:53 +3". */
    fun times(c: Connection, zone: ZoneId): String =
        "${clock(c.from.departure!!, zone)}${delay(c.from.delay)} → ${clock(c.to.arrival!!, zone)}${delay(c.to.delay)}"

    /** A connection in one line: "07:49 → 08:50 · Gl. 15 · IC 1"; the platform is the first ride's. */
    fun row(c: Connection, zone: ZoneId): String {
        val first = c.rides.firstOrNull()
        val platform = first?.let { platform(it.departure, it.line!!) }.orEmpty()
        val lines = c.rides.joinToString(", ") { it.line!!.label }
        return listOf(times(c, zone), platform, lines).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "Gl. 15", "Kante I", a changed platform "Gl. 16 neu"; nothing when unknown. */
    fun platform(stop: Stop, line: Line): String = platform(stop.platform, stop.newPlatform, line)

    fun platform(planned: String?, changed: String?, line: Line): String {
        val p = changed ?: planned ?: return ""
        return "${line.platformWord} $p" + if (changed != null && planned != null) " neu" else ""
    }

    /** "08:02 Zürich HB", with a delay "08:02 +4 Zürich HB". */
    fun stop(seconds: Long?, stop: Stop, zone: ZoneId): String =
        (seconds?.let { clock(it, zone) + delay(stop.delay) + " " } ?: "") + stop.name

    /** "56 min", "1 h", "3 h 29 min". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    fun transfers(n: Int): String = if (n <= 0) "direkt" else "$n× umsteigen"

    /** "10 Min. zu Fuß bis Zürich HB" */
    fun walk(leg: Leg): String = "${minutesUp(leg.walkSeconds ?: 0)} Min. zu Fuß bis ${leg.arrival.name}"

    /** Whole minutes, rounded up, at least one. */
    fun minutesUp(seconds: Int): Int = ((seconds + 59) / 60).coerceAtLeast(1)

    /** "160 m", "1,2 km". */
    fun distance(metres: Int?): String = when {
        metres == null -> ""
        metres < 1000 -> "${(metres + 5) / 10 * 10} m"
        else -> "%.1f km".format(Locale.GERMAN, metres / 1000.0)
    }

    /** "heute", "morgen", or "Mo 2.11.". */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "heute"
        today.plusDays(1) -> "morgen"
        else -> "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth}.${date.monthValue}."
    }

    /** [day] for the date of [seconds]. */
    fun day(seconds: Long, today: LocalDate, zone: ZoneId): String = day(Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate(), today)

    fun capital(s: String): String = s.replaceFirstChar { it.titlecase(Locale.GERMAN) }

    private val HOURS = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** Monday first, as java.time counts. */
    val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
}

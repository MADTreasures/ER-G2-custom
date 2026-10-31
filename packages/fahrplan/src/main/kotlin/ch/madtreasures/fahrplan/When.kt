package ch.madtreasures.fahrplan

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What the wearer answers to "Wann?", typed on the watch or a keyboard, or said: "jetzt"; a time ("17:30", "17.30",
 * "1730", "17 Uhr", "17h30", "7"); a day with or without one ("morgen 7:15", "übermorgen", "Mo 8:00", "2.11. 8:00",
 * "2.11.2026 8:00"); or "in 20 Min." / "in 2 h". Filler words ("um", "ab", "am") do not count, the order does not
 * matter. A time alone that lies more than an hour back means tomorrow; a day alone keeps the time of now.
 */
object When {
    sealed interface Answer {
        data object Now : Answer

        /** Milliseconds since 1970, to the minute. */
        data class At(val ms: Long) : Answer
    }

    /** The answer to [text] at [now], or null when it means nothing of the above. */
    fun parse(text: String, now: ZonedDateTime): Answer? {
        val s = text.trim().lowercase(Locale.GERMAN).replace(SPACES, " ")
        val bare = s.trimEnd('.', '!')
        if (bare.isEmpty() || bare in NOW) return Answer.Now
        val minuteNow = now.truncatedTo(ChronoUnit.MINUTES)
        IN.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val hours = m.groupValues[2].let { it.startsWith("h") || it.startsWith("st") }
            return at(minuteNow.plusMinutes(if (hours) n * 60 else n))
        }
        var day: LocalDate? = null
        var time: LocalTime? = null
        val today = now.toLocalDate()
        val tokens = s.replace(',', ' ').split(' ').map { if (it.endsWith("uhr.")) it.dropLast(4) else it.removeSuffix("uhr") }.filter { it.isNotEmpty() && it !in FILLERS }
        if (tokens.isEmpty()) return null
        for (token in tokens) {
            // A full stop at the end belongs to a date ("2.11."), else to the sentence ("morgen.").
            val t = if (dayOf(token, today) == null && timeOf(token) == null) token.trimEnd('.') else token
            val date = dayOf(t, today)
            val clock = if (date == null) timeOf(t) else null
            when {
                date != null && day == null -> day = date
                clock != null && time == null -> time = clock
                else -> return null
            }
        }
        var at = ZonedDateTime.of(day ?: today, time ?: minuteNow.toLocalTime(), now.zone)
        if (day == null && at.isBefore(now.minusHours(1))) at = at.plusDays(1)
        return at(at)
    }

    /** "jetzt", or when [ms] is: "heute 17:30", "morgen 07:15", "Mo 2.11. 08:00". */
    fun label(ms: Long?, now: ZonedDateTime): String {
        if (ms == null) return "jetzt"
        val at = Instant.ofEpochMilli(ms).atZone(now.zone)
        return Format.day(at.toLocalDate(), now.toLocalDate()) + " " + Format.clock(ms / 1000, now.zone)
    }

    private fun at(t: ZonedDateTime) = Answer.At(t.truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli())

    private fun dayOf(t: String, today: LocalDate): LocalDate? {
        when (t) {
            "heute" -> return today
            "morgen" -> return today.plusDays(1)
            "übermorgen", "uebermorgen" -> return today.plusDays(2)
        }
        WEEKDAY_NAMES.entries.firstOrNull { (_, names) -> t in names }?.let { (index, _) ->
            // The next such day, today included.
            return today.plusDays(((index - (today.dayOfWeek.value - 1)) + 7L) % 7)
        }
        val m = DATE.matchEntire(t) ?: return null
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        return try {
            val d = LocalDate.of(year ?: today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            // Without a year a date gone by is next year's.
            if (year == null && d.isBefore(today)) d.plusYears(1) else d
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun timeOf(t: String): LocalTime? {
        val (h, min) = HOUR_MINUTE.matchEntire(t)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?: DIGITS.matchEntire(t)?.let { m ->
                val d = m.groupValues[1]
                when (d.length) {
                    1, 2 -> d.toInt() to 0
                    else -> d.dropLast(2).toInt() to d.takeLast(2).toInt()
                }
            }
            ?: return null
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private val SPACES = Regex("\\s+")
    private val NOW = setOf("jetzt", "sofort", "gleich", "now")
    private val FILLERS = setOf("um", "ab", "am", "an", "den", "der", "zur", "zu")
    private val IN = Regex("in (\\d{1,3}) ?(min|mins|minute|minuten|m|h|std|stunde|stunden)?\\.?")
    private val DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2}|\\d{4})?")
    private val HOUR_MINUTE = Regex("(\\d{1,2})[:.h](\\d{2})")
    private val DIGITS = Regex("(\\d{1,4})h?")

    /** Names of the days, Monday (0) first. */
    private val WEEKDAY_NAMES: Map<Int, Set<String>> = mapOf(
        0 to setOf("mo", "montag"),
        1 to setOf("di", "dienstag"),
        2 to setOf("mi", "mittwoch"),
        3 to setOf("do", "donnerstag"),
        4 to setOf("fr", "freitag"),
        5 to setOf("sa", "samstag"),
        6 to setOf("so", "sonntag"),
    )
}

package ch.madtreasures.fahrplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

/** What the wearer may answer to "Wann?", on Monday 2 November 2026 at 07:40. */
class WhenTest {
    private val now = Instant.ofEpochMilli(Fixtures.MONDAY).atZone(Fixtures.ZONE)

    private fun at(text: String): String? = when (val a = When.parse(text, now)) {
        null -> null
        When.Answer.Now -> "jetzt"
        is When.Answer.At -> Instant.ofEpochMilli(a.ms).atZone(Fixtures.ZONE).let { "%d.%d.%d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.year, it.hour, it.minute) }
    }

    @Test
    fun `now`() {
        for (t in listOf("jetzt", "", "  ", "Jetzt.", "sofort")) assertEquals(t, "jetzt", at(t))
    }

    @Test
    fun `a time today, or tomorrow when it lies more than an hour back`() {
        for (t in listOf("17:30", "17.30", "1730", "17h30", "um 17:30", "17:30 Uhr")) assertEquals(t, "2.11.2026 17:30", at(t))
        assertEquals("2.11.2026 17:00", at("17 Uhr"))
        assertEquals("2.11.2026 17:00", at("17uhr"))
        assertEquals("2.11.2026 07:00", at("7"))
        assertEquals("2.11.2026 07:15", at("715"))
        assertEquals("3.11.2026 06:00", at("6:00"))
    }

    @Test
    fun `a day, with or without a time`() {
        assertEquals("3.11.2026 07:15", at("morgen 7:15"))
        assertEquals("3.11.2026 07:15", at("7:15 morgen"))
        assertEquals("4.11.2026 07:40", at("übermorgen"))
        assertEquals("2.11.2026 08:00", at("Mo 8:00"))
        assertEquals("8.11.2026 07:40", at("So"))
        assertEquals("6.11.2026 18:00", at("Freitag um 18 Uhr"))
        assertEquals("2.11.2026 08:00", at("2.11. 8:00"))
        assertEquals("24.12.2026 07:40", at("24.12."))
        assertEquals("1.1.2027 07:40", at("1.1."))
        assertEquals("2.11.2027 08:00", at("2.11.2027 8:00"))
        assertEquals("3.11.2026 07:40", at("morgen."))
    }

    @Test
    fun `in a while`() {
        assertEquals("2.11.2026 08:00", at("in 20 Min."))
        assertEquals("2.11.2026 08:00", at("in 20 Minuten"))
        assertEquals("2.11.2026 09:40", at("in 2 h"))
        assertEquals("2.11.2026 09:10", at("in 90"))
    }

    @Test
    fun `what means no time`() {
        for (t in listOf("Unsinn", "25:00", "8:61", "morgen früh", "31.2.", "morgen heute", "8:00 9:00")) assertNull(t, at(t))
    }

    @Test
    fun `the start page writes the time so`() {
        assertEquals("jetzt", When.label(null, now))
        fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, Fixtures.ZONE).toInstant().toEpochMilli()
        assertEquals("heute 17:30", When.label(ms(2, 17, 30), now))
        assertEquals("morgen 07:15", When.label(ms(3, 7, 15), now))
        assertEquals("Fr 6.11. 18:00", When.label(ms(6, 18, 0), now))
    }
}

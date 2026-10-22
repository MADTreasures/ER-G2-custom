package ch.madtreasures.fluency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which utterances the live page shows, in which order, and what the history keeps. */
class CaptionsTest {
    private val c = Captions(keep = 3)

    private fun u(id: Long, src: String, text: String, final: Boolean = false, from: String? = null) =
        Utterance(id, src, text, final, from)

    @Test
    fun `finished translations stay, each on its own line, the one being spoken follows`() {
        c.update(u(1, "Hello", "Hallo", final = true))
        c.update(u(2, "How are", "Wie geht"))
        assertEquals("Hallo\nWie geht", c.translation(partials = true))
        assertEquals("How are", c.original(partials = true))
        // Without partial results only the finished one, and its original.
        assertEquals("Hallo", c.translation(partials = false))
        assertEquals("Hello", c.original(partials = false))
    }

    @Test
    fun `the last three finished ones at most`() {
        for (i in 1L..5L) c.update(u(i, "s$i", "t$i", final = true))
        assertEquals("t3\nt4\nt5", c.translation(partials = true))
        assertEquals("t3\nt4\nt5", c.translation(partials = true, finished = 3))
        assertEquals("t5", c.translation(partials = true, finished = 1))
    }

    @Test
    fun `a finished utterance arriving after the next one started keeps its place`() {
        c.update(u(1, "One", "Eins"))
        c.update(u(2, "Two", "Zwei"))
        c.update(u(1, "One.", "Eins.", final = true))
        assertEquals("Eins.\nZwei", c.translation(partials = true))
        assertEquals("Two", c.original(partials = true))
    }

    @Test
    fun `a late partial result does not undo a finished one, a dropped utterance disappears`() {
        c.update(u(1, "One.", "Eins.", final = true))
        assertFalse(c.update(u(1, "On", "Ein")))
        assertEquals("Eins.", c.translation(partials = true))
        c.update(u(2, "Hmm", "Hm"))
        assertTrue(c.update(Utterance(2, "", "", final = false, gone = true)))
        assertEquals("Eins.", c.translation(partials = true))
        // A finished one cannot be dropped, and dropping what is not there changes nothing.
        assertFalse(c.update(Utterance(1, "", "", final = false, gone = true)))
        assertFalse(c.update(Utterance(9, "", "", final = false, gone = true)))
        assertFalse(c.update(u(1, "One.", "Eins.", final = true)))
    }

    @Test
    fun `the history keeps finished utterances newest first, up to its size, across sessions`() {
        for (i in 1L..4L) c.update(u(i, "s$i", "t$i", final = true))
        assertEquals(listOf("t4", "t3", "t2"), c.history.map { it.text })
        c.startSession()
        assertEquals("", c.translation(partials = true))
        assertEquals("", c.original(partials = true))
        // Fluency restarted: its numbers start again, it is a new utterance all the same.
        c.update(u(1, "again", "wieder", final = true))
        assertEquals(listOf("wieder", "t4", "t3"), c.history.map { it.text })
        assertEquals("wieder", c.translation(partials = true))
        c.clearHistory()
        assertTrue(c.history.isEmpty())
    }

    @Test
    fun `a corrected finished utterance takes the place of its history entry`() {
        c.update(u(1, "Bank", "bank", final = true))
        c.update(u(1, "Bank", "bench", final = true))
        assertEquals(listOf("bench"), c.history.map { it.text })
        assertEquals("bench", c.translation(partials = true))
    }

    @Test
    fun `blank translations are no lines and no history`() {
        c.update(u(1, "Ähm", "", final = true))
        c.update(u(2, "Yes", " "))
        assertEquals("", c.translation(partials = true))
        assertTrue(c.history.isEmpty())
        assertEquals("Yes", c.original(partials = true))
    }

    @Test
    fun `the original carries its language when Fluency names it`() {
        c.update(u(1, " Bonjour ", "Guten Tag", final = true, from = "fr"))
        assertEquals("FR: Bonjour", c.original(partials = true))
        c.update(u(2, "Hola", "Hallo", from = "auto"))
        assertEquals("Hola", c.original(partials = true))
    }

    @Test
    fun `a long session keeps only a few utterances for the page`() {
        // Every other one never finished (its end got lost): the newest 8 are kept all the same.
        for (i in 1L..20L) c.update(u(i, "s$i", "t$i", final = i % 2 == 0L))
        assertEquals("t13\nt15\nt16\nt17\nt18\nt19\nt20", c.translation(partials = true))
    }
}

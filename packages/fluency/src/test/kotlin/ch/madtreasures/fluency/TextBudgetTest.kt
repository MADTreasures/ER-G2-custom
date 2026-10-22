package ch.madtreasures.fluency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fitting captions into their lines: the newest words stay, "…" marks what is missing. */
class TextBudgetTest {
    private val h = FluencyApp.HEADING

    @Test
    fun `short text fits as it is, spaces tidied`() {
        assertEquals("Guten Morgen!", TextBudget.tail("  Guten   Morgen! ", h, bold = true, maxLines = 1))
        assertEquals(1, TextBudget.lines("Guten Morgen!", h, bold = true))
        assertEquals(2, TextBudget.lines("Eins\nZwei", h, bold = true))
        assertEquals(1, TextBudget.lines("", h, bold = true))
    }

    @Test
    fun `long text keeps its end with an ellipsis in front`() {
        val text = (1..60).joinToString(" ") { "Wort$it" }
        val fit = TextBudget.tail(text, h, bold = true, maxLines = 2)
        assertTrue(fit, fit.startsWith("…Wort"))
        assertTrue(fit.endsWith("Wort60"))
        assertTrue(TextBudget.lines(fit, h, bold = true) <= 2)
        // As much as fits: one word more would need a third line.
        val more = text.substring(0, text.length - fit.length + 1).trimEnd().substringAfterLast(' ')
        assertTrue(TextBudget.lines("…$more ${fit.removePrefix("…")}", h, bold = true) > 2)
    }

    @Test
    fun `a paragraph that starts on its own line gets no ellipsis`() {
        val old = (1..40).joinToString(" ") { "alt$it" }
        val fit = TextBudget.tail("$old\nNeuer Satz.", h, bold = true, maxLines = 1)
        assertEquals("Neuer Satz.", fit)
        val two = TextBudget.tail("$old\nNeuer Satz.", h, bold = true, maxLines = 2)
        assertTrue(two, two.startsWith("…") && two.endsWith("\nNeuer Satz."))
    }

    @Test
    fun `text without spaces is cut between characters, an emoji stays whole`() {
        val chinese = "下一班开往日内瓦的火车将于十五点十二分从四号站台出发请注意安全".repeat(4)
        val fit = TextBudget.tail(chinese, h, bold = true, maxLines = 2)
        assertTrue(fit.startsWith("…"))
        assertTrue(chinese.endsWith(fit.removePrefix("…")))
        assertTrue(TextBudget.lines(fit, h, bold = true) <= 2)

        val emoji = "👨‍👩‍👧".repeat(40)
        val cut = TextBudget.tail(emoji, h, bold = true, maxLines = 1)
        assertTrue(cut.startsWith("…"))
        assertEquals(0, cut.removePrefix("…").length % "👨‍👩‍👧".length)
    }

    @Test
    fun `a word longer than a line counts all its lines`() {
        val word = "Donaudampfschifffahrtsgesellschaftskapitänswitwenrentenversicherung"
        assertEquals(1, TextBudget.lines(word, FluencyApp.BODY, bold = false, max = 2000))
        assertTrue(TextBudget.lines(word, h, bold = true) >= 2)
        val fit = TextBudget.tail("$word $word", h, bold = true, maxLines = 2)
        assertTrue(fit, TextBudget.lines(fit, h, bold = true) <= 2)
        assertTrue(fit.endsWith("versicherung"))
    }

    @Test
    fun `nothing fits into no lines`() {
        assertEquals("", TextBudget.tail("Hallo", h, bold = true, maxLines = 0))
        assertEquals("", TextBudget.tail("   ", h, bold = true, maxLines = 3))
    }

    @Test
    fun `wide characters count wider than narrow ones`() {
        assertTrue(TextBudget.width("MMMM") > TextBudget.width("mmmm"))
        assertTrue(TextBudget.width("mmmm") > TextBudget.width("iiii"))
        assertTrue(TextBudget.width("日本語") > TextBudget.width("abc"))
        assertEquals(TextBudget.width("e"), TextBudget.width("é"))
        assertTrue(TextBudget.width("😊") >= 1f)
    }
}

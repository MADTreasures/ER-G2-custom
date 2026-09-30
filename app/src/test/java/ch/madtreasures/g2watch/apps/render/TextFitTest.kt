package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.FakeText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Word wrap and "…" with [FakeText]: 10 px per UTF-16 char at size 20. */
class TextFitTest {
    private val text = FakeText()

    @Test
    fun `wraps at spaces and cuts words that do not fit`() {
        assertEquals(listOf("ab cd", "ef"), TextFit.wrap(text, "ab cd ef", 20, false, 50))
        assertEquals(listOf("abcd", "efg"), TextFit.wrap(text, "abcdefg", 20, false, 40))
        assertEquals(listOf("a", "b"), TextFit.wrap(text, "ab", 20, false, 5))
    }

    @Test
    fun `cuts between emoji, never inside one`() {
        assertEquals(listOf("😀", "😀", "😀"), TextFit.wrap(text, "😀😀😀", 20, false, 30))
        assertEquals(listOf("👨‍👩‍👧", "ab"), TextFit.wrap(text, "👨‍👩‍👧ab", 20, false, 30))
        assertEquals("ab…", TextFit.ellipsize(text, "ab😀cd", 20, false, 40))
        assertEquals("ab😀…", TextFit.ellipsize(text, "ab😀cd", 20, false, 50))
        assertEquals("🇨🇭…", TextFit.ellipsize(text, "🇨🇭🇨🇭🇨🇭", 20, false, 60))
    }

    @Test
    fun `cut positions`() {
        assertArrayEquals(intArrayOf(0, 1, 2), TextFit.cuts("ab"))
        assertArrayEquals(intArrayOf(0, 2, 10, 11), TextFit.cuts("😀👨‍👩‍👧a"))
        assertArrayEquals(intArrayOf(0, 2), TextFit.cuts("é"))
        assertArrayEquals(intArrayOf(0), TextFit.cuts(""))
    }
}

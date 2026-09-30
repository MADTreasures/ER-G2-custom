package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which parts of a text are emoji, with Android's Unicode data (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EmojiTextTest {
    private fun emoji(text: String) = EmojiText.ranges(text).map { text.substring(it) }

    @Test
    fun `finds emoji the way phones show them`() {
        assertEquals(listOf("🤣"), emoji("These CATS are too FUNNY! 🤣 | New Cat Videos"))
        assertEquals(listOf("😼🐶", "😜"), emoji("Clips 2025😼🐶Try Not To Laugh😜"))
        assertEquals(listOf("❤️", "⭐⭐⭐"), emoji("❤️ Liebe ⭐⭐⭐"))
        assertEquals(listOf("🇨🇭"), emoji("🇨🇭 Schweiz"))
        assertEquals(listOf("👨‍👩‍👧"), emoji("👨‍👩‍👧 Familie"))
        assertEquals(listOf("👍🏽"), emoji("Super 👍🏽!"))
        assertEquals(listOf("1️⃣"), emoji("1️⃣ Platz"))
        assertEquals(listOf("🏴󠁧󠁢󠁳󠁣󠁴󠁿"), emoji("🏴󠁧󠁢󠁳󠁣󠁴󠁿 Schottland"))
        assertEquals(listOf("🌧"), emoji("🌧 Regen"))
    }

    @Test
    fun `leaves letters, signs and emoji asked for as text to the text font`() {
        assertEquals(emptyList<String>(), emoji(""))
        assertEquals(emptyList<String>(), emoji("Grüße – „Test“ · 1:02 | ß © ® ™ 100 % #1 *"))
        assertEquals(emptyList<String>(), emoji("❤ ☀ ✓ ♪ ▶ ⏸ ↔ ★"))
        assertEquals(emptyList<String>(), emoji("❤︎ ⭐︎ 🌧︎"))
    }
}

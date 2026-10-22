package ch.madtreasures.fluency

import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With Android's real fonts (Robolectric, native graphics) and the host's own layout: whatever Fluency
 * sends, the live page never grows past the app area, in every display setting. So the newest words of a
 * caption are always in view, and the page never scrolls (it could not: it takes gestures).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LivePageFitTest {
    private val text = AndroidTextPainter()

    /** Hard cases for a width estimate: long compounds, capitals, wide letters, digits, Chinese, emoji, other scripts. */
    private val samples = listOf(
        "Die Donaudampfschifffahrtsgesellschaftskapitänswitwe erzählte von Rindfleischetikettierungsüberwachungsaufgabenübertragungsgesetzen.",
        "ACHTUNG ACHTUNG: WICHTIGE DURCHSAGE AM GLEIS VIER. DER ZUG NACH GENF FÄHRT HEUTE WEGEN BAUARBEITEN NICHT.",
        "mmm www MMM WWW mmm www MMM WWW mmm www MMM WWW mmm www MMM WWW mmm www MMM WWW",
        "Gleis 1234567890 um 23:59 Uhr, Wagen 12 bis 18, Sektor A–D, 100 % ausgebucht, Preis 1'234.50 CHF.",
        "下一班开往日内瓦的火车将于十五点十二分从四号站台出发。请注意安全，不要靠近站台边缘。谢谢合作。",
        "Gute Reise 😊👍🏽👨‍👩‍👧 🇨🇭🇨🇭 und bis bald 🎉🎉🎉 🙏 ❤️",
        Demo.SCRIPT[4].text,
        "รถไฟขบวนถัดไปไปเจนีวาจะออกเวลาสิบห้านาฬิกาสิบสองนาทีจากชานชาลาที่สี่",
        "जिनेवा के लिए अगली ट्रेन पंद्रह बजकर बारह मिनट पर प्लेटफॉर्म चार से रवाना होगी।",
        "Ja.",
    )

    @Test
    fun `captions and original never run past the app area`() {
        for (original in listOf(true, false)) {
            for (big in listOf(false, true)) {
                val h = Harness(FakePhoneLink())
                h.ui.storage.set("original", JsonPrimitive(original))
                h.ui.storage.set("gross", JsonPrimitive(big))
                h.started()
                h.link(LinkEvent.Connected)
                h.fluency(Harness.welcome())
                h.click(FluencyApp.START, FluencyApp.GO)
                h.fluency(Harness.state(running = true, re = 1))
                check(h, "start (original=$original, big=$big)")
                var id = 0L
                for (sample in samples) {
                    for (said in listOf(sample, "$sample $sample")) {
                        id++
                        h.advance(500)
                        h.fluency(Harness.utt(id, said, said))
                        check(h, "partial $id (original=$original, big=$big)")
                        h.advance(500)
                        h.fluency(Harness.utt(id, said, said, final = true))
                        check(h, "finished $id (original=$original, big=$big)")
                    }
                }
            }
        }
    }

    @Test
    fun `the hints fit as well`() {
        for (original in listOf(true, false)) {
            val h = Harness(FakePhoneLink())
            h.ui.storage.set("original", JsonPrimitive(original))
            h.ui.storage.set("gross", JsonPrimitive(true))
            h.started()
            h.click(FluencyApp.START, FluencyApp.GO)
            check(h, "connecting")
            h.link(LinkEvent.Disconnected("Die Verbindung zum Handy ist abgebrochen, weil es außer Reichweite ist."))
            check(h, "lost")
        }
    }

    @Test
    fun `the width estimate is never narrower than the real text, and not much wider`() {
        val latin = samples.take(4) + Demo.SCRIPT.flatMap { listOf(it.source, it.text) } +
            listOf("Die Strasse ist sehr gross. Grüsse aus der grossen Stadt!", "0123456789", "mmmm wwww MMMM WWWW", "…")
        for ((size, bold) in listOf(FluencyApp.HEADING to true, FluencyApp.HEADING_BIG to true, FluencyApp.BODY to false)) {
            var real = 0.0
            var estimated = 0.0
            for (s in samples + latin) {
                val r = text.measure(s, size, bold)
                val e = TextBudget.px(s, size, bold)
                assertTrue("$s at $size px: estimated $e < real $r", e >= r)
                if (s in latin) {
                    real += r
                    estimated += e
                }
            }
            // About 8 % reserve for Latin text: enough for another font, not so much that lines stay empty.
            assertTrue("$size px: estimate ${estimated / real} times the real width", estimated / real in 1.03..1.15)
        }
    }

    private fun check(h: Harness, what: String) {
        val page = h.ui.page(FluencyApp.LIVE)
        val layout = PageLayout.of(page, text)
        assertTrue(
            "$what: ${layout.contentHeight} px > ${layout.height} px\n" + layout.boxes.joinToString("\n") { "${it.block.id}: ${it.lines}" },
            layout.contentHeight <= layout.height,
        )
    }
}

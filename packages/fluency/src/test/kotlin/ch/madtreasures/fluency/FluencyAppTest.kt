package ch.madtreasures.fluency

import ch.madtreasures.fluency.FluencyApp.Companion.DEMO_TIMER
import ch.madtreasures.fluency.FluencyApp.Companion.LIVE
import ch.madtreasures.fluency.FluencyApp.Companion.RENDER_TIMER
import ch.madtreasures.fluency.FluencyApp.Companion.START
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.textOf
import ch.madtreasures.g2watch.apps.valueOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app without a way to the phone ([NoPhoneLink]): its pages, the Probelauf through the captions,
 * temple, watch and keyboard on the live page, the settings.
 */
class FluencyAppTest {
    private var h = Harness()
    private val ui get() = h.ui

    private fun gesture(kind: GestureKind) = h.send(AppEvent.Gesture(kind, InputSource.RIGHT))

    private fun key(name: String, repeat: Boolean = false, ctrl: Boolean = false) =
        h.send(AppEvent.Key(name, if (name.length == 1) name else "", ctrl = ctrl, repeat = repeat))

    private fun demo() {
        h.started()
        h.click(START, FluencyApp.DEMO_BUTTON)
    }

    @Test
    fun `without a way to the phone the start page says so and offers the Probelauf`() {
        h.started()
        val page = ui.current!!
        assertEquals(START, page.id)
        assertEquals("fehlt", page.valueOf(FluencyApp.PHONE))
        assertEquals(FluencyApp.MISSING_TEXT, page.textOf(FluencyApp.MISSING_NOTE))
        assertEquals(
            listOf("Übersetzen", "Probelauf", "Ton: Uhr-Mikrofon", "Automatisch → Deutsch (Schweiz)", "Anzeige", "Verlauf"),
            page.blocks.filterIsInstance<Block.Button>().map { it.text },
        )
        assertEquals(listOf("Probelauf", "Ton: Handy-Mikrofon"), ui.menu.map { it.text })
    }

    @Test
    fun `without a way to the phone, translating says so and stays on the start page`() {
        h.started()
        h.click(START, FluencyApp.GO)
        assertEquals(START, ui.current!!.id)
        assertEquals(FluencyApp.NO_LINK_NOTE, ui.toasts.last())
        assertTrue(ui.subscriptions.isEmpty())
    }

    @Test
    fun `the Probelauf opens the live page with gestures and the keyboard`() {
        demo()
        val page = ui.current!!
        assertEquals(LIVE, page.id)
        assertEquals("Probelauf", page.name)
        assertEquals(InputMode.GESTURES, page.input)
        assertTrue(page.statusBar)
        assertEquals("", h.heading)
        assertEquals("Probelauf: gleich spricht jemand …", h.original)
        assertEquals(setOf(Sensor.KEYS), ui.subscriptions)
        assertEquals(Demo.FIRST_MS, h.dueOf(DEMO_TIMER))
        assertEquals(listOf("Pause", "Original aus", "Schrift groß", "Verlauf"), ui.menu.map { it.text })
    }

    @Test
    fun `words come in, the translation follows, the finished sentence stays and goes to the history`() {
        demo()
        h.advance(Demo.FIRST_MS)
        assertEquals("", h.heading)
        assertEquals("EN: Good", h.original)
        h.advance(Demo.PARTIAL_MS)
        assertEquals("Guten", h.heading)
        assertEquals("EN: Good morning!", h.original)
        h.advance(7 * Demo.PARTIAL_MS)
        assertEquals("Guten Morgen! Suchen Sie den Bahnhof?", h.heading)
        h.advance(Demo.PARTIAL_MS)
        assertEquals("EN: Good morning! Are you looking for the train station?", h.original)
        val history = ui.page(FluencyApp.HISTORY_PAGE)
        assertEquals("Guten Morgen! Suchen Sie den Bahnhof?", history.textOf("${FluencyApp.HISTORY_TEXT}0"))
        assertEquals("EN „Good morning! Are you looking for the train station?“", history.textOf("${FluencyApp.HISTORY_SOURCE}0"))

        // The next sentence starts on a line of its own below the finished one.
        h.advance(Demo.readingMs(Demo.SCRIPT[0].text) + 2 * Demo.PARTIAL_MS - 1)
        assertEquals("Guten Morgen! Suchen Sie den Bahnhof?\nEs", h.heading)
        assertEquals("EN: It is", h.original)
    }

    @Test
    fun `partial results change the page at most every 400 ms`() {
        demo()
        h.advance(Demo.FIRST_MS + Demo.PARTIAL_MS)
        assertEquals("Guten", h.heading)
        // The next result comes after only 100 ms (the demo's timer fired early by hand): the page waits its turn.
        h.advance(100)
        h.send(AppEvent.Timer(DEMO_TIMER))
        assertEquals("Guten", h.heading)
        assertTrue(RENDER_TIMER in ui.timers)
        h.advance(FluencyApp.RENDER_MS - 100)
        assertEquals("Guten Morgen!", h.heading)
    }

    @Test
    fun `a tap pauses and goes on, the menu and the header say so`() {
        demo()
        h.advance(Demo.FIRST_MS)
        gesture(GestureKind.CLICK)
        assertEquals("Pause", h.name)
        assertFalse(DEMO_TIMER in ui.timers)
        assertEquals("Weiter", ui.menu.first().text)
        val shown = h.original
        h.advance(10_000)
        assertEquals(shown, h.original)

        gesture(GestureKind.CLICK)
        assertEquals("Probelauf", h.name)
        assertEquals("Pause", ui.menu.first().text)
        h.advance(Demo.PARTIAL_MS)
        assertEquals("EN: Good morning!", h.original)
    }

    @Test
    fun `swipes make the text large or normal, a long press switches the original line`() {
        demo()
        gesture(GestureKind.SCROLL_DOWN)
        assertEquals(HeadingSize.GROSS, (ui.page(LIVE).block(FluencyApp.NOW) as Block.Heading).size)
        assertEquals("Schrift groß", ui.toasts.last())
        assertEquals(JsonPrimitive(true), ui.storage.get("gross"))
        assertTrue((ui.page(FluencyApp.DISPLAY_PAGE).block(FluencyApp.BIG_TOGGLE) as Block.Toggle).on)
        gesture(GestureKind.SCROLL_UP)
        assertEquals(HeadingSize.NORMAL, (ui.page(LIVE).block(FluencyApp.NOW) as Block.Heading).size)

        gesture(GestureKind.LONG_PRESS)
        assertNull(ui.page(LIVE).block(FluencyApp.ORIGINAL))
        assertEquals("Original aus", ui.toasts.last())
        // Without the original line the hint goes into the caption.
        assertEquals("Probelauf: gleich spricht jemand …", h.heading)
        h.send(AppEvent.Gesture(GestureKind.SWIPE_LEFT, InputSource.WATCH))
        assertEquals("", h.heading)
        assertEquals("Probelauf: gleich spricht jemand …", h.original)
    }

    @Test
    fun `the keyboard works the live page, held keys and shortcuts do nothing`() {
        demo()
        key(Keys.SPACE)
        assertEquals("Pause", h.name)
        key(Keys.SPACE, repeat = true)
        assertEquals("Pause", h.name)
        key(Keys.ENTER)
        assertEquals("Probelauf", h.name)
        key("+")
        assertEquals(HeadingSize.GROSS, (ui.page(LIVE).block(FluencyApp.NOW) as Block.Heading).size)
        key(Keys.ARROW_DOWN)
        assertEquals(HeadingSize.NORMAL, (ui.page(LIVE).block(FluencyApp.NOW) as Block.Heading).size)
        key("o")
        assertNull(ui.page(LIVE).block(FluencyApp.ORIGINAL))
        key("o", ctrl = true)
        assertNull(ui.page(LIVE).block(FluencyApp.ORIGINAL))
        key(Keys.MEDIA_PAUSE)
        assertEquals("Pause", h.name)
        key(Keys.MEDIA_PAUSE)
        assertEquals("Pause", h.name)
        key(Keys.MEDIA_PLAY)
        assertEquals("Probelauf", h.name)

        // V opens the history; there the host has the keys again, and back brings them to the live page.
        key("v")
        assertEquals(FluencyApp.HISTORY_PAGE, ui.current!!.id)
        assertTrue(ui.subscriptions.isEmpty())
        key(Keys.SPACE)
        assertEquals("Probelauf", h.name)
        h.send(AppEvent.Back(FluencyApp.HISTORY_PAGE))
        ui.pages.back()
        assertEquals(setOf(Sensor.KEYS), ui.subscriptions)
    }

    @Test
    fun `back from the live page ends the Probelauf and gives the keys back`() {
        demo()
        h.advance(Demo.FIRST_MS)
        h.send(AppEvent.Back(LIVE))
        ui.pages.back()
        assertEquals(START, ui.current!!.id)
        assertTrue(ui.subscriptions.isEmpty())
        assertTrue(ui.timers.isEmpty())
        assertEquals(listOf("Probelauf", "Ton: Handy-Mikrofon"), ui.menu.map { it.text })
        // Gestures and keys that still arrive change nothing.
        gesture(GestureKind.CLICK)
        key(Keys.SPACE)
        assertEquals(START, ui.current!!.id)
    }

    @Test
    fun `at the end of the Probelauf a tap plays it again`() {
        demo()
        h.advance(120_000)
        assertEquals("Ende", h.name)
        assertEquals("Ende des Probelaufs – tippen: nochmal", ui.toasts.last())
        assertEquals("Nochmal", ui.menu.first().text)
        assertEquals(Demo.SCRIPT.size, ui.page(FluencyApp.HISTORY_PAGE).blocks.count { it.id.startsWith(FluencyApp.HISTORY_TEXT) })
        assertTrue(h.heading.endsWith("Gute Reise! 😊"))

        gesture(GestureKind.CLICK)
        assertEquals("Probelauf", h.name)
        assertEquals("", h.heading)
        h.advance(Demo.FIRST_MS)
        assertEquals("EN: Good", h.original)
    }

    @Test
    fun `the caption rolls on through the long sentence and never needs more than its lines`() {
        demo()
        val long = Demo.SCRIPT[4]
        var rolled = false
        var finished = false
        // The whole script, in steps of a partial result.
        repeat(200) {
            h.advance(Demo.PARTIAL_MS)
            assertTrue(h.heading, TextBudget.lines(h.heading, FluencyApp.HEADING, bold = true) <= 4)
            assertTrue(h.original, TextBudget.lines(h.original, FluencyApp.BODY, bold = false) <= 2)
            if (h.heading.startsWith("…")) rolled = true
            // The finished long sentence: its last words in view, the start cut off.
            if (h.heading.endsWith("die ich je gegessen habe.") && h.original.endsWith("I have ever had.")) {
                finished = true
                assertTrue(h.original.startsWith("…"))
                assertFalse(h.heading.contains(Demo.SCRIPT[0].text))
                assertTrue(long.text.endsWith(h.heading.removePrefix("…").substringAfterLast('\n')))
            }
        }
        assertTrue(rolled && finished)
        assertEquals("Ende", h.name)
    }

    @Test
    fun `the display page switches original, size, partial results and reading aloud, and they are kept`() {
        h.started()
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.ORIGINAL_TOGGLE, false))
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.BIG_TOGGLE, true))
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.PARTIALS_TOGGLE, false))
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.SPEAK_TOGGLE, true))
        // No notes off the live page.
        assertTrue(ui.toasts.isEmpty())

        val again = Harness()
        again.ui.storage.values.putAll(ui.storage.values)
        again.started()
        val display = again.ui.page(FluencyApp.DISPLAY_PAGE)
        assertEquals(listOf(false, true, false, true), display.blocks.map { (it as Block.Toggle).on })
        again.click(START, FluencyApp.DEMO_BUTTON)
        assertNull(again.ui.page(LIVE).block(FluencyApp.ORIGINAL))
        assertEquals(HeadingSize.GROSS, (again.ui.page(LIVE).block(FluencyApp.NOW) as Block.Heading).size)
    }

    @Test
    fun `without partial results only finished sentences appear`() {
        h.started()
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.PARTIALS_TOGGLE, false))
        h.click(START, FluencyApp.DEMO_BUTTON)
        h.advance(Demo.FIRST_MS + 5 * Demo.PARTIAL_MS)
        assertEquals("", h.heading)
        assertEquals("Probelauf: gleich spricht jemand …", h.original)
        h.advance(5 * Demo.PARTIAL_MS)
        assertEquals("Guten Morgen! Suchen Sie den Bahnhof?", h.heading)
        assertEquals("EN: Good morning! Are you looking for the train station?", h.original)
    }

    @Test
    fun `the sound source switches between watch and phone and is kept`() {
        h.started()
        h.click(START, FluencyApp.SOURCE)
        assertEquals("Ton: Handy-Mikrofon", ui.page(START).textOf(FluencyApp.SOURCE))
        assertEquals("Fluency hört über das Handy", ui.toasts.last())
        assertEquals(JsonPrimitive("phone"), ui.storage.get("quelle"))
        assertEquals("Ton: Uhr-Mikrofon", ui.menu.last().text)
        h.send(AppEvent.Menu(FluencyApp.MENU_SOURCE))
        assertEquals("Ton: Uhr-Mikrofon", ui.page(START).textOf(FluencyApp.SOURCE))
    }

    @Test
    fun `languages are picked from the short and the long list, back on the languages page`() {
        h.started()
        ui.show(FluencyApp.LANGUAGE_PAGE)
        ui.show(FluencyApp.FROM_PAGE)
        assertEquals("Automatisch  ✓", ui.page(FluencyApp.FROM_PAGE).textOf("von_auto"))
        h.click(FluencyApp.FROM_PAGE, "von_en")
        assertEquals(listOf(START, FluencyApp.LANGUAGE_PAGE), ui.pages.historyIds)
        assertEquals("Von: Englisch", ui.page(FluencyApp.LANGUAGE_PAGE).textOf(FluencyApp.FROM_BUTTON))
        assertEquals("Englisch  ✓", ui.page(FluencyApp.FROM_PAGE).textOf("von_en"))
        assertEquals("Englisch → Deutsch (Schweiz)", ui.page(START).textOf(FluencyApp.LANGUAGES))

        ui.show(FluencyApp.TO_PAGE)
        ui.show(FluencyApp.TO_MORE_PAGE)
        h.click(FluencyApp.TO_MORE_PAGE, "nach_ja")
        assertEquals(listOf(START, FluencyApp.LANGUAGE_PAGE), ui.pages.historyIds)
        assertEquals("Nach: Japanisch", ui.page(FluencyApp.LANGUAGE_PAGE).textOf(FluencyApp.TO_BUTTON))
        assertEquals("Weitere: Japanisch  ✓", ui.page(FluencyApp.TO_PAGE).textOf(FluencyApp.TO_MORE))
        assertEquals(JsonPrimitive("en"), ui.storage.get("von"))
        assertEquals(JsonPrimitive("ja"), ui.storage.get("nach"))

        h.click(FluencyApp.LANGUAGE_PAGE, FluencyApp.SWAP)
        assertEquals("Japanisch → Englisch", ui.page(START).textOf(FluencyApp.LANGUAGES))
        assertEquals("Japanisch → Englisch", ui.toasts.last())
    }

    @Test
    fun `automatic recognition has nothing to swap, and every language has its button`() {
        h.started()
        h.click(FluencyApp.LANGUAGE_PAGE, FluencyApp.SWAP)
        assertEquals("Bei „Automatisch“ gibt es nichts zu tauschen", ui.toasts.last())
        assertEquals("Automatisch → Deutsch (Schweiz)", ui.page(START).textOf(FluencyApp.LANGUAGES))

        val from = (ui.page(FluencyApp.FROM_PAGE).blocks + ui.page(FluencyApp.FROM_MORE_PAGE).blocks).map { it.id }
        val to = (ui.page(FluencyApp.TO_PAGE).blocks + ui.page(FluencyApp.TO_MORE_PAGE).blocks).map { it.id }
        assertEquals(Languages.all.map { "von_${it.code}" }.toSet() + "von_auto" + FluencyApp.FROM_MORE, from.toSet())
        assertEquals(Languages.all.map { "nach_${it.code}" }.toSet() + FluencyApp.TO_MORE, to.toSet())
        assertEquals(54, Languages.all.size)
    }

    @Test
    fun `a stored setting this version does not know falls back to the default`() {
        ui.storage.set("von", JsonPrimitive("xx"))
        ui.storage.set("nach", JsonPrimitive("auto"))
        ui.storage.set("quelle", JsonPrimitive("brille"))
        h.started()
        assertEquals("Automatisch → Deutsch (Schweiz)", ui.page(START).textOf(FluencyApp.LANGUAGES))
        assertEquals("Ton: Uhr-Mikrofon", ui.page(START).textOf(FluencyApp.SOURCE))
    }

    @Test
    fun `the history lists finished sentences newest first and can be cleared`() {
        demo()
        h.advance(30_000)
        val page = ui.page(FluencyApp.HISTORY_PAGE)
        val texts = page.blocks.filter { it.id.startsWith(FluencyApp.HISTORY_TEXT) }.map { page.textOf(it.id) }
        assertTrue(texts.size >= 3)
        assertEquals("Guten Morgen! Suchen Sie den Bahnhof?", texts.last())
        assertTrue(page.blocks.last() is Block.Button)

        h.click(FluencyApp.HISTORY_PAGE, FluencyApp.CLEAR_HISTORY)
        assertEquals("Noch nichts übersetzt.", ui.page(FluencyApp.HISTORY_PAGE).textOf(FluencyApp.HISTORY_EMPTY))
        assertEquals("Verlauf gelöscht", ui.toasts.last())
    }

    @Test
    fun `the app menu on the live page does what tap, swipe and long press do`() {
        demo()
        h.send(AppEvent.Menu(FluencyApp.MENU_PAUSE))
        assertEquals("Pause", h.name)
        h.send(AppEvent.Menu(FluencyApp.MENU_SIZE))
        assertEquals("Schrift normal", ui.menu.first { it.id == FluencyApp.MENU_SIZE }.text)
        h.send(AppEvent.Menu(FluencyApp.MENU_ORIGINAL))
        assertEquals("Original an", ui.menu.first { it.id == FluencyApp.MENU_ORIGINAL }.text)
        h.send(AppEvent.Menu(FluencyApp.MENU_HISTORY))
        assertEquals(FluencyApp.HISTORY_PAGE, ui.current!!.id)
    }

    @Test
    fun `hidden and visible again, the Probelauf goes on where it was`() {
        demo()
        h.advance(Demo.FIRST_MS)
        h.send(AppEvent.Hidden)
        h.send(AppEvent.Visible)
        assertEquals("Probelauf", h.name)
        h.advance(Demo.PARTIAL_MS)
        assertEquals("Guten", h.heading)
    }

    @Test
    fun `an unknown event changes nothing`() {
        h.started()
        h.send(AppEvent.Navigate(START, FluencyApp.DISPLAY_PAGE, FluencyApp.DISPLAY))
        h.send(AppEvent.Timer("unbekannt"))
        h.send(AppEvent.TextInput("x", "y"))
        h.send(AppEvent.Stop)
        assertEquals(START, ui.current!!.id)
    }
}

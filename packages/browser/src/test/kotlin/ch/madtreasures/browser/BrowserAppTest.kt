package ch.madtreasures.browser

import ch.madtreasures.browser.BrowserApp.Companion.ADDRESS
import ch.madtreasures.browser.BrowserApp.Companion.ASK_ADDRESS
import ch.madtreasures.browser.BrowserApp.Companion.ASK_FIELD
import ch.madtreasures.browser.BrowserApp.Companion.PICTURE
import ch.madtreasures.browser.BrowserApp.Companion.START
import ch.madtreasures.browser.BrowserApp.Companion.WEB
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.WebAction
import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebField
import ch.madtreasures.g2watch.apps.WebState
import ch.madtreasures.g2watch.apps.textOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browser app against [FakeAppContext]: address, page, clicks, scrolling, fields, bookmarks, history. */
class BrowserAppTest {
    private var app = BrowserApp()
    private var ui = FakeAppContext.forApp(app)

    private fun send(event: AppEvent) = app.onEvent(event, ui)

    private fun started() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    /** The wearer enters [text] on the watch. */
    private fun enter(text: String?) {
        send(AppEvent.Click(START, ADDRESS))
        assertEquals(ASK_ADDRESS, ui.question!!.first)
        send(AppEvent.TextInput(ASK_ADDRESS, text))
    }

    private fun web(
        state: WebState,
        url: String,
        title: String = "",
        progress: Int = if (state == WebState.LOADING) 30 else 100,
        reader: Boolean = false,
        readable: Boolean = false,
        canForward: Boolean = false,
        field: WebField? = null,
        message: String? = null,
        canBack: Boolean = false,
    ) = send(AppEvent.Web(PICTURE, state, url, title, progress, canBack = canBack, canForward = canForward, reader = reader, readable = readable, field = field, message = message))

    private fun buttons(page: String) = ui.page(page).blocks.filterIsInstance<Block.Button>().map { it.text }

    private fun menuTexts() = ui.menu.map { it.text }

    @Test
    fun `the start page offers the address, bookmarks, history and the settings`() {
        started()
        assertEquals(START, ui.current!!.id)
        assertEquals(
            listOf("Adresse oder Suche", "Wikipedia", "SRF News", "DuckDuckGo", "Verlauf", "Schrift auf Bildern: Umriss", "Lesezeichen entfernen"),
            buttons(START),
        )
        assertTrue((ui.page(START).block(BrowserApp.READER) as Block.Toggle).on)
        assertEquals(listOf("Adresse eingeben"), menuTexts())
    }

    @Test
    fun `an address from the watch opens the page in the picture, in reading mode`() {
        started()
        enter("srf.ch")
        assertEquals(WEB, ui.current!!.id)
        val picture = ui.page(WEB).block(PICTURE) as Block.Image
        assertEquals(576 to 260, picture.w to picture.h)
        assertTrue(picture.bleed)
        assertEquals(WebAction.Open("https://srf.ch", reader = true), ui.lastWeb(PICTURE))
        assertEquals("Lädt …", ui.page(WEB).name)

        web(WebState.LOADING, "https://www.srf.ch/", progress = 40)
        assertEquals("Lädt … 40 %", ui.page(WEB).name)
        web(WebState.READY, "https://www.srf.ch/news", "SRF News – Nachrichten", reader = true, readable = true)
        assertEquals("📖 SRF News – Nachrichten", ui.page(WEB).name)
        assertTrue("Lesemodus aus" in menuTexts())
    }

    @Test
    fun `typed words, spoken host names and full addresses`() {
        assertEquals("https://srf.ch", Address.of(" srf.ch "))
        assertEquals("https://de.wikipedia.org/wiki/Brille", Address.of("de.wikipedia.org/wiki/Brille"))
        assertEquals("https://wikipedia.org", Address.of("wikipedia punkt org"))
        assertEquals("http://192.168.1.10:8080/", Address.of("http://192.168.1.10:8080/"))
        assertEquals(Address.SEARCH + "Katzen+im+Schnee", Address.of("Katzen im Schnee"))
        assertEquals(Address.SEARCH + "Brille", Address.of("Brille"))
        assertEquals(Address.SEARCH + "f%C3%BCr+2%2B2", Address.of("für 2+2"))
        assertNull(Address.of("  "))
        assertNull(Address.of(null))
        assertEquals("srf.ch", Address.host("https://www.srf.ch/news?x=1"))
    }

    @Test
    fun `clicks and scrolling over the picture go to the page`() {
        started()
        enter("srf.ch")
        send(AppEvent.ImageClick(WEB, PICTURE, 120, 80))
        assertEquals(WebAction.Tap(120, 80), ui.lastWeb(PICTURE))
        send(AppEvent.ImageScroll(WEB, PICTURE, 195))
        assertEquals(WebAction.Scroll(195), ui.lastWeb(PICTURE))
        send(AppEvent.ImageScroll(WEB, PICTURE, -40))
        assertEquals(WebAction.Scroll(-40), ui.lastWeb(PICTURE))
    }

    @Test
    fun `a field the wearer tapped asks for its text on the watch`() {
        started()
        enter("lite.duckduckgo.com/lite/")
        web(WebState.READY, "https://lite.duckduckgo.com/lite/", "DuckDuckGo")
        ui.questionAnswered()
        // A field the page focused by itself waits for the menu.
        web(WebState.READY, "https://lite.duckduckgo.com/lite/", "DuckDuckGo", field = WebField("Suche"))
        assertNull(ui.question)
        assertTrue("Text eingeben" in menuTexts())

        send(AppEvent.ImageClick(WEB, PICTURE, 200, 40))
        web(WebState.READY, "https://lite.duckduckgo.com/lite/", "DuckDuckGo", field = null)
        web(WebState.READY, "https://lite.duckduckgo.com/lite/", "DuckDuckGo", field = WebField("Suchbegriff", "Kat"))
        val (tag, prompt, suggestions) = ui.question!!
        assertEquals(ASK_FIELD, tag)
        assertEquals("Suchbegriff", prompt)
        assertEquals("Kat", suggestions.first())
        send(AppEvent.TextInput(ASK_FIELD, "Katzen"))
        assertEquals(WebAction.Type("Katzen", enter = true), ui.lastWeb(PICTURE))

        // Cancelled: nothing is typed; the menu asks again.
        val typed = ui.webs.size
        send(AppEvent.TextInput(ASK_FIELD, null))
        assertEquals(typed, ui.webs.size)
        send(AppEvent.Menu(BrowserApp.MENU_TYPE))
        assertEquals(ASK_FIELD, ui.question!!.first)
    }

    @Test
    fun `password and text area fields`() {
        started()
        enter("example.org")
        send(AppEvent.ImageClick(WEB, PICTURE, 10, 10))
        web(WebState.READY, "https://example.org/", field = WebField("Passwort", "", password = true))
        assertEquals(emptyList<String>(), ui.question!!.third)
        send(AppEvent.TextInput(ASK_FIELD, "geheim"))
        assertEquals(WebAction.Type("geheim", enter = true), ui.lastWeb(PICTURE))
        // A password never becomes a suggestion.
        send(AppEvent.Menu(BrowserApp.MENU_ADDRESS))
        assertFalse("geheim" in ui.question!!.third)

        send(AppEvent.ImageClick(WEB, PICTURE, 10, 100))
        web(WebState.READY, "https://example.org/", field = WebField("Kommentar", multiline = true))
        send(AppEvent.TextInput(ASK_FIELD, "Zwei\nZeilen"))
        assertEquals(WebAction.Type("Zwei\nZeilen", enter = false), ui.lastWeb(PICTURE))
    }

    @Test
    fun `back without an earlier page ends the page and shows the start page's history`() {
        started()
        enter("srf.ch")
        web(WebState.READY, "https://www.srf.ch/news", "SRF News")
        send(AppEvent.Back(WEB))
        assertEquals(WebAction.Stop, ui.lastWeb(PICTURE))
        assertFalse(ui.webOpen(PICTURE))
        assertEquals(listOf("Adresse eingeben"), menuTexts())
        assertEquals("SRF News", ui.page(BrowserApp.HISTORY).textOf("${BrowserApp.SEEN}0"))
        assertEquals("srf.ch", ui.page(BrowserApp.HISTORY).textOf("${BrowserApp.SEEN_INFO}0"))

        // From the history the page opens again, in a new picture.
        ui.show(BrowserApp.HISTORY)
        send(AppEvent.Click(BrowserApp.HISTORY, "${BrowserApp.SEEN}0"))
        assertEquals(WebAction.Open("https://www.srf.ch/news", reader = true), ui.lastWeb(PICTURE))
        assertEquals(WEB, ui.current!!.id)
    }

    @Test
    fun `bookmarks are set and opened from the menu and removed with two taps`() {
        started()
        enter("srf.ch")
        web(WebState.READY, "https://www.srf.ch/meteo", "SRF Meteo")
        send(AppEvent.Menu(BrowserApp.MENU_MARK))
        assertEquals("Lesezeichen: SRF Meteo", ui.toasts.last())
        assertTrue("Lesezeichen entfernen" in menuTexts())
        assertTrue("SRF Meteo" in buttons(START))
        // The bookmarks are in the menu of the page, to jump there.
        val wiki = ui.menu.first { it.text == "★ Wikipedia" }
        send(AppEvent.Menu(wiki.id))
        assertEquals(WebAction.Open("https://de.m.wikipedia.org/", reader = true), ui.lastWeb(PICTURE))
        assertTrue(ui.menu.all { it.text.toByteArray(Charsets.UTF_8).size <= 32 } && ui.menu.size <= 10)

        // Two taps on the removal page take one away.
        ui.show(BrowserApp.REMOVE)
        send(AppEvent.Click(BrowserApp.REMOVE, "${BrowserApp.REMOVE_ITEM}0"))
        assertEquals("Entfernen? Wikipedia", ui.page(BrowserApp.REMOVE).textOf("${BrowserApp.REMOVE_ITEM}0"))
        send(AppEvent.Click(BrowserApp.REMOVE, "${BrowserApp.REMOVE_ITEM}0"))
        assertEquals("Entfernt: Wikipedia", ui.toasts.last())
        assertFalse("Wikipedia" in buttons(START))

        // Kept across starts.
        val stored = ui.storage
        app = BrowserApp()
        ui = FakeAppContext.forApp(app).also { fresh -> stored.values.forEach { (k, v) -> fresh.storage.set(k, v) } }
        started()
        assertEquals(listOf("SRF News", "DuckDuckGo", "SRF Meteo"), buttons(START).subList(1, 4))
    }

    @Test
    fun `reading mode and the text style are settings that reach an open page`() {
        started()
        send(AppEvent.Toggle(START, BrowserApp.READER, false))
        enter("srf.ch")
        assertEquals(WebAction.Open("https://srf.ch", reader = false), ui.lastWeb(PICTURE))

        // Reading mode from the menu, on a page that has none.
        web(WebState.READY, "https://www.srf.ch/", "SRF", readable = true)
        send(AppEvent.Menu(BrowserApp.MENU_READER))
        assertEquals(WebAction.Reader(true), ui.lastWeb(PICTURE))
        web(WebState.READY, "https://www.srf.ch/", "SRF", reader = false, readable = false)
        assertEquals("Diese Seite hat keinen Lesemodus", ui.toasts.last())

        send(AppEvent.Back(WEB))
        send(AppEvent.Click(START, BrowserApp.CONTRAST))
        assertEquals("Schrift auf Bildern: Leuchtschrift", ui.page(START).textOf(BrowserApp.CONTRAST))
        enter("srf.ch")
        assertEquals(WebAction.Contrast(WebContrast.HALO), ui.lastWeb(PICTURE))
        send(AppEvent.Click(START, BrowserApp.CONTRAST))
        assertEquals(WebAction.Contrast(WebContrast.PLATE), ui.lastWeb(PICTURE))
    }

    @Test
    fun `errors and notes of the watch become hints on the glasses`() {
        started()
        enter("nirgends.example")
        web(WebState.ERROR, "https://nirgends.example", message = "Adresse nicht gefunden")
        assertEquals("Adresse nicht gefunden", ui.toasts.last())
        assertEquals("Fehler", ui.page(WEB).name)
        // The same error again is no new hint.
        val hints = ui.toasts.size
        web(WebState.ERROR, "https://nirgends.example", message = "Adresse nicht gefunden")
        assertEquals(hints, ui.toasts.size)

        enter("srf.ch")
        web(WebState.READY, "https://www.srf.ch/", "SRF", message = "Telefonnummern lassen sich hier nicht anrufen")
        assertEquals("Telefonnummern lassen sich hier nicht anrufen", ui.toasts.last())
        // Forward is offered when the page has a page after it.
        web(WebState.READY, "https://www.srf.ch/", "SRF", canForward = true)
        send(AppEvent.Menu(BrowserApp.MENU_FORWARD))
        assertEquals(WebAction.Forward, ui.lastWeb(PICTURE))
        send(AppEvent.Menu(BrowserApp.MENU_RELOAD))
        assertEquals(WebAction.Reload, ui.lastWeb(PICTURE))
    }

    @Test
    fun `addresses typed before come back as suggestions`() {
        started()
        enter("srf.ch")
        send(AppEvent.Back(WEB))
        enter("Katzen im Schnee")
        assertEquals(WebAction.Open(Address.SEARCH + "Katzen+im+Schnee", reader = true), ui.lastWeb(PICTURE))
        send(AppEvent.Menu(BrowserApp.MENU_ADDRESS))
        assertEquals(listOf("Katzen im Schnee", "srf.ch"), ui.question!!.third)
        // Nothing entered: nothing happens.
        val before = ui.webs.size
        send(AppEvent.TextInput(ASK_ADDRESS, null))
        assertEquals(before, ui.webs.size)
    }

    private fun key(key: String, text: String = "", shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, repeat: Boolean = false) =
        send(AppEvent.Key(key, text, shift = shift, ctrl = ctrl, alt = alt, repeat = repeat))

    @Test
    fun `held keys type and scroll on, shortcuts and Enter act once`() {
        started()
        enter("srf.ch")
        web(WebState.READY, "https://www.srf.ch/meteo", "SRF Meteo", canBack = true)
        val before = ui.webs.size
        key(Keys.ARROW_DOWN)
        key(Keys.ARROW_DOWN, repeat = true)
        key(Keys.ENTER)
        key(Keys.ENTER, repeat = true)
        key("r", ctrl = true)
        key("r", ctrl = true, repeat = true)
        key(Keys.BACKSPACE)
        key(Keys.BACKSPACE, repeat = true)
        key("d", ctrl = true)
        key("d", ctrl = true, repeat = true)
        assertEquals(
            listOf(WebAction.Scroll(BrowserApp.LINE), WebAction.Scroll(BrowserApp.LINE), WebAction.Key(Keys.ENTER), WebAction.Reload, WebAction.Back),
            ui.webs.drop(before).map { it.second },
        )
        assertEquals("Lesezeichen: SRF Meteo", ui.toasts.last())
        // In a field, a held letter and a held Backspace go on.
        web(WebState.READY, "https://www.srf.ch/meteo", "SRF Meteo", field = WebField("Suche"))
        key("a", "a")
        key("a", "a", repeat = true)
        key(Keys.BACKSPACE, repeat = true)
        assertEquals(
            listOf(WebAction.Insert("a"), WebAction.Insert("a"), WebAction.Key(Keys.BACKSPACE)),
            ui.webs.takeLast(3).map { it.second },
        )
    }

    @Test
    fun `the page takes the keyboard's keys, the start page leaves them to the watch`() {
        started()
        assertFalse(Sensor.KEYS in ui.subscriptions)
        enter("srf.ch")
        assertTrue(Sensor.KEYS in ui.subscriptions)
        web(WebState.READY, "https://www.srf.ch/news", "SRF News")
        key(Keys.ARROW_DOWN)
        assertEquals(WebAction.Scroll(BrowserApp.LINE), ui.lastWeb(PICTURE))
        key(Keys.SPACE, " ")
        assertEquals(WebAction.Scroll(195), ui.lastWeb(PICTURE))
        key(Keys.SPACE, " ", shift = true)
        assertEquals(WebAction.Scroll(-195), ui.lastWeb(PICTURE))
        key(Keys.PAGE_DOWN)
        assertEquals(WebAction.Scroll(195), ui.lastWeb(PICTURE))
        key(Keys.TAB, shift = true)
        assertEquals(WebAction.Key(Keys.TAB, shift = true), ui.lastWeb(PICTURE))
        key(Keys.ENTER)
        assertEquals(WebAction.Key(Keys.ENTER), ui.lastWeb(PICTURE))
        // Letters without a field type nothing.
        val before = ui.webs.size
        key("a", "a")
        assertEquals(before, ui.webs.size)
        send(AppEvent.Back(WEB))
        assertFalse(Sensor.KEYS in ui.subscriptions)
    }

    @Test
    fun `typing goes straight into the field with the cursor`() {
        started()
        ui.keyboard = true
        enter("lite.duckduckgo.com/lite/")
        ui.questionAnswered()
        send(AppEvent.ImageClick(WEB, PICTURE, 200, 40))
        web(WebState.READY, "https://lite.duckduckgo.com/lite/", "DuckDuckGo", field = WebField("Suche"))
        // With a keyboard the wearer types on it; the watch does not ask.
        assertNull(ui.question)
        assertEquals("Tippen geht ins Feld, Enter sendet", ui.toasts.last())
        key("K", "K", shift = true)
        assertEquals(WebAction.Insert("K"), ui.lastWeb(PICTURE))
        key("ä", "ä")
        assertEquals(WebAction.Insert("ä"), ui.lastWeb(PICTURE))
        key(Keys.BACKSPACE)
        assertEquals(WebAction.Key(Keys.BACKSPACE), ui.lastWeb(PICTURE))
        key(Keys.ARROW_LEFT, shift = true)
        assertEquals(WebAction.Key(Keys.ARROW_LEFT, shift = true), ui.lastWeb(PICTURE))
        key("a", ctrl = true)
        assertEquals(WebAction.Key("a", ctrl = true), ui.lastWeb(PICTURE))
        key(Keys.ENTER)
        assertEquals(WebAction.Key(Keys.ENTER), ui.lastWeb(PICTURE))
        // The menu still asks, on the glasses with a keyboard.
        send(AppEvent.Menu(BrowserApp.MENU_TYPE))
        assertEquals(ASK_FIELD, ui.question!!.first)
    }

    @Test
    fun `browser shortcuts on the page`() {
        started()
        enter("srf.ch")
        web(WebState.READY, "https://www.srf.ch/meteo", "SRF Meteo", canForward = true)
        ui.questionAnswered()
        key("l", ctrl = true)
        assertEquals(ASK_ADDRESS, ui.question!!.first)
        ui.questionAnswered()
        key(Keys.F6)
        assertEquals(ASK_ADDRESS, ui.question!!.first)
        key("r", ctrl = true)
        assertEquals(WebAction.Reload, ui.lastWeb(PICTURE))
        key(Keys.F5)
        assertEquals(WebAction.Reload, ui.lastWeb(PICTURE))
        key(Keys.ARROW_RIGHT, alt = true)
        assertEquals(WebAction.Forward, ui.lastWeb(PICTURE))
        key("d", ctrl = true)
        assertEquals("Lesezeichen: SRF Meteo", ui.toasts.last())
        key("D", shift = true, ctrl = true)
        assertEquals("Lesezeichen entfernt", ui.toasts.last())
        // In a field the shortcuts stay shortcuts.
        web(WebState.READY, "https://www.srf.ch/meteo", "SRF Meteo", field = WebField("Suche"))
        key("r", ctrl = true)
        assertEquals(WebAction.Reload, ui.lastWeb(PICTURE))
    }
}

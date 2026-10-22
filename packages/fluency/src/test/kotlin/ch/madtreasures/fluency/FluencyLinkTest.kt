package ch.madtreasures.fluency

import ch.madtreasures.fluency.FluencyApp.Companion.LIVE
import ch.madtreasures.fluency.FluencyApp.Companion.START
import ch.madtreasures.fluency.Harness.Companion.error
import ch.madtreasures.fluency.Harness.Companion.gone
import ch.madtreasures.fluency.Harness.Companion.state
import ch.madtreasures.fluency.Harness.Companion.utt
import ch.madtreasures.fluency.Harness.Companion.welcome
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.valueOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app with a connection to Fluency (PLATTFORM.md): the protocol from hello to the captions, pausing,
 * hiding, losing the connection. [FakePhoneLink] stands for the watch app's link ([WatchPhoneLink],
 * tested in [WatchPhoneLinkTest]).
 */
class FluencyLinkTest {
    private val link = FakePhoneLink()
    private val h = Harness(link)
    private val ui get() = h.ui

    private fun connected() {
        h.started()
        h.link(LinkEvent.Connected)
        h.fluency(welcome())
    }

    /** Live page open, Fluency listening. */
    private fun live() {
        connected()
        h.click(START, FluencyApp.GO)
        h.fluency(state(running = true, re = link.last("start")["n"]!!.jsonPrimitive.int))
    }

    @Test
    fun `the app connects at start, says hello and shows Fluency's version`() {
        h.started()
        assertEquals(1, link.opened)
        assertEquals("verbindet …", ui.page(START).valueOf(FluencyApp.PHONE))
        assertTrue(ui.page(START).block(FluencyApp.MISSING_NOTE) == null)
        h.link(LinkEvent.Connected)
        assertEquals(listOf("hello"), link.types)
        assertEquals(JsonPrimitive(Protocol.NAME), link.last("hello")["proto"])
        assertEquals(JsonPrimitive("1.1.0"), link.last("hello")["app"])
        h.fluency(welcome(app = "1.9.0"))
        assertEquals("Fluency 1.9.0", ui.page(START).valueOf(FluencyApp.PHONE))
    }

    @Test
    fun `translating starts Fluency with the settings and the watch's microphone`() {
        connected()
        h.click(START, FluencyApp.GO)
        assertEquals(LIVE, ui.current!!.id)
        assertEquals(setOf(Sensor.KEYS), ui.subscriptions)
        val start = link.last("start")
        assertEquals("watch", start["source"]!!.jsonPrimitive.content)
        assertEquals("auto", start["from"]!!.jsonPrimitive.content)
        assertEquals("de-CH", start["to"]!!.jsonPrimitive.content)
        assertFalse(start["speak"]!!.jsonPrimitive.boolean)
        assertTrue(start["partials"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(true), link.microphone)
        assertEquals("Startet …", h.name)
        assertEquals("Fluency startet …", h.original)

        h.fluency(state(running = true, re = start["n"]!!.jsonPrimitive.int, note = "Lädt die Modelle …"))
        assertEquals("Live", h.name)
        assertEquals("Lädt die Modelle …", h.original)
        h.fluency(state(running = true, re = start["n"]!!.jsonPrimitive.int))
        assertEquals("Fluency hört zu (Uhr-Mikrofon) …", h.original)
    }

    @Test
    fun `with the phone's microphone the watch's stays off`() {
        h.started()
        h.click(START, FluencyApp.SOURCE)
        h.link(LinkEvent.Connected)
        h.fluency(welcome())
        h.click(START, FluencyApp.GO)
        assertEquals("phone", link.last("start")["source"]!!.jsonPrimitive.content)
        assertTrue(link.microphone.isEmpty())
    }

    /** A message from Fluency half a second after the last one: the page is free to change. */
    private fun later(json: String) {
        h.advance(500)
        h.fluency(json)
    }

    @Test
    fun `utterances become the caption and the original line, finished ones go to the history`() {
        live()
        later(utt(1, "Where is", "Wo ist"))
        assertEquals("Wo ist", h.heading)
        assertEquals("Where is", h.original)
        later(utt(1, "Where is the station?", "Wo ist der Bahnhof?", final = true, from = "en"))
        assertEquals("Wo ist der Bahnhof?", h.heading)
        assertEquals("EN: Where is the station?", h.original)
        val history = ui.page(FluencyApp.HISTORY_PAGE).blocks.filter { it.id.startsWith(FluencyApp.HISTORY_TEXT) }
        assertEquals("Wo ist der Bahnhof?", (history.single() as Block.Text).text)

        // A new utterance without text yet leaves the finished one in view.
        later(utt(2, "", ""))
        assertEquals("Wo ist der Bahnhof?", h.heading)
        later(utt(2, "Thank", ""))
        assertEquals("Wo ist der Bahnhof?", h.heading)
        assertEquals("Thank", h.original)
        later(utt(2, "Thank you", "Danke"))
        assertEquals("Wo ist der Bahnhof?\nDanke", h.heading)

        // Dropped (it was noise): gone from the caption.
        later(gone(2))
        assertEquals("Wo ist der Bahnhof?", h.heading)
    }

    @Test
    fun `a finished sentence shows at once, a partial one waits for its turn`() {
        live()
        later(utt(1, "Good", "Gut"))
        assertEquals("Gut", h.heading)
        h.now += 50
        h.fluency(utt(1, "Good morning", "Guten Morgen"))
        assertEquals("Gut", h.heading)
        h.now += 50
        h.fluency(utt(1, "Good morning!", "Guten Morgen!", final = true))
        assertEquals("Guten Morgen!", h.heading)
        assertFalse(FluencyApp.RENDER_TIMER in ui.timers)
    }

    @Test
    fun `pause stops Fluency and the microphone, a tap starts both again`() {
        live()
        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.LEFT))
        assertEquals("stop", link.types.last())
        assertEquals(listOf(true, false), link.microphone)
        assertEquals("Pause", h.name)
        assertEquals("Weiter", ui.menu.first().text)

        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.LEFT))
        assertEquals("start", link.types.last())
        assertEquals(listOf(true, false, true), link.microphone)
        assertEquals("Startet …", h.name)
        // The requests are numbered: start, stop, start.
        assertEquals(listOf(1, 2, 3), link.messages.filter { it.type != "hello" }.map { it["n"]!!.jsonPrimitive.int })
    }

    @Test
    fun `an answer to an older request does not undo a newer one`() {
        live()
        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        // Fluency answers the stop (2) only now; the start (3) is on its way.
        h.fluency(state(running = false, re = 2))
        assertEquals("Startet …", h.name)
        assertEquals("Pause", ui.menu.first().text)
        h.fluency(state(running = true, re = 3))
        assertEquals("Live", h.name)
    }

    @Test
    fun `when Fluency stops by itself, the app pauses and shows why`() {
        live()
        h.fluency(state(running = false, note = "Kein Modell für Englisch → Japanisch"))
        assertEquals("Pause", h.name)
        assertEquals("Kein Modell für Englisch → Japanisch", h.original)
        assertEquals(false, link.microphone.last())
        h.fluency(error("Kein Modell für Englisch → Japanisch"))
        assertEquals("Kein Modell für Englisch → Japanisch", ui.toasts.last())
        assertTrue(ui.logs.any { it.contains("no_model") })
    }

    @Test
    fun `hidden, Fluency rests, and visible again it listens again`() {
        live()
        h.send(AppEvent.Hidden)
        assertEquals("stop", link.types.last())
        assertEquals(false, link.microphone.last())
        h.send(AppEvent.Visible)
        assertEquals("start", link.types.last())
        assertEquals(true, link.microphone.last())
    }

    @Test
    fun `a welcome while hidden waits until the app is visible`() {
        h.started()
        h.link(LinkEvent.Connected)
        h.click(START, FluencyApp.GO)
        h.send(AppEvent.Hidden)
        h.fluency(welcome())
        assertFalse("start" in link.types)
        h.send(AppEvent.Visible)
        assertEquals("start", link.types.last())
    }

    @Test
    fun `a lost connection says so, a tap tries again`() {
        live()
        h.link(LinkEvent.Disconnected("Handy außer Reichweite"))
        assertEquals("Getrennt", h.name)
        assertEquals("Verbindung zum Handy unterbrochen", ui.toasts.last())
        assertEquals("getrennt", ui.page(START).valueOf(FluencyApp.PHONE))
        assertEquals("Handy außer Reichweite Tippen: neu versuchen", h.original)

        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertEquals(2, link.opened)
        assertEquals("Verbindet …", h.name)
        h.link(LinkEvent.Connected)
        h.fluency(welcome())
        assertEquals("start", link.types.last())
    }

    @Test
    fun `back from the live page stops Fluency, closing the app ends the connection`() {
        live()
        h.send(AppEvent.Back(LIVE))
        assertEquals("stop", link.types.last())
        assertEquals(false, link.microphone.last())
        assertTrue(ui.subscriptions.isEmpty())
        h.send(AppEvent.Stop)
        assertTrue(link.closed)
    }

    @Test
    fun `swapped languages and changed settings reach Fluency while it listens`() {
        connected()
        ui.show(FluencyApp.LANGUAGE_PAGE)
        ui.show(FluencyApp.FROM_PAGE)
        h.click(FluencyApp.FROM_PAGE, "von_en")
        assertFalse("config" in link.types)
        ui.replace(START)
        h.click(START, FluencyApp.GO)
        h.fluency(state(running = true, re = 1))
        assertEquals("en", link.last("start")["from"]!!.jsonPrimitive.content)

        // With a spoken language set there is something to swap: by key, or in the app menu.
        assertTrue(ui.menu.any { it.text == "Sprachen tauschen" })
        h.send(AppEvent.Key("t", "t"))
        assertEquals("de-CH", link.last("config")["from"]!!.jsonPrimitive.content)
        assertEquals("en", link.last("config")["to"]!!.jsonPrimitive.content)
        assertEquals("Deutsch (Schweiz) → Englisch", ui.toasts.last())
        h.send(AppEvent.Menu(FluencyApp.MENU_SWAP))
        assertEquals("en", link.last("config")["from"]!!.jsonPrimitive.content)

        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.PARTIALS_TOGGLE, false))
        assertFalse(link.last("config")["partials"]!!.jsonPrimitive.boolean)
        h.send(AppEvent.Toggle(FluencyApp.DISPLAY_PAGE, FluencyApp.SPEAK_TOGGLE, true))
        assertTrue(link.last("config")["speak"]!!.jsonPrimitive.boolean)
        assertEquals(LIVE, ui.current!!.id)
    }

    @Test
    fun `a Fluency with another protocol is not used`() {
        h.started()
        h.link(LinkEvent.Connected)
        h.fluency(welcome(proto = "fluency-g2@2"))
        assertEquals("passt nicht", ui.page(START).valueOf(FluencyApp.PHONE))
        h.click(START, FluencyApp.GO)
        assertFalse("start" in link.types)
        assertEquals("Passt nicht", h.name)
        h.send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertEquals("Fluency auf dem Handy passt nicht zu dieser App", ui.toasts.last())
    }

    @Test
    fun `messages the app does not know are logged and change nothing`() {
        live()
        h.fluency("""{"t":"neu","x":1}""")
        h.fluency("kein JSON")
        assertEquals(2, ui.logs.count { it.startsWith("Fluency: unbekannte Nachricht") })
        assertEquals("Live", h.name)
    }
}

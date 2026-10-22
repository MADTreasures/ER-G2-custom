package ch.madtreasures.fluency

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.valueOf
import ch.madtreasures.g2watch.input.KeyPress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app in the real app host of the watch app (fakes for the screen and the platform, virtual time):
 * the temple works the start page, the live page takes gestures and keys, the host's timers drive the
 * Probelauf, back leaves the live page, and the host refuses none of the app's commands.
 */
class FluencyHostTest {
    private val scheduler = FakeScheduler()
    private val ports = FakePorts()
    private val link = FakePhoneLink()
    private var withLink = false

    /** The real link through the watch app ([WatchPhoneLink]), to the host's fake phone engine. */
    private var throughWatch = false

    /** The app the host made, and the context it gave it. */
    private var app: FluencyApp? = null
    private var context: AppContext? = null

    private val host = AppHost(
        scheduler, FakeScreen(), FakeText(), ports,
        listOf({
            FluencyApp(
                clock = { scheduler.now },
                linkFor = { ui ->
                    context = ui
                    when {
                        throughWatch -> WatchPhoneLink(ui)
                        withLink -> link
                        else -> NoPhoneLink
                    }
                },
            ).also { app = it }
        }),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private val id = "ch.madtreasures.fluency"

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun key(press: KeyPress) {
        host.key(press)
        settle()
    }

    /** What the link reports, delivered on the app thread as the watch app will do it. */
    private fun fromLink(event: LinkEvent) {
        scheduler.post { app!!.onLink(event, context!!) }
        settle()
    }

    private fun page() = host.currentPageOf(id)!!

    private fun caption() = (page().block(FluencyApp.NOW) as Block.Heading).text

    private fun launch() {
        // The wearer allowed what the app asks for on its first start (02 §8).
        FluencyApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        host.launch("watch:$id")
        settle()
        assertEquals(id, host.state.value.visible)
        assertEquals(FluencyApp.START, host.state.value.page)
    }

    private fun noComplaints() =
        assertTrue(ports.logs.toString(), ports.logs.none { "abgelehnt" in it || "dauerte" in it || "abgestürzt" in it })

    @Test
    fun `the temple starts the Probelauf, the live page takes gestures, back leaves it`() {
        launch()
        // The first button has the focus; a swipe goes on to "Probelauf".
        assertEquals(FocusTarget(FluencyApp.GO), host.state.value.focus)
        temple(GestureKind.SCROLL_DOWN)
        assertEquals(FocusTarget(FluencyApp.DEMO_BUTTON), host.state.value.focus)
        temple(GestureKind.CLICK)
        assertEquals(FluencyApp.LIVE, host.state.value.page)
        assertEquals(InputMode.GESTURES, host.inputMode.value)

        scheduler.advanceBy(Demo.FIRST_MS + Demo.PARTIAL_MS)
        settle()
        assertTrue(caption(), caption().startsWith("Guten"))

        // A tap pauses; the Probelauf stands still.
        temple(GestureKind.CLICK)
        assertEquals("Pause", page().name)
        val paused = caption()
        scheduler.advanceBy(5_000)
        settle()
        assertEquals(paused, caption())

        // A double tap is back: to the start page, with the pointer again.
        temple(GestureKind.DOUBLE_CLICK)
        assertEquals(FluencyApp.START, host.state.value.page)
        assertEquals(InputMode.POINTER, host.inputMode.value)
        noComplaints()
    }

    @Test
    fun `keys reach the live page, Escape stays the host's back`() {
        launch()
        temple(GestureKind.SCROLL_DOWN)
        temple(GestureKind.CLICK)
        key(KeyPress("+", "+"))
        assertEquals(HeadingSize.GROSS, (page().block(FluencyApp.NOW) as Block.Heading).size)
        key(KeyPress(Keys.SPACE, " "))
        assertEquals("Pause", page().name)
        key(KeyPress(Keys.ESCAPE))
        assertEquals(FluencyApp.START, host.state.value.page)
        // On the start page the arrows move the focus again (the host's), on from "Probelauf".
        assertEquals(FocusTarget(FluencyApp.DEMO_BUTTON), host.state.value.focus)
        key(KeyPress(Keys.ARROW_DOWN))
        assertEquals(FocusTarget(FluencyApp.SOURCE), host.state.value.focus)
        noComplaints()
    }

    @Test
    fun `with a link, captions from Fluency reach the glasses through the host`() {
        withLink = true
        launch()
        fromLink(LinkEvent.Connected)
        fromLink(LinkEvent.Message(Harness.welcome()))
        temple(GestureKind.CLICK)
        assertEquals(FluencyApp.LIVE, host.state.value.page)
        assertEquals("start", link.types.last())
        assertEquals(listOf(true), link.microphone)
        fromLink(LinkEvent.Message(Harness.state(running = true, re = 1)))
        scheduler.advanceBy(500)
        fromLink(LinkEvent.Message(Harness.utt(1, "Where is the station?", "Wo ist der Bahnhof?", final = true, from = "en")))
        assertEquals("Live", page().name)
        assertEquals("Wo ist der Bahnhof?", caption())
        assertEquals("EN: Where is the station?", (page().block(FluencyApp.ORIGINAL) as Block.Text).text)

        // Closing the app from its menu ends the connection.
        host.gesture(Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()
        host.gesture(Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()
        scheduler.advanceBy(2_000)
        assertTrue(link.closed)
        noComplaints()
    }

    @Test
    fun `through the watch app's link, G2 Handy's line carries hello, start, the microphone and the end`() {
        throughWatch = true
        launch()
        val line = ports.phone.lines.single()
        assertEquals(Triple(id, "1.1.0", WatchPhoneLink.PARTNER), Triple(line.app, line.version, line.partner))
        line.state(PhoneState.CONNECTED)
        settle()
        assertEquals(listOf("hello"), line.sent.map { Json.parseToJsonElement(it).jsonObject.type })
        line.state(PhoneState.MESSAGE, text = Harness.welcome(app = "1.9.0"))
        settle()
        assertEquals("Fluency 1.9.0", page().valueOf(FluencyApp.PHONE))

        temple(GestureKind.CLICK) // "Übersetzen"
        assertEquals(FluencyApp.LIVE, host.state.value.page)
        assertEquals("start", Json.parseToJsonElement(line.sent.last()).jsonObject.type)
        assertEquals(listOf(true), line.mic)
        line.state(PhoneState.MESSAGE, text = Harness.state(running = true, re = 1))
        line.state(PhoneState.MESSAGE, text = Harness.utt(1, "Where is the station?", "Wo ist der Bahnhof?", final = true, from = "en"))
        settle()
        assertEquals("Wo ist der Bahnhof?", caption())

        // Hidden, Fluency rests and the microphone with it; the connection stays.
        host.openLauncher()
        settle()
        assertEquals("stop", Json.parseToJsonElement(line.sent.last()).jsonObject.type)
        assertEquals(listOf(true, false), line.mic)
        assertFalse(line.closed)
        host.launch("watch:$id")
        settle()
        assertEquals(listOf(true, false, true), line.mic)

        // Fluency goes away on the phone: the app hears it from the watch app.
        line.state(PhoneState.DISCONNECTED, message = "Fluency wurde auf dem Handy beendet")
        settle()
        assertEquals("Getrennt", page().name)
        assertTrue(line.closed)
        noComplaints()
    }
}

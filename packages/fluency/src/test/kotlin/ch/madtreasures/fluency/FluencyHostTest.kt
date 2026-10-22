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
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.input.KeyPress
import org.junit.Assert.assertEquals
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
                    if (withLink) link else NoPhoneLink
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
}

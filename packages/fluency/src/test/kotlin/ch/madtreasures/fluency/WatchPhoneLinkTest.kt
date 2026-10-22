package ch.madtreasures.fluency

import ch.madtreasures.fluency.FluencyApp.Companion.GO
import ch.madtreasures.fluency.FluencyApp.Companion.LIVE
import ch.madtreasures.fluency.FluencyApp.Companion.PHONE
import ch.madtreasures.fluency.FluencyApp.Companion.START
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.PhoneAction
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.apps.valueOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connection to Fluency through the watch app (interface version 4), as the app uses it by default. */
class WatchPhoneLinkTest {
    private val app = FluencyApp(clock = { 0L })
    private val ui = FakeAppContext.forApp(app)

    private fun send(event: AppEvent) = app.onEvent(event, ui)

    private fun started() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    private fun sentTypes() = ui.phoneMessages().map { Json.parseToJsonElement(it).jsonObject.type }

    @Test
    fun `the link speaks to Fluency through the watch app`() {
        val link = WatchPhoneLink(ui)
        assertTrue(link.available)
        link.open()
        ui.phoneEvent(PhoneState.CONNECTED) // what the watch app hears from G2 Handy
        link.send("""{"t":"hello"}""")
        link.microphone(true)
        link.microphone(false)
        link.close()
        assertEquals(
            listOf(
                PhoneAction.Connect("ch.madtreasures.fluency"),
                PhoneAction.Send("""{"t":"hello"}"""),
                PhoneAction.Microphone(true),
                PhoneAction.Microphone(false),
                PhoneAction.Disconnect,
            ),
            ui.phones,
        )
    }

    @Test
    fun `the watch app's phone events are link events`() {
        assertEquals(LinkEvent.Connected, WatchPhoneLink.event(AppEvent.Phone(PhoneState.CONNECTED)))
        assertEquals(LinkEvent.Message("""{"t":"state"}"""), WatchPhoneLink.event(AppEvent.Phone(PhoneState.MESSAGE, """{"t":"state"}""")))
        assertEquals(LinkEvent.Disconnected("weg"), WatchPhoneLink.event(AppEvent.Phone(PhoneState.DISCONNECTED, message = "weg")))
        assertEquals(LinkEvent.Disconnected(null), WatchPhoneLink.event(AppEvent.Phone(PhoneState.DISCONNECTED)))
    }

    @Test
    fun `the app asks for the phone and the watch's microphone, and connects at its start`() {
        assertEquals(setOf(Permission.PHONE, Permission.WATCH_MIC), app.manifest.permissions)
        started()
        assertEquals(listOf<PhoneAction>(PhoneAction.Connect(WatchPhoneLink.PARTNER)), ui.phones)
        assertEquals("verbindet …", ui.page(START).valueOf(PHONE))

        send(ui.phoneEvent(PhoneState.CONNECTED))
        assertEquals(listOf("hello"), sentTypes())
        send(ui.phoneEvent(PhoneState.MESSAGE, Harness.welcome(app = "1.9.0")))
        assertEquals("Fluency 1.9.0", ui.page(START).valueOf(PHONE))

        send(AppEvent.Click(START, GO))
        assertEquals("start", sentTypes().last())
        assertTrue(ui.phoneMicOn())

        send(AppEvent.Hidden)
        assertEquals("stop", sentTypes().last())
        assertFalse(ui.phoneMicOn())

        send(AppEvent.Stop)
        assertEquals(PhoneAction.Disconnect, ui.phones.last())
    }

    @Test
    fun `when the connection does not come about, the start page says why, and translating tries again`() {
        started()
        send(ui.phoneEvent(PhoneState.DISCONNECTED, message = "Kein Handy mit G2 Handy erreichbar"))
        assertEquals("getrennt", ui.page(START).valueOf(PHONE))
        assertEquals("Kein Handy mit G2 Handy erreichbar", ui.toasts.last())

        send(AppEvent.Click(START, GO))
        assertEquals(LIVE, ui.current!!.id)
        assertEquals(listOf(PhoneAction.Connect(WatchPhoneLink.PARTNER), PhoneAction.Connect(WatchPhoneLink.PARTNER)), ui.phones)
        assertEquals("Verbindet …", ui.page(LIVE).name)
    }
}

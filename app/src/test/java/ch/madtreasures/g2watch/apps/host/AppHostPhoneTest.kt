package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.PhoneAction
import ch.madtreasures.g2watch.apps.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apps on the phone (interface 4, [AppContext.phone]) in the app host, with a fake engine and virtual time. */
class AppHostPhoneTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val text = FakeText()
    private val ports = FakePorts()
    private val created = mutableListOf<ScriptedApp>()
    private var context: AppContext? = null

    private val pages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        context = ui
        if (event == AppEvent.Start) {
            ui.definePages(listOf(Page("start", "Start", listOf(Block.Text("t", "Hallo")))))
            ui.show("start")
        }
    }

    private val all = setOf(Permission.PHONE, Permission.WATCH_MIC)

    private fun app(id: String = ID, permissions: Set<Permission> = all): () -> G2App = {
        ScriptedApp(AppManifest(id, id.substringAfterLast('.'), "1.0.0", permissions = permissions), pages).also { created += it }
    }

    private fun host(vararg apps: () -> G2App) = AppHost(
        scheduler, screen, text, ports, apps.toList(),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.start(id: String = ID, granted: Set<Permission> = all): ScriptedApp {
        ports.answers["$id@1.0.0"] = granted
        launch("watch:$id")
        settle()
        return created.last()
    }

    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    /** Starts the app and connects it to Fluency; the engine accepts. */
    private fun AppHost.connected(): ScriptedApp {
        val app = start()
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        line.state(PhoneState.CONNECTED)
        settle()
        return app
    }

    private val line get() = ports.phone.lines.last()

    private fun ScriptedApp.phoneEvents() = events.filterIsInstance<AppEvent.Phone>()

    private fun refused(what: String) = ports.logs.any { "abgelehnt" in it && what in it }

    @Test
    fun `an app connects to its partner, and the messages go both ways`() {
        val host = host(app())
        val app = host.start()
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        assertEquals(1, ports.phone.lines.size)
        assertEquals(Triple(ID, "1.0.0", FLUENCY), Triple(line.app, line.version, line.partner))
        assertTrue(app.phoneEvents().isEmpty()) // not before the phone said so

        line.state(PhoneState.CONNECTED)
        settle()
        assertEquals(listOf(AppEvent.Phone(PhoneState.CONNECTED)), app.phoneEvents())

        asApp { phone(PhoneAction.Send("""{"t":"hello"}""")) }
        assertEquals(listOf("""{"t":"hello"}"""), line.sent)
        line.state(PhoneState.MESSAGE, text = """{"t":"welcome"}""")
        settle()
        assertEquals(AppEvent.Phone(PhoneState.MESSAGE, """{"t":"welcome"}"""), app.phoneEvents().last())
    }

    @Test
    fun `the connection needs its permission, and the app hears that it did not come about`() {
        val host = host(app(permissions = setOf(Permission.PHONE)))
        val app = host.start(granted = emptySet())
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        assertTrue(ports.phone.lines.isEmpty())
        assertTrue(refused("Keine Berechtigung: Verbindung zum Handy"))
        assertEquals(listOf(AppEvent.Phone(PhoneState.DISCONNECTED, message = AppHost.PHONE_DENIED)), app.phoneEvents())
    }

    @Test
    fun `the microphone needs its own permission, and the wearer reads that it is missing`() {
        val host = host(app(permissions = setOf(Permission.PHONE)))
        host.start(granted = setOf(Permission.PHONE))
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        line.state(PhoneState.CONNECTED)
        settle()
        asApp { phone(PhoneAction.Microphone(true)) }
        assertTrue(line.mic.isEmpty())
        assertTrue(refused("Keine Berechtigung: Mikrofon der Uhr"))
        assertTrue(AppHost.MIC_DENIED in text.drawn)
    }

    @Test
    fun `nothing goes to the phone before the connection stands or beyond its limits`() {
        val host = host(app())
        host.start()
        asApp { phone(PhoneAction.Send("zu früh")) }
        assertTrue(refused("Keine Verbindung zum Handy"))
        asApp { phone(PhoneAction.Connect("fluency")) }
        assertTrue(refused("kein Paketname"))
        assertTrue(ports.phone.lines.isEmpty())

        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        asApp { phone(PhoneAction.Microphone(true)) }
        assertTrue(line.mic.isEmpty()) // still connecting
        line.state(PhoneState.CONNECTED)
        settle()
        asApp { phone(PhoneAction.Send("x".repeat(PhoneAction.MAX_TEXT_BYTES + 1))) }
        assertTrue(refused("höchstens 64 KiB"))
        asApp { repeat(25) { phone(PhoneAction.Send("Nachricht $it")) } }
        assertEquals(AppHost.MAX_PHONE_MESSAGES, line.sent.size)
        assertTrue(refused("Höchstens ${AppHost.MAX_PHONE_MESSAGES} Nachrichten je Sekunde"))
        scheduler.advanceBy(1_000)
        asApp { phone(PhoneAction.Send("wieder")) }
        assertEquals("wieder", line.sent.last())
    }

    @Test
    fun `a second connect to the same partner does nothing, another partner replaces the connection`() {
        val host = host(app())
        host.connected()
        val first = line
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        assertEquals(1, ports.phone.lines.size)
        asApp { phone(PhoneAction.Connect("com.example.other")) }
        assertEquals(2, ports.phone.lines.size)
        assertTrue(first.closed)
        // the old line has nothing more to say
        first.state(PhoneState.MESSAGE, text = "spät")
        settle()
        assertFalse(created.last().phoneEvents().any { it.text == "spät" })
    }

    @Test
    fun `the microphone only while the app is on the glasses, and hidden it stays off`() {
        val host = host(app())
        val app = host.connected()
        asApp { phone(PhoneAction.Microphone(true)) }
        assertEquals(listOf(true), line.mic)

        host.openLauncher()
        settle()
        assertEquals(listOf(true, false), line.mic)
        assertFalse(line.closed) // the connection stays
        asApp { phone(PhoneAction.Microphone(true)) }
        assertTrue(refused("solange die App zu sehen ist"))

        host.launch("watch:$ID")
        settle()
        assertTrue(AppEvent.Visible in app.events)
        assertEquals(listOf(true, false), line.mic) // not again by itself
        asApp { phone(PhoneAction.Microphone(true)) }
        assertEquals(listOf(true, false, true), line.mic)
    }

    @Test
    fun `when the phone ends the connection the app hears why, and may connect again`() {
        val host = host(app())
        val app = host.connected()
        line.state(PhoneState.DISCONNECTED, message = "Fluency wurde auf dem Handy beendet")
        settle()
        assertEquals(AppEvent.Phone(PhoneState.DISCONNECTED, message = "Fluency wurde auf dem Handy beendet"), app.phoneEvents().last())
        assertTrue(ports.logs.any { "Fluency wurde auf dem Handy beendet" in it })
        asApp { phone(PhoneAction.Send("weg")) }
        assertTrue(refused("Keine Verbindung zum Handy"))
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        assertEquals(2, ports.phone.lines.size)
    }

    @Test
    fun `a notice of the connection shows on the glasses`() {
        val host = host(app())
        host.connected()
        line.notice("Mikrofon der Uhr: bitte auf der Uhr erlauben")
        settle()
        assertTrue("Mikrofon der Uhr: bitte auf der Uhr erlauben" in text.drawn)
    }

    @Test
    fun `the connection ends with the app, and disconnect ends it at once`() {
        val host = host(app())
        host.connected()
        asApp { phone(PhoneAction.Disconnect) }
        assertTrue(line.closed)
        asApp { phone(PhoneAction.Connect(FLUENCY)) }
        val second = line
        asApp { close() }
        assertTrue(second.closed)
    }

    private companion object {
        const val ID = "ch.test.handy"
        const val FLUENCY = "ch.madtreasures.fluency"
    }
}

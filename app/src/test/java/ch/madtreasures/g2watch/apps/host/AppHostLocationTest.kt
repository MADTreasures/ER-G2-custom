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
import ch.madtreasures.g2watch.apps.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's GPS for the apps (interface 5, [Sensor.LOCATION]) in the app host, with a fake receiver. */
class AppHostLocationTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val text = FakeText()
    private val ports = FakePorts()
    private val created = mutableListOf<ScriptedApp>()
    private var context: AppContext? = null

    private val pages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        context = ui
        if (event == AppEvent.Start) {
            ui.definePages(listOf(Page("start", "Start", listOf(Block.Text("t", "Karte")))))
            ui.show("start")
        }
    }

    private fun app(permissions: Set<Permission> = setOf(Permission.LOCATION)): () -> G2App = {
        ScriptedApp(AppManifest(ID, "Karte", "1.0.0", permissions = permissions), pages).also { created += it }
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

    private fun AppHost.start(granted: Set<Permission> = setOf(Permission.LOCATION)): ScriptedApp {
        ports.answers["$ID@1.0.0"] = granted
        launch("watch:$ID")
        settle()
        return created.last()
    }

    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    private val line get() = ports.location.lines.last()

    private fun ScriptedApp.fixes() = events.filterIsInstance<AppEvent.Location>()

    @Test
    fun `a subscribed app gets the watch's positions, every second by default`() {
        val host = host(app())
        val app = host.start()
        asApp { subscribe(Sensor.LOCATION) }
        assertEquals(1, ports.location.lines.size)
        assertEquals(1_000L, line.intervalMs)
        line.fix(47.3769, 8.5417, acc = 4f, speed = 1.4f, bearing = 90f)
        settle()
        assertEquals(listOf(AppEvent.Location(47.3769, 8.5417, 4f, 1_000L, 1.4f, 90f)), app.fixes())
        assertFalse(ports.logs.any { "noch nicht angeschlossen" in it })
    }

    @Test
    fun `the rate is in seconds, and a new rate starts the receiver over`() {
        val host = host(app())
        host.start()
        asApp { subscribe(Sensor.LOCATION, 5) }
        assertEquals(5_000L, line.intervalMs)
        val first = line
        asApp { subscribe(Sensor.LOCATION, 5) }
        assertEquals(1, ports.location.lines.size) // the same rate changes nothing
        asApp { subscribe(Sensor.LOCATION, 2) }
        assertTrue(first.stopped)
        assertEquals(2_000L, line.intervalMs)
        asApp { subscribe(Sensor.LOCATION, 3_600) }
        assertEquals(AppHost.MAX_LOCATION_SECONDS * 1_000L, line.intervalMs)
    }

    @Test
    fun `without the permission there is no GPS, and the log says why`() {
        val host = host(app())
        host.start(granted = emptySet())
        asApp { subscribe(Sensor.LOCATION) }
        assertTrue(ports.location.lines.isEmpty())
        assertTrue(ports.logs.any { "abgelehnt" in it && "Standort" in it })
    }

    @Test
    fun `hidden the app gets no positions and the receiver rests, back on the glasses it runs again`() {
        val host = host(app())
        val app = host.start()
        asApp { subscribe(Sensor.LOCATION) }
        val first = line
        host.openLauncher()
        settle()
        assertTrue(first.stopped)
        first.fix(1.0, 2.0)
        settle()
        assertTrue(app.fixes().isEmpty())

        host.launch("watch:$ID")
        settle()
        assertEquals(2, ports.location.lines.size)
        line.fix(47.0, 8.0)
        settle()
        assertEquals(1, app.fixes().size)
    }

    @Test
    fun `unsubscribing and ending the app stop the receiver`() {
        val host = host(app())
        host.start()
        asApp { subscribe(Sensor.LOCATION) }
        asApp { unsubscribe(Sensor.LOCATION) }
        assertTrue(line.stopped)
        asApp { subscribe(Sensor.LOCATION) }
        val second = line
        assertFalse(second.stopped)
        asApp { close() }
        assertTrue(second.stopped)
    }

    @Test
    fun `a notice of the receiver shows on the glasses and in the log`() {
        val host = host(app())
        host.start()
        asApp { subscribe(Sensor.LOCATION) }
        line.listener.onNotice("Standort bitte auf der Uhr erlauben")
        settle()
        assertTrue("Standort bitte auf der Uhr erlauben" in text.drawn)
        assertTrue(ports.logs.any { "Standort bitte auf der Uhr erlauben" in it })
    }

    private companion object {
        const val ID = "ch.test.karte"
    }
}

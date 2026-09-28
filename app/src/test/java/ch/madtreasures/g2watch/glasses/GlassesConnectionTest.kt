package ch.madtreasures.g2watch.glasses

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.desktop.AppId
import ch.madtreasures.g2watch.desktop.DesktopController
import com.faceclaw.app.BleProtocol
import com.faceclaw.app.FaceclawBleCommunicatorListener
import com.faceclaw.app.FaceclawDeviceInfoProbeListener
import com.faceclaw.app.SessionLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What the glasses report with the custom firmware this app installs. */
private const val CURRENT = "Faceclaw/${FirmwareRequirement.REQUIRED_REVISION}"

class GlassesConnectionTest {
    // Separate fakes for the main thread and the worker, so tests can see where things run.
    private val main = FakeScheduler()
    private val worker = FakeScheduler()
    private val desktopScheduler = FakeScheduler()

    private inner class FakeProbe(val right: String, val left: String) : FirmwareProbe {
        var listener: FaceclawDeviceInfoProbeListener? = null
        var listening = false
        var closed = false
        var closedOnWorker = false

        override fun start(listener: FaceclawDeviceInfoProbeListener) {
            this.listener = listener
            listening = true
        }

        override fun stopListening() {
            listening = false
        }

        override fun close() {
            closed = true
            closedOnWorker = worker.inTask && !main.inTask
        }
    }

    private inner class FakeSession(val right: String, val left: String) : GlassesSession {
        override val display = FakeDisplay()
        var listener: FaceclawBleCommunicatorListener? = null
        var listening = false
        var screenOn: Boolean? = null
        var closed = false
        var closedOnWorker = false

        override fun start(listener: FaceclawBleCommunicatorListener) {
            this.listener = listener
            listening = true
            // Like Faceclaw's GlassesSessionCore: setListener() reports the initial phase at once
            // (posted to the main thread), before the session has even started connecting.
            main.post { if (listening) listener.onStateChange("disconnected", "Disconnected.") }
        }

        override fun stopListening() {
            listening = false
        }

        override fun setScreenOn(on: Boolean) {
            screenOn = on
        }

        override fun sweepFrameTimings() = Unit

        override fun close() {
            closed = true
            closedOnWorker = worker.inTask && !main.inTask
        }
    }

    private inner class FakeParts : GlassesParts {
        val probes = mutableListOf<FakeProbe>()
        val sessions = mutableListOf<FakeSession>()
        val foreground = mutableListOf<String>()
        var bluetoothMissing = false

        /** Whether every earlier session was already closed when the latest probe was created. */
        var sessionsClosedAtLastProbe = true

        override fun probe(right: String, left: String): FirmwareProbe {
            if (bluetoothMissing) throw IllegalStateException("Bluetooth adapter unavailable")
            sessionsClosedAtLastProbe = sessions.all { it.closed }
            return FakeProbe(right, left).also { probes += it }
        }

        override fun session(right: String, left: String, log: (SessionLogLevel, String) -> Unit): GlassesSession {
            if (bluetoothMissing) throw IllegalStateException("Bluetooth adapter unavailable")
            return FakeSession(right, left).also { sessions += it }
        }

        override fun showForeground(text: String) {
            foreground += "show"
        }

        override fun updateForeground(text: String) {
            foreground += "update"
        }

        override fun stopForeground() {
            foreground += "stop"
        }
    }

    private val desktop = DesktopController(
        FakeText(),
        desktopScheduler,
        nowMs = { desktopScheduler.now },
        now = { LocalDateTime.of(2026, 9, 25, 14, 5) },
    )
    private val parts = FakeParts()
    private var clock = 0L
    private val connection = GlassesConnection(desktop, parts, main, worker, clockMs = { clock })

    private val state get() = connection.state.value

    private fun settle() {
        repeat(4) {
            main.runPending()
            worker.runPending()
            desktopScheduler.runPending()
        }
    }

    /** Lets [ms] pass on the worker, where the delayed steps are scheduled. */
    private fun advance(ms: Long) {
        worker.advanceBy(ms)
        settle()
    }

    private val probe get() = parts.probes.last()

    /** Connects and answers the firmware check with [extension]. */
    private fun checkWith(extension: String, left: String? = LEFT) {
        connection.connect("G2 Test", RIGHT, left)
        settle()
        probe.listener!!.onResult("2.3.0.24", "2.3.0.24", extension)
        settle()
    }

    /** The session after a successful check, started and reporting "connected". */
    private fun connected(): FakeSession {
        checkWith(CURRENT)
        advance(2_000)
        val session = parts.sessions.single()
        session.listener!!.onStateChange("connected", "Connected.")
        settle()
        return session
    }

    @Test
    fun `the session's initial disconnected report does not end it`() {
        checkWith(CURRENT)
        advance(2_000)
        val session = parts.sessions.single()
        settle()
        assertTrue(session.listening)
        assertFalse(session.closed)
        assertEquals(Stage.CONNECTING, state.stage)
        session.listener!!.onStateChange("connecting", "Connecting…")
        session.listener!!.onStateChange("connected", "Connected.")
        settle()
        assertEquals(Stage.CONNECTED, state.stage)
        // Once it was under way, "disconnected" is Faceclaw's own teardown and ends it as before.
        session.listener!!.onStateChange("disconnected", "Disconnected.")
        settle()
        assertEquals(Stage.IDLE, state.stage)
        assertTrue(session.closed)
    }

    @Test
    fun `the check runs first and alone`() {
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        assertEquals(Stage.CHECKING, state.stage)
        val probe = parts.probes.single()
        assertEquals(RIGHT, probe.right)
        assertEquals(LEFT, probe.left)
        assertTrue(parts.sessions.isEmpty())
        assertEquals(listOf("show"), parts.foreground)
    }

    @Test
    fun `stock firmware never gets a session`() {
        checkWith("")
        advance(10_000)
        assertEquals(Stage.INCOMPATIBLE, state.stage)
        assertEquals(FirmwareKind.STOCK, state.firmware?.kind)
        assertTrue(state.detail.contains("Original-Firmware"))
        assertTrue(parts.sessions.isEmpty())
        assertTrue(parts.probes.single().closed)
        assertEquals("stop", parts.foreground.last())
    }

    @Test
    fun `older Faceclaw and foreign firmware never get a session`() {
        for (extension in listOf("Faceclaw/22", "EVENCFW/22 img640", "OtherCFW/3")) {
            checkWith(extension)
            advance(10_000)
            assertEquals(extension, Stage.INCOMPATIBLE, state.stage)
        }
        assertTrue(parts.sessions.isEmpty())
    }

    @Test
    fun `a failed check never gets a session`() {
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        parts.probes.single().listener!!.onError("no response")
        advance(10_000)
        assertEquals(Stage.FAILED, state.stage)
        // German on the page, Faceclaw's own words in the protocol.
        assertTrue(state.detail, state.detail.contains("nicht rechtzeitig geantwortet"))
        assertTrue(connection.log.value.any { it.contains("no response") })
        assertTrue(parts.sessions.isEmpty())
    }

    @Test
    fun `the required Faceclaw revision starts the session after the check`() {
        checkWith(CURRENT)
        assertEquals(Stage.CONNECTING, state.stage)
        assertTrue(parts.sessions.isEmpty())
        advance(2_000)
        val session = parts.sessions.single()
        assertEquals(RIGHT, session.right)
        assertEquals(LEFT, session.left)
        assertTrue(session.listening)
        // The desktop is mirrored to the session's compositor.
        assertTrue(session.display.submitsOf("desktop").isNotEmpty())
        assertTrue(session.display.submitsOf("pointer").isNotEmpty())
    }

    @Test
    fun `compatible firmware without the left arm stops before the session`() {
        checkWith(CURRENT, left = null)
        advance(10_000)
        assertEquals(Stage.FAILED, state.stage)
        assertTrue(parts.sessions.isEmpty())
    }

    @Test
    fun `a check that is no longer wanted is ignored`() {
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        val listener = parts.probes.single().listener!!
        connection.disconnect()
        settle()
        listener.onResult("2.3.0.24", "2.3.0.24", CURRENT)
        advance(10_000)
        assertEquals(Stage.IDLE, state.stage)
        assertTrue(parts.sessions.isEmpty())
    }

    @Test
    fun `connected holds the wake lock and charging releases it`() {
        val session = connected()
        assertEquals(Stage.CONNECTED, state.stage)
        assertEquals(true, session.screenOn)
        session.listener!!.onStateChange("charging", "Charging")
        settle()
        assertEquals(Stage.CHARGING, state.stage)
        assertEquals(false, session.screenOn)
    }

    @Test
    fun `the session reporting other firmware ends it`() {
        val session = connected()
        session.listener!!.onFirmwareInfo("2.3.0.24", "2.3.0.24", "")
        settle()
        assertEquals(Stage.INCOMPATIBLE, state.stage)
        assertFalse(session.listening)
        assertTrue(session.closed)
    }

    @Test
    fun `a session report without data keeps it`() {
        val session = connected()
        session.listener!!.onFirmwareInfo("", "", "")
        settle()
        assertEquals(Stage.CONNECTED, state.stage)
        assertFalse(session.closed)
    }

    @Test
    fun `a temple tap clicks at the pointer`() {
        val session = connected()
        // Pointer onto the "Uhr" tile, then tap the right temple.
        val tile = desktop.layout.tiles.first { it.first == AppId.CLOCK }.second
        desktop.moveBy((tile.x + tile.w / 2 - 320).toFloat(), (tile.y + tile.h / 2 - 240).toFloat())
        desktopScheduler.advanceBy(100)
        val before = session.display.submitsOf("desktop").size
        session.listener!!.onRingEvent(
            "sys-event", "", BleProtocol.EVENT_CLICK, BleProtocol.EVENT_SOURCE_GLASSES_R, 0, 0, 0L, 0, 0, 0,
        )
        settle()
        assertEquals("Tipp (rechter Bügel)", state.lastInput)
        assertEquals(before + 1, session.display.submitsOf("desktop").size)
    }

    @Test
    fun `frame times fill the transfer log, and a new connection starts it afresh`() {
        val session = connected()
        for (ms in listOf(30, 21, 95)) session.listener!!.onFrameMetrics(4, ms, 2)
        // Published at most every half second: the log catches up with the next frame.
        clock += 600
        session.listener!!.onFrameMetrics(4, 42, 2)
        settle()
        assertEquals(4L, state.framesSent)
        assertEquals(42, state.transmitMs)
        val transfer = state.transfer!!
        assertEquals(listOf(30, 21, 95, 42), transfer.recent)
        assertEquals(42, transfer.lastMs)
        assertEquals(21, transfer.minMs)
        assertEquals(95, transfer.maxMs)
        assertEquals(47, transfer.avgMs)

        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        assertEquals(null, state.transfer)
    }

    @Test
    fun `battery goes to the state and the desktop`() {
        val session = connected()
        session.listener!!.onBatteryState(81, 1, -1, -1)
        settle()
        assertEquals(81, state.battery)
        assertTrue(state.charging)
        // An unavailable reading keeps the last level.
        session.listener!!.onBatteryState(-1, 0, -1, -1)
        settle()
        assertEquals(81, state.battery)
    }

    @Test
    fun `disconnect closes the session and ends the foreground service`() {
        val session = connected()
        connection.disconnect()
        settle()
        assertEquals(Stage.IDLE, state.stage)
        assertFalse(session.listening)
        assertTrue(session.closed)
        assertEquals("stop", parts.foreground.last())
        // Detached: the desktop no longer sends to the old session.
        val sent = session.display.submits.size
        desktop.click()
        desktop.moveBy(50f, 0f)
        desktopScheduler.advanceBy(100)
        assertEquals(sent, session.display.submits.size)
    }

    @Test
    fun `letting go for firmware calls back only after the session is closed`() {
        val session = connected()
        var released = 0
        var closedWhenReleased = false
        connection.releaseForFirmware {
            released++
            closedWhenReleased = session.closed
        }
        main.runPending()
        assertEquals(0, released) // the worker has not closed the session yet
        settle()
        assertEquals(1, released)
        assertTrue(closedWhenReleased)
        assertTrue(session.closedOnWorker)
        assertEquals(Stage.IDLE, state.stage)
        assertEquals("stop", parts.foreground.last())
    }

    @Test
    fun `letting go for firmware during the check closes the probe and always calls back`() {
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        val p = probe
        var released = 0
        connection.releaseForFirmware { released++ }
        // Something else starts meanwhile: the callback still comes.
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        assertEquals(1, released)
        assertTrue(p.closed)
        assertFalse(p.listening)
    }

    @Test
    fun `losing the pairing fails the connection`() {
        val session = connected()
        session.listener!!.onStateChange("unpaired", "not paired")
        settle()
        assertEquals(Stage.FAILED, state.stage)
        assertTrue(state.detail.contains("nicht mehr mit der Brille gekoppelt"))
        assertTrue(session.closed)
    }

    @Test
    fun `Bluetooth waits never block the main thread`() {
        // Faceclaw's BLE manager holds its lock during GATT waits of up to 5 s; closing the probe
        // or the session has to wait for it, so both happen on the worker.
        checkWith(CURRENT)
        assertTrue(probe.closed)
        assertTrue("probe closed on the main thread", probe.closedOnWorker)
        advance(2_000)
        val session = parts.sessions.single()
        connection.disconnect()
        settle()
        assertTrue(session.closed)
        assertTrue("session closed on the main thread", session.closedOnWorker)
    }

    @Test
    fun `cancelling a running check silences it at once and closes it on the worker`() {
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        connection.disconnect()
        assertFalse(probe.listening)
        assertFalse(probe.closed)
        settle()
        assertTrue(probe.closedOnWorker)
        assertEquals(Stage.IDLE, state.stage)
    }

    @Test
    fun `a new check starts only after the old session is closed`() {
        connected()
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        assertEquals(2, parts.probes.size)
        assertTrue(parts.sessionsClosedAtLastProbe)
    }

    @Test
    fun `no Bluetooth adapter fails instead of crashing`() {
        parts.bluetoothMissing = true
        connection.connect("G2 Test", RIGHT, LEFT)
        settle()
        assertEquals(Stage.FAILED, state.stage)
        assertTrue(parts.sessions.isEmpty())
        assertEquals("stop", parts.foreground.last())
    }

    @Test
    fun `a session that cannot be created fails`() {
        checkWith(CURRENT)
        parts.bluetoothMissing = true
        advance(2_000)
        assertEquals(Stage.FAILED, state.stage)
        assertTrue(parts.sessions.isEmpty())
    }

    @Test
    fun `reconnecting releases the wake lock until connected again`() {
        val session = connected()
        session.listener!!.onStateChange("retrying", "Reconnecting...")
        settle()
        assertEquals(Stage.RECONNECTING, state.stage)
        assertEquals(false, session.screenOn)
        session.listener!!.onStateChange("connected", "Connected.")
        settle()
        assertEquals(true, session.screenOn)
    }

    @Test
    fun `a session that ends itself is cleaned up`() {
        val session = connected()
        session.listener!!.onStateChange("disconnected", "Disconnected.")
        settle()
        assertEquals(Stage.IDLE, state.stage)
        assertTrue(session.closed)
        assertEquals("stop", parts.foreground.last())
    }

    private companion object {
        const val RIGHT = "AA:BB:CC:DD:EE:01"
        const val LEFT = "AA:BB:CC:DD:EE:02"
    }
}

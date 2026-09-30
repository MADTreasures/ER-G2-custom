package ch.madtreasures.g2watch.glasses

import android.content.Context
import androidx.annotation.MainThread
import ch.madtreasures.g2watch.MainScheduler
import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.ThreadScheduler
import ch.madtreasures.g2watch.apps.host.InputRouter
import ch.madtreasures.g2watch.desktop.DesktopController
import com.faceclaw.app.FaceclawBleCommunicatorListener
import com.faceclaw.app.FaceclawDeviceInfoProbeListener
import com.faceclaw.app.FrameTimingsCore
import com.faceclaw.app.SessionLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The connection to one pair of glasses, in two steps:
 *
 * 1. **Check.** Faceclaw's device-info probe connects the way the Even app does, pairs if the
 *    watch is not paired yet and reads the firmware versions. It changes no setting and shows
 *    nothing on the glasses.
 * 2. **Session.** Only if the glasses reported Faceclaw firmware at
 *    exactly [FirmwareRequirement.REQUIRED_REVISION] does Faceclaw's session core connect, and
 *    the desktop is mirrored to it.
 *
 * With any other firmware the app stops after step 1 and says why. It never writes firmware;
 * that is [ch.madtreasures.g2watch.firmware.WatchFirmwareInstaller]'s job, which first asks this
 * connection to let go of the glasses ([releaseForFirmware]).
 *
 * Public methods and all callbacks run on [main]. Blocking Faceclaw calls run on [worker], one
 * after the other: closing the probe waits for a GATT operation in flight, closing a session
 * for its cleanup message and its worker. So a new connection starts only after the previous
 * one has released the Bluetooth links, and the watch UI never waits for Bluetooth.
 */
class GlassesConnection internal constructor(
    private val desktop: DesktopController,
    private val parts: GlassesParts,
    private val main: Scheduler,
    private val worker: Scheduler,
    /** Milliseconds for pacing the frame metrics; tests pass their own. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(context: Context, desktop: DesktopController) :
        this(desktop, FaceclawParts(context), MainScheduler(), ThreadScheduler("G2Watch-connection"))

    private val _state = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Newest last, at most [LOG_LINES]. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // Main thread only. Every connect, disconnect and failure starts a new generation, and
    // callbacks that belong to an older one are ignored.
    private var generation = 0
    private var target: Target? = null
    private var probe: FirmwareProbe? = null
    private var active: Active? = null
    private var framesSent = 0L
    private var lastMetricsAtMs = 0L
    private val recentTransmitMs = ArrayDeque<Int>()

    /** Temple and ring events to gestures (03 §5.1), with the ring's duplicates dropped. */
    private val input = InputRouter()

    private class Target(val title: String, val right: String, val left: String?)

    private class Active(val session: GlassesSession) {
        /** Read by the frame-timing sweep on the worker thread. */
        @Volatile
        var closed = false
    }

    /** Checks the firmware of the glasses with these arms and connects if it fits. */
    @MainThread
    fun connect(title: String, right: String, left: String?) {
        val gen = ++generation
        releaseProbe()
        releaseSession()
        target = Target(title, right, left?.takeIf { it.isNotBlank() && !it.equals(right, ignoreCase = true) })
        framesSent = 0
        recentTransmitMs.clear()
        setState(GlassesState(stage = Stage.CHECKING, title = title, detail = "Warte auf die Brille …"))
        log("Verbinde mit $title (R $right" + (left?.let { ", L $it" } ?: "") + ")")
        desktop.updateStatus { it.copy(connection = Stage.CHECKING.label) }
        parts.showForeground("Firmware wird geprüft")
        // Queued behind any teardown that is still running, so the old links are closed first.
        worker.post { main.post { if (gen == generation) startProbe(gen) } }
    }

    @MainThread
    fun disconnect() {
        val gen = ++generation
        releaseProbe()
        val hadSession = releaseSession()
        if (hadSession) log("Getrennt auf Wunsch")
        setState(_state.value.copy(stage = if (hadSession) Stage.DISCONNECTING else Stage.IDLE, detail = ""))
        afterTeardown(gen) { setState(_state.value.copy(stage = Stage.IDLE)) }
    }

    /**
     * Lets go of the glasses for a firmware transfer: ends the probe and the session like
     * [disconnect], and calls [released] on [main] once their Bluetooth links are closed (the
     * worker ran everything queued before). Unlike [disconnect] it always calls back, even if
     * something else starts a new generation meanwhile, so the transfer never waits forever.
     */
    @MainThread
    fun releaseForFirmware(released: () -> Unit) {
        val gen = ++generation
        releaseProbe()
        val hadSession = releaseSession()
        if (hadSession) log("Getrennt für die Firmware-Übertragung")
        setState(_state.value.copy(stage = if (hadSession) Stage.DISCONNECTING else Stage.IDLE, detail = "Firmware-Übertragung"))
        worker.post {
            main.post {
                if (gen == generation) {
                    setState(_state.value.copy(stage = Stage.IDLE))
                    desktop.updateStatus { it.copy(connection = Stage.IDLE.label) }
                    parts.stopForeground()
                }
                released()
            }
        }
    }

    // --- Step 1: firmware check -----------------------------------------------------------------

    private fun startProbe(gen: Int) {
        val t = target ?: return
        val p = try {
            parts.probe(t.right, t.left.orEmpty())
        } catch (e: RuntimeException) {
            fail("Bluetooth ist nicht verfügbar: ${e.message}")
            return
        }
        probe = p
        p.start(object : FaceclawDeviceInfoProbeListener {
            override fun onLog(line: String?) {
                if (gen == generation && !line.isNullOrBlank()) log(line)
            }

            override fun onState(state: String?, detail: String?) {
                if (gen != generation) return
                val arm = when (detail) {
                    "right" -> "rechter Bügel"
                    "left" -> "linker Bügel"
                    else -> detail.orEmpty()
                }
                val text = when (state) {
                    "connecting" -> "Verbinde ($arm) …"
                    "authenticating" -> "Kopple ($arm) – falls die Uhr fragt: bestätigen"
                    "querying" -> "Lese die Firmware-Version ($arm) …"
                    else -> "$state $arm"
                }
                setState(_state.value.copy(detail = text))
            }

            override fun onResult(leftVersion: String?, rightVersion: String?, extension: String?) {
                if (gen != generation) return
                releaseProbe()
                onFirmwareChecked(gen, FirmwareRequirement.check(leftVersion, rightVersion, extension))
            }

            override fun onError(message: String?) {
                if (gen != generation) return
                releaseProbe()
                log("Firmware-Prüfung: ${message.orEmpty()}")
                fail("Firmware-Prüfung fehlgeschlagen: ${FaceclawMessages.german(message)}")
            }
        })
    }

    private fun onFirmwareChecked(gen: Int, verdict: FirmwareVerdict) {
        log("Firmware: ${verdict.summary}" + (verdict.message?.let { " – $it" } ?: ""))
        desktop.updateStatus { it.copy(firmware = verdict.summary) }
        if (!verdict.compatible) {
            stopWith(Stage.INCOMPATIBLE, verdict.message.orEmpty(), verdict)
            return
        }
        if (target?.left == null) {
            stopWith(Stage.FAILED, "Für die Anzeige braucht Faceclaw beide Bügel; der linke wurde nicht gefunden.", verdict)
            return
        }
        setState(_state.value.copy(stage = Stage.CONNECTING, firmware = verdict, detail = "Starte die Sitzung …"))
        desktop.updateStatus { it.copy(connection = Stage.CONNECTING.label) }
        parts.updateForeground("Verbinde mit der Brille")
        // The probe closes its links on its own thread; give the stack a moment before the
        // session dials the same arms again.
        worker.postDelayed(PROBE_TO_SESSION_MS) { main.post { if (gen == generation) startSession(gen) } }
    }

    // --- Step 2: session ------------------------------------------------------------------------

    private fun startSession(gen: Int) {
        val t = target ?: return
        val left = t.left ?: return
        val session = try {
            parts.session(t.right, left) { level, line ->
                main.post { if (gen == generation) log(if (level == SessionLogLevel.ERROR) "FEHLER: $line" else "Warnung: $line") }
            }
        } catch (e: RuntimeException) {
            fail("Die Sitzung ließ sich nicht anlegen: ${e.message}")
            return
        }
        val a = Active(session)
        active = a
        input.reset()
        scheduleSweep(a)
        desktop.attach(session.display)
        session.start(SessionListener(gen))
        log("Sitzung gestartet")
    }

    /** Frames the session never finishes would pile up without this; Faceclaw's Android facade does the same. */
    private fun scheduleSweep(a: Active) {
        worker.postDelayed(FrameTimingsCore.SWEEP_INTERVAL_MS) {
            if (!a.closed) {
                a.session.sweepFrameTimings()
                scheduleSweep(a)
            }
        }
    }

    /** Faceclaw's session events, delivered on the main thread. */
    private inner class SessionListener(private val gen: Int) : FaceclawBleCommunicatorListener {
        private val current: Boolean get() = gen == generation && active != null

        /**
         * Faceclaw's core reports its current phase as soon as the listener is set, and that is
         * "disconnected" before it even started connecting. Only a "disconnected" after the session
         * got under way is its own teardown.
         */
        private var underWay = false

        override fun onStateChange(phase: String?, status: String?) {
            if (!current) return
            log("Sitzung: $phase – $status")
            if (phase == "disconnected" && !underWay) return
            underWay = true
            val stage = when (phase) {
                "connected" -> Stage.CONNECTED
                "charging" -> Stage.CHARGING
                "retrying" -> Stage.RECONNECTING
                "disconnecting" -> Stage.DISCONNECTING
                "disconnected" -> {
                    // Only Faceclaw's own teardown says this; never leave a session behind it.
                    stopWith(Stage.IDLE, "", _state.value.firmware)
                    return
                }
                "unpaired" -> {
                    fail(
                        "Die Uhr ist nicht mehr mit der Brille gekoppelt. Die Brille in den Bluetooth-" +
                            "Einstellungen der Uhr entfernen und neu verbinden.",
                    )
                    return
                }
                else -> Stage.CONNECTING
            }
            when (stage) {
                // Keeps the watch CPU awake while the glasses show the desktop. Not while they
                // charge in the case or are out of reach: retrying can go on for hours, and the
                // watch wakes up often enough on its own to reconnect.
                Stage.CONNECTED -> active?.let { a ->
                    a.session.setScreenOn(true)
                    // "connected" also comes on each arm's link before the session is ready; the
                    // wear detector (for "getragen", 03 §5.2) can only be set once it is.
                    worker.post { if (!a.closed && a.session.isReady()) a.session.enableWearDetection() }
                }
                Stage.CHARGING, Stage.RECONNECTING -> active?.session?.setScreenOn(false)
                else -> Unit
            }
            // Faceclaw's status text is English and already in the protocol; the page gets German.
            val detail = when (stage) {
                Stage.CONNECTED -> ""
                Stage.CHARGING -> "Die Brille lädt im Etui."
                Stage.RECONNECTING -> "Verbindung verloren, verbinde neu …"
                Stage.DISCONNECTING -> "Trenne …"
                else -> "Verbinde mit der Brille …"
            }
            setState(_state.value.copy(stage = stage, detail = detail))
            desktop.updateStatus { it.copy(connection = stage.label) }
            parts.updateForeground(stage.label)
        }

        override fun onRingEvent(
            kind: String?,
            containerName: String?,
            eventType: Int,
            eventSource: Int,
            systemExitReasonCode: Int,
            frameId: Int,
            ringTick: Long,
            ringType: Int,
            ringAux: Int,
            ringSpeed: Int,
        ) {
            if (!current) return
            val gesture = input.translate(kind, eventType, eventSource, ringTick, ringType) ?: return
            desktop.glassesGesture(gesture)
            setState(_state.value.copy(lastInput = gesture.label))
        }

        override fun onBatteryState(headsetBattery: Int, headsetCharging: Int, ringBattery: Int, ringCharging: Int) {
            if (!current) return
            // An unavailable reading keeps the last known level.
            val battery = headsetBattery.takeIf { it in 0..100 } ?: _state.value.battery
            val charging = headsetCharging > 0
            setState(_state.value.copy(battery = battery, charging = charging))
            desktop.updateStatus { it.copy(glassesBattery = battery, glassesCharging = charging) }
        }

        override fun onSilentMode(silent: Boolean) = Unit

        override fun onWearState(wearing: Boolean) {
            if (current) setState(_state.value.copy(wearing = wearing))
        }

        override fun onPhoneLockState(locked: Boolean) = Unit

        override fun onEvenAppConflict(message: String?) {
            if (current) log("Warnung: ${message.orEmpty()}")
        }

        override fun onFrameMetrics(paintMs: Int, transmitMs: Int, tileCount: Int) {
            if (!current) return
            framesSent++
            recentTransmitMs.addLast(transmitMs)
            if (recentTransmitMs.size > TransferStats.WINDOW) recentTransmitMs.removeFirst()
            // Up to 30 frames a second; the watch UI does not need every one.
            val now = clockMs()
            if (now - lastMetricsAtMs >= METRICS_INTERVAL_MS) {
                lastMetricsAtMs = now
                setState(
                    _state.value.copy(
                        framesSent = framesSent,
                        transmitMs = transmitMs,
                        transfer = TransferStats.of(recentTransmitMs),
                    ),
                )
            }
        }

        override fun onFrameFinished(frameId: Int, outcome: String?) = Unit

        override fun onFirmwareInfo(leftVersion: String?, rightVersion: String?, extension: String?) {
            if (!current) return
            val verdict = FirmwareRequirement.check(leftVersion, rightVersion, extension)
            log("Firmware laut Sitzung: ${verdict.summary}")
            desktop.updateStatus { it.copy(firmware = verdict.summary) }
            // Like Faceclaw: no data is no evidence, but a definite mismatch ends the session.
            if (!verdict.compatible && verdict.kind != FirmwareKind.UNKNOWN) {
                stopWith(Stage.INCOMPATIBLE, verdict.message.orEmpty(), verdict)
            } else {
                setState(_state.value.copy(firmware = verdict))
            }
        }
    }

    // --- Teardown ------------------------------------------------------------------------------

    private fun fail(message: String) {
        log("FEHLER: $message")
        stopWith(Stage.FAILED, message, _state.value.firmware)
    }

    /** Ends probe and session and leaves the app in [stage] with [detail]. */
    private fun stopWith(stage: Stage, detail: String, firmware: FirmwareVerdict?) {
        val gen = ++generation
        releaseProbe()
        releaseSession()
        setState(_state.value.copy(stage = stage, detail = detail, firmware = firmware))
        afterTeardown(gen) {}
    }

    private fun releaseProbe() {
        val p = probe ?: return
        probe = null
        p.stopListening()
        worker.post { p.close() }
    }

    /** Detaches the desktop and closes the session on the worker; false if there was none. */
    private fun releaseSession(): Boolean {
        val a = active ?: return false
        active = null
        a.closed = true
        desktop.detach()
        a.session.stopListening()
        desktop.updateStatus { it.copy(connection = Stage.IDLE.label, glassesBattery = null, glassesCharging = false) }
        worker.post {
            try {
                // Faceclaw's cleanup message lets the firmware return to its normal screen.
                a.session.close()
            } catch (t: Throwable) {
                main.post { log("Warnung: Sitzung nicht sauber beendet: ${t.message}") }
            }
        }
        return true
    }

    /** Runs [action] and ends the foreground service once everything queued so far is closed. */
    private fun afterTeardown(gen: Int, action: () -> Unit) {
        worker.post {
            main.post {
                if (gen != generation) return@post
                action()
                desktop.updateStatus { it.copy(connection = _state.value.stage.label) }
                parts.stopForeground()
            }
        }
    }

    /** Adds a line of the app's own to the protocol, e.g. a confirmed firmware request. */
    @MainThread
    fun note(line: String) = log(line)

    private fun setState(next: GlassesState) {
        _state.value = next
    }

    private fun log(line: String) {
        val stamped = LocalTime.now().format(TIME) + " " + line
        _log.update { (it + stamped).takeLast(LOG_LINES) }
    }

    private companion object {
        const val LOG_LINES = 300
        const val PROBE_TO_SESSION_MS = 1_000L
        const val METRICS_INTERVAL_MS = 500L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

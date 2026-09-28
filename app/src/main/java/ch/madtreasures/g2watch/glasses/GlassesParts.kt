package ch.madtreasures.g2watch.glasses

import android.content.Context
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.desktop.GlassesDisplay
import com.faceclaw.app.AndroidProtocolPlatform
import com.faceclaw.app.AndroidSessionLink
import com.faceclaw.app.FaceclawBleCommunicatorListener
import com.faceclaw.app.FaceclawBleManager
import com.faceclaw.app.FaceclawDeviceInfoProbe
import com.faceclaw.app.FaceclawDeviceInfoProbeListener
import com.faceclaw.app.FrameTimingsCore
import com.faceclaw.app.GlassesSessionCore
import com.faceclaw.app.SessionLogLevel

/** Faceclaw's read-only device-info probe. Listener callbacks arrive on the main thread. */
interface FirmwareProbe {
    fun start(listener: FaceclawDeviceInfoProbeListener)

    /** No more listener callbacks. Cheap, any thread. */
    fun stopListening()

    /**
     * Cancels the probe and closes its links. Blocks while a GATT operation is in flight
     * (Faceclaw's BLE manager holds its lock for up to 5 s), so never on the main thread.
     */
    fun close()
}

/** Faceclaw's session core for one pair of glasses. Listener callbacks arrive on the main thread. */
interface GlassesSession {
    /** The session's compositor; valid from creation, frames are sent once the session is ready. */
    val display: GlassesDisplay

    fun start(listener: FaceclawBleCommunicatorListener)

    /** No more listener callbacks. */
    fun stopListening()

    /** Holds (true) or releases the wake lock that keeps the watch running for the glasses. */
    fun setScreenOn(on: Boolean)

    /** Finishes frame records that never completed; call every few seconds. */
    fun sweepFrameTimings()

    /** Sends Faceclaw's cleanup message, disconnects both arms and waits for the worker. Blocks. */
    fun close()
}

/** What [GlassesConnection] needs from the platform: Faceclaw's Android classes, or fakes in tests. */
interface GlassesParts {
    /** Throws [IllegalStateException] when Bluetooth is unavailable. */
    fun probe(right: String, left: String): FirmwareProbe

    /** Throws [IllegalStateException] when Bluetooth is unavailable. */
    fun session(right: String, left: String, log: (SessionLogLevel, String) -> Unit): GlassesSession

    fun showForeground(text: String)

    fun updateForeground(text: String)

    fun stopForeground()
}

/** [GlassesParts] with Faceclaw's Android GATT classes, wired the way FaceclawBleCommunicator does it. */
class FaceclawParts(context: Context) : GlassesParts {
    private val context = context.applicationContext

    override fun probe(right: String, left: String): FirmwareProbe {
        val probe = FaceclawDeviceInfoProbe(context, right, left)
        return object : FirmwareProbe {
            override fun start(listener: FaceclawDeviceInfoProbeListener) {
                probe.setListener(listener)
                probe.start()
            }

            override fun stopListening() = probe.setListener(null)

            override fun close() = probe.close()
        }
    }

    override fun session(right: String, left: String, log: (SessionLogLevel, String) -> Unit): GlassesSession {
        val bleManager = FaceclawBleManager(context)
        val timings = FrameTimingsCore()
        val core = GlassesSessionCore(
            AndroidSessionLink(bleManager),
            WearSessionHost(context, log),
            timings,
            AndroidProtocolPlatform,
            right,
            left,
            null,
        )
        bleManager.setListener(core)
        // Before any surface is configured, like Faceclaw's dashboard controller does it.
        core.configureCompositorScreen(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
        return object : GlassesSession {
            override val display: GlassesDisplay = CoreDisplay(core, timings)

            override fun start(listener: FaceclawBleCommunicatorListener) {
                core.setListener(listener)
                core.start()
            }

            override fun stopListening() = core.setListener(null)

            override fun setScreenOn(on: Boolean) = core.setG2ScreenOn(on)

            override fun sweepFrameTimings() {
                timings.sweepTimedOut()
            }

            override fun close() = core.close()
        }
    }

    override fun showForeground(text: String) = GlassesService.start(context, text)

    override fun updateForeground(text: String) = GlassesService.update(context, text)

    override fun stopForeground() = GlassesService.stop(context)
}

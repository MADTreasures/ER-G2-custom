package ch.madtreasures.g2watch

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import ch.madtreasures.g2watch.apps.host.AndroidAppPlatform
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.GlassesStatus
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.ble.G2Scanner
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.firmware.AndroidFirmwareEnvironment
import ch.madtreasures.g2watch.firmware.LensPair
import ch.madtreasures.g2watch.firmware.WatchFirmwareInstaller
import ch.madtreasures.g2watch.glasses.FirmwareInstaller
import ch.madtreasures.g2watch.glasses.GlassesConnection
import ch.madtreasures.g2watch.glasses.Stage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Holds the desktop and the glasses connection for the whole process, so both survive activity
 * recreation and keep running behind the foreground service while the watch display is off.
 */
class G2WatchApp : Application() {
    val desktop: DesktopController by lazy { DesktopController(AndroidTextPainter()).also { it.startClock() } }
    val glasses: GlassesConnection by lazy { GlassesConnection(this, desktop) }
    val scanner: G2Scanner by lazy { G2Scanner(this) }

    /**
     * The app host behind the "Apps" tile (03 §5), on its own thread with its own text painter.
     * Its log lines go to Settings → Protokoll.
     */
    val apps: AppHost by lazy {
        val main = MainScheduler()
        AppHost(
            platform = AndroidAppPlatform(this) { line -> main.post { glasses.note(line) } },
            renderer = PageRenderer(AndroidTextPainter()),
        ).also { desktop.connectApps(it) }
    }

    private val scope = MainScope()

    /**
     * Puts the original or the custom firmware on the last chosen glasses. Only the confirm page
     * (two-second hold) starts it; see [WatchFirmwareInstaller].
     */
    val firmware: FirmwareInstaller by lazy {
        val main = MainScheduler()
        val note: (String) -> Unit = { line -> main.post { glasses.note(line) } }
        WatchFirmwareInstaller(
            env = AndroidFirmwareEnvironment(this, { glasses }, main, note),
            pair = { lastPair()?.let { LensPair(it.right, it.left.orEmpty()) } },
            worker = ThreadScheduler("G2Watch-firmware"),
            log = note,
        )
    }

    private val prefs by lazy { getSharedPreferences("g2watch", Context.MODE_PRIVATE) }

    data class LastPair(val title: String, val right: String, val left: String?)

    override fun onCreate() {
        super.onCreate()
        followWatchBattery()
        followGlassesForApps()
    }

    /** Battery, charging and wearing of the glasses for the app host (03 §5.2). */
    private fun followGlassesForApps() {
        val host = apps
        scope.launch {
            glasses.state.collect { st ->
                host.updateGlasses(
                    GlassesStatus(
                        connected = st.stage == Stage.CONNECTED,
                        battery = st.battery,
                        charging = st.charging,
                        wearing = st.wearing,
                    ),
                )
            }
        }
    }

    fun lastPair(): LastPair? {
        val right = prefs.getString("right", null) ?: return null
        return LastPair(prefs.getString("title", null) ?: "G2", right, prefs.getString("left", null))
    }

    fun saveLastPair(title: String, right: String, left: String?) {
        prefs.edit {
            putString("title", title)
            putString("right", right)
            putString("left", left)
        }
    }

    fun forgetLastPair() {
        prefs.edit {
            remove("title")
            remove("right")
            remove("left")
        }
    }

    /** The watch battery in the desktop's top bar, from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private fun followWatchBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
                desktop.updateStatus { it.copy(watchBattery = percent) }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

package ch.madtreasures.g2watch.firmware

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import androidx.core.content.edit
import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.GlassesConnection
import com.faceclaw.app.AndroidProtocolPlatform
import com.faceclaw.app.ProtocolPlatform
import com.faceclaw.app.StockFlowTimings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [FirmwareEnvironment] on the watch. Methods are called on the firmware worker thread; anything
 * that touches the UI side (the glasses connection, the foreground service) is posted to [main].
 */
class AndroidFirmwareEnvironment(
    context: Context,
    private val glasses: () -> GlassesConnection,
    private val main: Scheduler,
    log: (String) -> Unit,
) : FirmwareEnvironment {
    private val context = context.applicationContext

    override val stock: StockImageSource = StockImageStore(
        cacheDir = File(this.context.noBackupFilesDir, "firmware"),
        importDir = this.context.getExternalFilesDir("firmware"),
        log = log,
    )

    override val images: FirmwareImages = FirmwareImages.Catalog

    override val timings: StockFlowTimings = StockFlowTimings()

    override val platform: ProtocolPlatform = AndroidProtocolPlatform

    override fun openLink(log: (String) -> Unit): GuardedStockLink = WatchStockLink.guarded(context, log)

    override fun watchPower(): WatchPower {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return WatchPower(null, charging = false)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return WatchPower(percent, battery.isCharging)
    }

    override fun releaseGlasses() {
        val released = CountDownLatch(1)
        main.post { glasses().releaseForFirmware { released.countDown() } }
        if (!released.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw IllegalStateException("die Brillenverbindung wurde nicht rechtzeitig getrennt")
        }
    }

    override fun keepAwake(text: String) {
        main.post { FirmwareService.start(context, text) }
    }

    override fun allowSleep() {
        main.post { FirmwareService.stop(context) }
    }

    private val prefs = this.context.getSharedPreferences("g2watch-firmware", Context.MODE_PRIVATE)

    // commit, not apply: the marker must be on disk before the first firmware byte goes out.
    @SuppressLint("ApplySharedPref")
    override fun markTransfer(target: FirmwareTarget?) {
        prefs.edit(commit = true) {
            if (target == null) remove(TRANSFER_KEY) else putString(TRANSFER_KEY, target.name)
        }
    }

    override fun interruptedTransfer(): FirmwareTarget? =
        prefs.getString(TRANSFER_KEY, null)?.let { name -> FirmwareTarget.entries.firstOrNull { it.name == name } }

    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    private companion object {
        /** Closing a session waits for its cleanup message and worker; closing a probe up to 5 s. */
        const val RELEASE_TIMEOUT_S = 45L
        const val TRANSFER_KEY = "transferRunning"
    }
}

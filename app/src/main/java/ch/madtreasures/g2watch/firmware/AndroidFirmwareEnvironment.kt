package ch.madtreasures.g2watch.firmware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ch.madtreasures.g2watch.Scheduler
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
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        return WatchPower(percent, plugged != 0)
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

    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    private companion object {
        /** Closing a session waits for its cleanup message and worker; closing a probe up to 5 s. */
        const val RELEASE_TIMEOUT_S = 45L
    }
}

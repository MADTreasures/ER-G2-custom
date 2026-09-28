package ch.madtreasures.g2watch.firmware

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.madtreasures.g2watch.MainActivity
import ch.madtreasures.g2watch.R

/**
 * Keeps the watch running while firmware is prepared and transferred: a foreground service (so
 * Wear OS does not stop the process when the display goes dark or the wearer presses the side
 * button) holding a partial wake lock (so the CPU keeps feeding the Bluetooth link). A transfer
 * takes several minutes per lens; the lock has a generous timeout as a last resort.
 *
 * Separate from [ch.madtreasures.g2watch.glasses.GlassesService]: the glasses connection stops
 * its service when it lets go of the glasses, which is exactly when the transfer starts.
 *
 * Like GlassesService, [stop] never stops a service that is still starting (Android would end the
 * app); the service stops itself right after it went to the foreground.
 */
class FirmwareService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(this, latestText ?: "Firmware"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        }
        starting = false
        if (stopWhenStarted) {
            stopWhenStarted = false
            stopSelf()
        } else {
            running = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "G2Watch"
        private const val CHANNEL_ID = "firmware"
        private const val NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TAG = "G2Watch:firmware"

        /** Two lenses at up to ~17 minutes each, plus download, prompt, waits and checks, with a wide margin. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        // Main thread only.
        private var starting = false
        private var running = false
        private var stopWhenStarted = false
        private var latestText: String? = null

        /** Starts or updates the service. Call on the main thread, ideally while the app is visible. */
        fun start(context: Context, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "no Bluetooth permission, no firmware foreground service")
                return
            }
            stopWhenStarted = false
            latestText = text
            if (starting || running) {
                if (running) {
                    context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(context, text))
                }
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, FirmwareService::class.java))
                starting = true
            } catch (e: RuntimeException) {
                // For example ForegroundServiceStartNotAllowedException: the transfer still runs
                // while the app stays open, and the screen is kept on.
                Log.w(TAG, "firmware foreground service not started", e)
            }
        }

        fun stop(context: Context) {
            when {
                starting -> stopWhenStarted = true
                running -> {
                    running = false
                    context.stopService(Intent(context, FirmwareService::class.java))
                }
            }
        }

        private fun notification(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Firmware", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }
    }
}

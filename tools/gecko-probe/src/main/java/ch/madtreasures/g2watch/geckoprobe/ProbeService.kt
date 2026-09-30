package ch.madtreasures.g2watch.geckoprobe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Holds the app in the foreground with a partial wake lock while a test runs, the way the watch
 * app keeps the glasses session alive: so GeckoView is measured with the screen off under the
 * same conditions it would later run under.
 */
class ProbeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The "special use" type exists from API 34 (Wear OS 5) on; before, no type is needed.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Messung läuft"), type)
        if (wakeLock == null) {
            // Released in onDestroy; a test ends the service when it is done (at most 35 minutes).
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "G2GeckoTest:measure").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, ProbeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "measure"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProbeService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (e: RuntimeException) {
                // Started from the background: the test still runs while the screen is on.
                Log.w("GeckoTest", "no foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProbeService::class.java))
        }
    }
}

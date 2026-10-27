package ch.madtreasures.g2watch.glasses

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.madtreasures.g2watch.MainActivity
import ch.madtreasures.g2watch.R

/**
 * Keeps the process in the foreground while the watch talks to the glasses, so Wear OS does not
 * stop the connection when the watch display goes dark. The connection itself lives in
 * [GlassesConnection]; this service only holds the ongoing notification.
 *
 * With the permission RECORD_AUDIO it also has the type `microphone`: only so may apps stream the
 * watch's microphone to the phone while the display is dark (PhoneLinks). Likewise ACCESS_FINE_LOCATION
 * brings the type `location`, so apps keep the watch's GPS with the display dark (interface 5). Android
 * grants those types only while the app is in front, so the service starts there, and [refresh] adds
 * them after the wearer allowed the microphone or the location.
 *
 * Android ends the whole app if a service started with startForegroundService() stops before
 * it called startForeground(). So [stop] never stops a service that is still starting; it
 * leaves a note, and the service stops itself right after going to the foreground.
 */
class GlassesService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = notification(this, latestText ?: getString(R.string.notification_connected))
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var types = device
        if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, note, types)
        } catch (e: SecurityException) {
            // Microphone or location while the app is not in front: the glasses connection without them.
            Log.w(TAG, "foreground service without microphone and location", e)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, note, device)
        }
        starting = false
        if (stopWhenStarted) {
            stopWhenStarted = false
            stopSelf()
        } else {
            running = true
        }
        // Without the process there is no connection to keep, so no restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "G2Watch"
        private const val CHANNEL_ID = "glasses"
        private const val NOTIFICATION_ID = 1

        // Main thread only: start/update/stop are called there, and so are the lifecycle methods.
        private var starting = false
        private var running = false
        private var stopWhenStarted = false
        private var latestText: String? = null

        /** Call while the app is in the foreground (Android refuses it from the background). */
        fun start(context: Context, text: String) {
            // A connectedDevice service needs the Bluetooth permission; the connection itself
            // fails without it anyway and says so.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "no Bluetooth permission, no foreground service")
                return
            }
            stopWhenStarted = false
            if (starting || running) {
                update(context, text)
                return
            }
            latestText = text
            try {
                ContextCompat.startForegroundService(context, Intent(context, GlassesService::class.java))
                starting = true
            } catch (e: RuntimeException) {
                // For example ForegroundServiceStartNotAllowedException: the connection still
                // works while the app stays open.
                Log.w(TAG, "foreground service not started", e)
            }
        }

        /**
         * Gives the running service the type `microphone` once RECORD_AUDIO is granted; call while the app
         * is in front (right after the wearer allowed it).
         */
        fun refresh(context: Context) {
            if (!running) return
            try {
                context.startService(Intent(context, GlassesService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "foreground service not refreshed", e)
            }
        }

        /** New notification text without restarting the service. */
        fun update(context: Context, text: String) {
            latestText = text
            if (!running) return // a starting service picks up [latestText]
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(context, text))
        }

        fun stop(context: Context) {
            when {
                starting -> stopWhenStarted = true
                running -> {
                    running = false
                    context.stopService(Intent(context, GlassesService::class.java))
                }
            }
        }

        private fun notification(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
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
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}

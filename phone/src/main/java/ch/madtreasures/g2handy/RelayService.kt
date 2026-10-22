package ch.madtreasures.g2handy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps G2 Handy awake while it is ready for the watch: a foreground service (`connectedDevice`) with a
 * notice of what is connected. Android lets an app start it only from the foreground (or right after the
 * phone started), so the screen starts it, and the [BootReceiver] after a restart.
 */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            Ready.set(this, false)
            stop()
            return START_NOT_STICKY
        }
        val app = G2HandyApp.of(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(app.relay.status()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android refused (started from the background): the connection works as long as G2 Handy runs anyway.
            Log.w(G2HandyApp.TAG, "foreground service refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                app.status.collectLatest { links ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(links))
                    if (links.isEmpty() && !Ready.get(this@RelayService)) stop()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(links: List<Relay.Status>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(Texts.notice(links) { Texts.label(this, it) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.off), off)
            .build()
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_OFF = "ch.madtreasures.g2handy.action.OFF"

        /** Starts the foreground service; refused by Android when G2 Handy is in the background. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
            } catch (e: Exception) {
                Log.w(G2HandyApp.TAG, "foreground service not started", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

/** After the phone started (or G2 Handy was updated): ready again, if it was. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Ready.get(context)) RelayService.start(context)
    }
}

/** Whether G2 Handy stays ready for the watch (the switch on the screen); on until switched off. */
object Ready {
    private const val PREFS = "g2handy"
    private const val KEY = "ready"

    fun get(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
    }
}

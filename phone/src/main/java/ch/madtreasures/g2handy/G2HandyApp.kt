package ch.madtreasures.g2handy

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** G2 Handy: one [Relay] per process, shared by the listener service, the foreground service and the screen. */
class G2HandyApp : Application() {
    lateinit var relay: Relay
        private set

    private val connections = MutableStateFlow<List<Relay.Status>>(emptyList())

    /** The connections the relay holds now. */
    val status: StateFlow<List<Relay.Status>> get() = connections

    override fun onCreate() {
        super.onCreate()
        val main = Handler(Looper.getMainLooper())
        val messages = Wearable.getMessageClient(this)
        relay = Relay(
            watch = { node, path, data ->
                messages.sendMessage(node, path, data).addOnFailureListener { e -> Log.w(TAG, "to the watch failed: $path", e) }
            },
            partners = MessengerPartners(this),
            after = { ms, action ->
                val r = Runnable(action)
                main.postDelayed(r, ms)
                Relay.Cancel { main.removeCallbacks(r) }
            },
            onChange = { connections.value = it },
        )
    }

    companion object {
        const val TAG = "G2Handy"

        fun of(context: Context): G2HandyApp = context.applicationContext as G2HandyApp
    }
}

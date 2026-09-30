package ch.madtreasures.g2watch.apps.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WLAN or LTE for videos (01 §4, 03 §10). A watch paired with a phone sends its traffic over Bluetooth
 * through the phone by default: slow, and the same radio the glasses need. While a video loads, the
 * watch asks Wear OS for WLAN or mobile data and binds the app to it; the last [release] gives it back
 * (akku). [acquire] and [release] never block; [awaitReady] waits for the network before a video starts.
 */
internal class FastNetwork(context: Context, private val log: (String) -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var users = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var available = CountDownLatch(1)

    fun acquire() = synchronized(lock) {
        users++
        if (callback == null) request()
    }

    /**
     * Waits up to [timeoutMs] for WLAN or LTE (never on the main or app thread); false when neither came,
     * and the video goes over whatever network there is.
     */
    fun awaitReady(timeoutMs: Long = WAIT_MS): Boolean {
        val latch = synchronized(lock) { available }
        val ready = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ready) log("Video: kein WLAN und kein LTE – über die Verbindung, die gerade da ist")
        return ready
    }

    fun release() = synchronized(lock) {
        if (users == 0) return@synchronized
        users--
        if (users > 0) return@synchronized
        callback?.let {
            try {
                connectivity?.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
        callback = null
        available = CountDownLatch(1)
        connectivity?.bindProcessToNetwork(null)
    }

    private fun request() {
        val manager = connectivity ?: return available.countDown()
        val ready = available
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (manager.bindProcessToNetwork(network)) log("Video: über WLAN/LTE")
                ready.countDown()
            }

            override fun onLost(network: Network) {
                if (manager.boundNetworkForProcess == network) manager.bindProcessToNetwork(null)
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            manager.requestNetwork(request, cb)
        } catch (e: RuntimeException) {
            // SecurityException without CHANGE_NETWORK_STATE, or too many requests: use the default network.
            log("Video: WLAN/LTE nicht anforderbar – ${e.message}")
            callback = null
            ready.countDown()
        }
    }

    private companion object {
        const val WAIT_MS = 8_000L
    }
}

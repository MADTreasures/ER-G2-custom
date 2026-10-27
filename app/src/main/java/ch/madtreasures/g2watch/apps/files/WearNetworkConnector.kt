package ch.madtreasures.g2watch.apps.files

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Opens downloads on a fast network of the watch: Wi-Fi, or with LTE allowed also the mobile network.
 * Near the phone Wear OS otherwise sends everything over the Bluetooth link to it, which is far too slow
 * for files of hundreds of megabytes; an app has to ask for Wi-Fi or LTE itself (Android developer guide
 * "Network access and sync on Wear OS"). The network is asked for per download, waited for at most
 * [waitMs], and released when the download's connection closes. Unlike videos
 * ([ch.madtreasures.g2watch.apps.video.FastNetwork]) a download does not bind the whole app to the
 * network and does not fall back to the Bluetooth link: without Wi-Fi (or LTE, if allowed) it fails.
 */
class WearNetworkConnector(context: Context, private val waitMs: Long = 30_000) : Connector {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun open(url: String, wifiOnly: Boolean, from: Long): Connector.Response {
        val manager = connectivity ?: throw IOException("Kein Netz auf dieser Uhr")
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .apply { if (!wifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) }
            .build()
        val found = ArrayBlockingQueue<Network>(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.offer(network)
            }
        }
        manager.requestNetwork(request, callback)
        val release = {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Released already.
            }
        }
        val network = try {
            found.poll(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            release()
            throw IOException("abgebrochen", e)
        }
        if (network == null) {
            release()
            throw IOException(if (wifiOnly) "kein WLAN erreichbar" else "weder WLAN noch LTE erreichbar")
        }
        val response = try {
            UrlConnector { address, _ -> network.openConnection(address) }.open(url, wifiOnly, from)
        } catch (e: IOException) {
            release()
            throw e
        }
        return object : Connector.Response by response {
            override fun close() {
                response.close()
                release()
            }
        }
    }
}

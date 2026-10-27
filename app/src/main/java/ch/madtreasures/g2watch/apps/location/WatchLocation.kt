package ch.madtreasures.g2watch.apps.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import ch.madtreasures.g2watch.apps.AppEvent
import java.util.concurrent.Executors

/**
 * The watch's own GPS ([LocationManager.GPS_PROVIDER]; the fused position would come from the phone while it
 * is near). Without the permission ACCESS_FINE_LOCATION a line waits: the watch asks on its screen
 * ([prompt]), and the lines start once the wearer allowed it ([allowed]).
 */
class WatchLocation(context: Context, private val prompt: LocationPrompt) : LocationEngine {
    private val context = context.applicationContext
    private val manager: LocationManager? = this.context.getSystemService(LocationManager::class.java)
    private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "G2Watch-location").apply { isDaemon = true } }
    private val waiting = LinkedHashSet<Line>()

    override fun start(intervalMs: Long, listener: LocationListener): LocationLine {
        val line = Line(intervalMs, listener)
        if (granted()) {
            line.begin()
        } else {
            synchronized(waiting) { waiting += line }
            listener.onNotice(ASK)
            prompt.ask()
        }
        return line
    }

    /** The wearer allowed the location: the lines that waited for it start. */
    fun allowed() {
        val lines = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        lines.forEach { it.begin() }
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private inner class Line(private val intervalMs: Long, private val listener: LocationListener) :
        LocationLine, android.location.LocationListener {
        @Volatile private var stopped = false

        @Volatile private var running = false

        fun begin() {
            if (stopped) return
            val m = manager ?: return listener.onNotice(NO_GPS)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return listener.onNotice(DENIED)
            }
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) listener.onNotice(OFF)
            try {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, callbacks, this)
                running = true
                // A recent fix at once, so a map need not wait for the first new one.
                m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.takeIf { ageMs(it) < FRESH_MS }?.let { onLocationChanged(it) }
            } catch (e: SecurityException) {
                listener.onNotice(DENIED)
            } catch (e: IllegalArgumentException) {
                listener.onNotice(NO_GPS)
            }
        }

        override fun onLocationChanged(location: Location) {
            if (!stopped) listener.onFix(location.toFix())
        }

        override fun onProviderDisabled(provider: String) {
            if (!stopped) listener.onNotice(OFF)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun stop() {
            stopped = true
            synchronized(waiting) { waiting -= this }
            if (running) manager?.removeUpdates(this)
        }
    }

    private fun ageMs(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L

    companion object {
        const val FRESH_MS = 30_000L
        const val ASK = "Standort bitte auf der Uhr erlauben"
        const val DENIED = "Standort der Uhr nicht erlaubt"
        const val OFF = "Standort der Uhr ist aus: in den Einstellungen der Uhr einschalten"
        const val NO_GPS = "Kein GPS auf dieser Uhr"

        /** A fix as apps get it; speed, direction and height only where the receiver knows them. */
        fun Location.toFix() = AppEvent.Location(
            lat = latitude,
            lon = longitude,
            acc = accuracy,
            t = time,
            speed = if (hasSpeed()) speed else null,
            bearing = if (hasBearing()) bearing else null,
            alt = if (hasAltitude()) altitude else null,
        )
    }
}

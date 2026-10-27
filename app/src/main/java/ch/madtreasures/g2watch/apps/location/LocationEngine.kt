package ch.madtreasures.g2watch.apps.location

import ch.madtreasures.g2watch.apps.AppEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The watch's GPS for the apps ([ch.madtreasures.g2watch.apps.Sensor.LOCATION], interface 5, 03 §14). */
interface LocationEngine {
    /** Fixes about every [intervalMs] until [LocationLine.stop]; [listener] hears from any thread. */
    fun start(intervalMs: Long, listener: LocationListener): LocationLine

    companion object {
        val NONE: LocationEngine = object : LocationEngine {
            override fun start(intervalMs: Long, listener: LocationListener): LocationLine {
                listener.onNotice("Kein GPS auf dieser Uhr")
                return LocationLine {}
            }
        }
    }
}

interface LocationListener {
    fun onFix(fix: AppEvent.Location)

    /** Something the wearer should know: the watch asks for the permission, GPS is switched off. */
    fun onNotice(message: String)
}

fun interface LocationLine {
    fun stop()
}

/** The question for the watch's location (apps that use GPS), asked on the watch screen like [ch.madtreasures.g2watch.apps.phone.MicPrompt]. */
class LocationPrompt {
    private val _wanted = MutableStateFlow(false)

    /** True while the question waits for the screen. */
    val wanted: StateFlow<Boolean> = _wanted.asStateFlow()

    /** Called when the wearer allowed it (the screen's thread). */
    @Volatile var onAllowed: () -> Unit = {}

    fun ask() {
        _wanted.value = true
    }

    fun answered(allowed: Boolean) {
        _wanted.value = false
        if (allowed) onAllowed()
    }
}

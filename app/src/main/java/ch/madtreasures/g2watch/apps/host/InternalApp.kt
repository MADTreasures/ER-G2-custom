package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.coroutines.flow.StateFlow

/** Battery, charging and wearing of the glasses, as far as known (03 §5.2). */
data class GlassesStatus(
    val connected: Boolean = false,
    val battery: Int? = null,
    val charging: Boolean = false,
    /** Null until the glasses reported it; needs the wear detector the connection switches on. */
    val wearing: Boolean? = null,
)

/**
 * A platform part that runs as a session of its own, like the launcher (03 §5.2): the EvenHub
 * runtime (M3) and the web browser (M7). Unlike a [G2App] it may run its own threads and engines
 * and draws finished pixels with [InternalContext.setRaster]; the host shows "Startet …" until
 * its first page, for at most [startTimeoutMs].
 */
interface InternalApp {
    val manifest: AppManifest

    val startTimeoutMs: Long get() = 20_000L

    /** A double tap on a temple or the ring goes to the app as a gesture instead of meaning Back (EvenHub). */
    val doubleTapToApp: Boolean get() = false

    /** Called on the app thread, never concurrently. No 50 ms limit, but long work belongs on the part's own threads. */
    fun onEvent(event: AppEvent, host: InternalContext)
}

/** [AppContext] plus what only platform parts get. */
interface InternalContext : AppContext {
    /**
     * Writes gray pixels into a borderless image block, without PNG and without the 48 KiB limit.
     * The raster is used as given; do not change it afterwards.
     */
    fun setRaster(blockId: String, raster: GrayRaster)

    val glasses: StateFlow<GlassesStatus>

    /** Runs [action] on the app thread, e.g. with a result from the part's own thread; dropped once the session ended. */
    fun post(action: () -> Unit)
}

/** An installed EvenHub app, for the launcher. */
data class EvenHubAppInfo(
    val id: String,
    val name: String,
    val version: String,
    val location: Location,
    val permissions: Set<Permission> = emptySet(),
) {
    /** Where the app's code runs (05 §3); the watch draws in both cases. */
    enum class Location { WATCH, PHONE }
}

/** The installed EvenHub apps (03 §5.2). Empty until the EvenHub runtime arrives with M3. */
interface EvenHubRegistry {
    fun installed(): List<EvenHubAppInfo>

    /** A new session for app [id], or null if it is not installed (any more). */
    fun create(id: String): InternalApp?
}

object NoEvenHubApps : EvenHubRegistry {
    override fun installed(): List<EvenHubAppInfo> = emptyList()

    override fun create(id: String): InternalApp? = null
}

package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.video.VideoEngine
import ch.madtreasures.g2watch.desktop.GrayRaster

/** What the app host needs from the platform: Android on the watch, fakes in tests. */
interface HostPorts {
    /** The store of app [appId] (≤ [AppStorage.MAX_BYTES]); called on the app thread. */
    fun storage(appId: String): AppStorage

    /** The permissions the wearer granted this app version, or null if not asked yet. */
    fun grantedPermissions(appId: String, version: String): Set<Permission>?

    fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>)

    fun vibrate(pattern: Vibration)

    /** Runs [request] off the app thread (HTTPS only, 10 s, 1 MB) and calls [done] from any thread. */
    fun fetch(request: HttpRequest, done: (HttpResult) -> Unit)

    /** A file from the APK's assets (`app/src/main/assets/…`), or null. */
    fun asset(path: String): ByteArray?

    /** A PNG (or other picture) as 8-bit grey, or null if it is none. */
    fun decodeImage(bytes: ByteArray): GrayRaster?

    /** A line for the watch log (Settings → Protokoll); any thread. */
    fun log(line: String)

    /**
     * Asks the wearer for a line of text on the watch: keyboard, voice or one of [suggestions]. [done]
     * gets the text, or null when the wearer cancelled; from any thread, at most once. A new question
     * replaces an open one, whose [done] is then never called.
     */
    fun askText(prompt: String, suggestions: List<String>, done: (String?) -> Unit)

    /** Takes back the open question of [askText], if any (its app ended). */
    fun cancelText()

    /** Searching and playing videos (03 §10). */
    val video: VideoEngine
}

/** The glasses as apps may know them (03 §5.2). */
data class GlassesStatus(
    val connected: Boolean = false,
    val battery: Int? = null,
    val charging: Boolean = false,
    /** Null until the glasses reported it (wear detection). */
    val wearing: Boolean? = null,
)

/**
 * A session the host runs for the platform rather than for a [ch.madtreasures.g2watch.apps.G2App]
 * (03 §5.2): the launcher, and from M3 the Even Hub runtime. It uses the same pages, history and menu
 * as an app, but no 50 ms rule and no permission questions apply, and it can write finished rasters
 * straight into image blocks.
 */
interface InternalSession {
    val manifest: AppManifest

    /** How long the host waits for the first page before "App antwortet nicht" (Even Hub: 20 s). */
    val startTimeoutMs: Long get() = AppHost.FIRST_PAGE_MS

    /** Shown while the session has no page yet, e.g. "Startet …"; null shows an empty page. */
    val startingText: String? get() = null

    /** Double taps on temple or ring go to the session as gestures instead of meaning back (Even Hub). */
    val ownsDoubleClick: Boolean get() = false

    fun onEvent(event: AppEvent, host: InternalContext)

    /** Battery, charging or wearing changed. */
    fun onGlassesStatus(status: GlassesStatus, host: InternalContext) = Unit
}

/** What an [InternalSession] can do beyond an app. */
interface InternalContext : AppContext {
    /** Writes [raster] into image block [blockId] (same size as the block), without PNG and without the 48 KiB limit. */
    fun setRaster(blockId: String, raster: GrayRaster)

    val glasses: GlassesStatus
}

/** An installed app package, ready to run: the app and the files of its package (`assets/…`). */
class InstalledApp(val app: G2App, val asset: (String) -> ByteArray?)

/**
 * The apps installed as packages (docs/app-entwicklung/09): the launcher lists them after the built-in
 * apps. [ch.madtreasures.g2watch.apps.packages.AppPackages] fills it on the watch.
 */
interface InstalledApps {
    /** The installed apps, in launcher order. */
    val apps: List<AppManifest>

    /** A new instance of app [id], or null if it is not installed; throws if its code does not load. */
    fun open(id: String): InstalledApp?

    companion object {
        val NONE: InstalledApps = object : InstalledApps {
            override val apps: List<AppManifest> = emptyList()

            override fun open(id: String): InstalledApp? = null
        }
    }
}

/** Where an Even Hub app runs (05 §3). */
enum class EvenHubLocation(val label: String) {
    WATCH("Uhr"),
    PHONE("Handy"),
}

/** An installed Even Hub app, as the launcher lists it. */
data class EvenHubEntry(
    val id: String,
    val name: String,
    val version: String,
    val location: EvenHubLocation,
    val permissions: Set<Permission> = emptySet(),
)

/** The installed Even Hub apps (03 §5.2). The Even Hub runtime fills it from M3; until then it is empty. */
interface EvenHubRegistry {
    val apps: List<EvenHubEntry>

    /** A new session that runs app [id], or null if it is not installed (any more). */
    fun open(id: String): InternalSession?

    companion object {
        val NONE: EvenHubRegistry = object : EvenHubRegistry {
            override val apps: List<EvenHubEntry> = emptyList()

            override fun open(id: String): InternalSession? = null
        }
    }
}

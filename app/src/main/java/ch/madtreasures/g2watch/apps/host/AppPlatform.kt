package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.desktop.GrayRaster

/**
 * What the app host needs from Android: assets, storage, the vibrator, HTTP, image decoding and
 * the log. [AndroidAppPlatform] on the watch, fakes in tests. Called on the app thread unless
 * noted otherwise.
 */
interface AppPlatform {
    /** A file from the APK's assets (e.g. `apps/<id>/ui.json`), or null. */
    fun readAsset(path: String): ByteArray?

    /** The stored key-value pairs of app [appId] as JSON texts. */
    fun loadStorage(appId: String): Map<String, String>

    fun saveStorage(appId: String, values: Map<String, String>)

    /** The permissions the wearer answered for this app version, or null if not asked yet. */
    fun grantedPermissions(appId: String, version: String): Set<Permission>?

    fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>)

    fun vibrate(pattern: Vibration)

    /** Runs [request] off the app thread and calls [onResult] from any thread. */
    fun http(request: HttpRequest, onResult: (HttpResult) -> Unit)

    /**
     * Decodes a PNG (or another format Android knows) into gray levels, scaled down to fit
     * [maxWidth] × [maxHeight] with its aspect ratio kept. Null if it is no picture.
     */
    fun decodeImage(bytes: ByteArray, maxWidth: Int, maxHeight: Int): GrayRaster?

    /** A line for Settings → Protokoll; any thread. */
    fun log(line: String)
}

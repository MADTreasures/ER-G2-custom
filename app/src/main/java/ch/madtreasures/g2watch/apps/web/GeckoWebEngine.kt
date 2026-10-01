package ch.madtreasures.g2watch.apps.web

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.asCoroutineDispatcher
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors

/**
 * [WebEngine] on the watch (05 §10, M7). GeckoView paints every page into an unseen surface of its
 * block's size ([GeckoWebPage]), at 1.5 pixels per CSS pixel; the built-in extension
 * (`assets/webbridge/`) reports the page's layout, takes the second capture without text, scrolls,
 * types and shows articles in reading mode; web-raster ([WebPicture]) turns the captures into the
 * glasses' picture. Everything with GeckoView happens on the main thread, the rasterizing on
 * "G2Watch-web".
 *
 * One [GeckoRuntime] for the whole process, started with the first page and kept: GeckoView cannot
 * start a second one in the same process. It runs Gecko's main part in the watch app's process and
 * pages in its own processes, which is why [ch.madtreasures.g2watch.G2WatchApp] only works in the main
 * process. Not tried on a watch yet; memory, start time and battery are what the Gecko test (M2) measures.
 */
class GeckoWebEngine(context: Context, private val log: (String) -> Unit) : WebEngine {
    private val app = context.applicationContext

    internal val main = Handler(Looper.getMainLooper())

    /** Rasterizing, off the main thread. */
    internal val work = Executors.newSingleThreadExecutor { r -> Thread(r, "G2Watch-web").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    /** Where the unseen surfaces drop the frames nobody captures. */
    internal val surfaces: Handler by lazy { Handler(HandlerThread("G2Watch-web-surface").apply { start() }.looper) }

    // Main thread only.
    private var runtime: GeckoRuntime? = null
    private var extension: GeckoResult<WebExtension>? = null

    override fun open(request: WebRequest, listener: WebListener): WebPage =
        GeckoWebPage(this, request, listener).also { page -> main.post { page.start() } }

    internal fun note(line: String) = log("Browser: $line")

    /** The runtime of the process, started on first use. Main thread. */
    internal fun runtime(): GeckoRuntime = runtime ?: GeckoRuntime.create(app, settings()).also { rt ->
        rt.delegate = GeckoRuntime.Delegate { note("Browser-Engine beendet") }
        runtime = rt
        note("GeckoView gestartet")
    }

    /** The bridge extension, installed once per runtime. Main thread. */
    internal fun extension(): GeckoResult<WebExtension> = extension
        ?: runtime().webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).also { extension = it }

    /** The extension did not install; the next page tries again. Main thread. */
    internal fun extensionFailed() {
        extension = null
    }

    private fun settings(): GeckoRuntimeSettings = GeckoRuntimeSettings.Builder()
        // 05 §5: as few processes as possible on the watch.
        .fissionEnabled(false)
        .extensionsProcessEnabled(false)
        .displayDensityOverride(WebPicture.DENSITY)
        // Pages with a dark look show it: less ground to remove, less light on the lens.
        .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
        .contentBlocking(
            ContentBlocking.Settings.Builder()
                // Firefox's standard protection: no ad, analytics, social, cryptomining or fingerprinting
                // trackers, cookies kept per site. Less to load over the watch's connection, less clutter.
                .antiTracking(
                    ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.CRYPTOMINING or
                        ContentBlocking.AntiTracking.FINGERPRINTING,
                )
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .build(),
        )
        // A tap is a click, not a zoom; a field with the cursor does not zoom in either.
        .doubleTapZoomingEnabled(false)
        .inputAutoZoomEnabled(false)
        .loginAutofillEnabled(false)
        .webManifest(false)
        .consoleOutput(false)
        .remoteDebuggingEnabled(false)
        .aboutConfigEnabled(false)
        .build()

    companion object {
        const val EXTENSION_URI = "resource://android/assets/webbridge/"
        const val EXTENSION_ID = "g2web@madtreasures.ch"
    }
}

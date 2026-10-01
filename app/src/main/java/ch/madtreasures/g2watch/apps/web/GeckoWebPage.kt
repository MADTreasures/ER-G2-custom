package ch.madtreasures.g2watch.apps.web

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.graphics.scale
import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One page of [GeckoWebEngine]: a GeckoSession without a view that paints into an unseen
 * [ImageReader] surface of the block's size. When [CapturePlan] says so, it holds the page still
 * ("freeze"), asks the extension for the layout, captures the surface, captures it again without
 * text ("hide-text") and lets [WebPicture] make the glasses' picture; a picture equal to the last is
 * not sent again. Taps go to GeckoView as touch events (like a finger), scrolling and typing through
 * the extension. Everything here runs on the main thread; the [WebPage] methods may be called from
 * any thread and post there.
 */
internal class GeckoWebPage(
    private val engine: GeckoWebEngine,
    request: WebRequest,
    private val listener: WebListener,
) : WebPage {
    private val width = request.width
    private val height = request.height
    private val scope = MainScope()
    private val plan = CapturePlan()

    // Main thread only.
    private var session: GeckoSession? = null
    private var display: GeckoDisplay? = null
    private var surface: ImageReader? = null
    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var ready = false
    private var released = false
    private var active = true
    private var composited = false
    private var readerWanted = request.reader
    private var contrast = request.contrast

    /** Loaded once the extension is there. */
    private var pending: String? = request.url
    private var status = WebStatus(WebState.LOADING, request.url)
    private var reported: WebStatus? = null
    private var capturing = false
    private var wake: Job? = null
    private var nextId = 1L
    private val replies = HashMap<Long, CompletableDeferred<PageMessage>>()
    private var lastPicture: ByteArray? = null

    private fun now() = SystemClock.uptimeMillis()

    // --- WebPage, any thread ----------------------------------------------------------------------

    override fun open(url: String, reader: Boolean) = onMain {
        readerWanted = reader
        load(url)
    }

    override fun back() = onMain { (session ?: return@onMain revive()).goBack() }

    override fun forward() = onMain { (session ?: return@onMain revive()).goForward() }

    override fun reload() = onMain { (session ?: return@onMain revive()).reload() }

    override fun scrollBy(dy: Int) = onMain {
        if (!send(WebBridge.scroll(WebPicture.cssPixels(dy)))) {
            // No script on this page (an error page): GeckoView scrolls it itself.
            session?.panZoomController?.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble()))
        }
        acted(CapturePlan.AFTER_SCROLL_MS)
    }

    override fun tap(x: Int, y: Int) = onMain {
        val pz = session?.panZoomController ?: return@onMain
        val down = SystemClock.uptimeMillis()
        pz.onTouchEvent(touch(down, down, MotionEvent.ACTION_DOWN, x, y))
        engine.main.postDelayed({
            if (!released) session?.panZoomController?.onTouchEvent(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
        }, TAP_MS)
        acted(CapturePlan.AFTER_TAP_MS)
    }

    override fun type(text: String, enter: Boolean) = onMain {
        if (!send(WebBridge.type(text, enter))) note("Hier lässt sich nichts eingeben")
        acted(CapturePlan.AFTER_TYPE_MS)
    }

    /** The next picture follows the extension's answer (on) or the new load (off). */
    override fun setReader(on: Boolean) = onMain {
        readerWanted = on
        when {
            on && !status.reader -> if (!send(WebBridge.reader(true))) note("Diese Seite hat keinen Lesemodus")
            // Back to the page as it is: load it again.
            !on && status.reader -> session?.reload()
        }
    }

    override fun setContrast(contrast: WebContrast) = onMain {
        this.contrast = contrast
        lastPicture = null
        acted(CapturePlan.AFTER_SETTING_MS)
    }

    override fun setActive(active: Boolean) = onMain {
        this.active = active
        session?.let(::applyActive)
        plan.activate(now(), active)
        schedule()
    }

    override fun release() {
        engine.main.post {
            if (released) return@post
            released = true
            wake?.cancel()
            scope.cancel()
            replies.clear()
            closeSession()
        }
    }

    private fun onMain(block: () -> Unit) {
        engine.main.post { if (!released) block() }
    }

    // --- Session ----------------------------------------------------------------------------------

    /** First call on the main thread: engine and extension, then the first address. */
    fun start() {
        if (released) return
        try {
            engine.runtime()
        } catch (e: RuntimeException) {
            fail("Browser-Engine startet nicht: ${e.message}")
            return
        }
        scope.launch {
            extension = try {
                engine.extension().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engine.extensionFailed()
                engine.note("Erweiterung fehlt (${e.message}) – Bild nur aus den Pixeln")
                null
            }
            ready = true
            pending?.let(::load)
        }
    }

    private fun load(url: String) {
        if (!ready) {
            pending = url
            return
        }
        pending = null
        status = status.copy(url = url)
        (session ?: openSession() ?: return).loadUri(url)
    }

    /** After a crash: a new session with the last address. */
    private fun revive() {
        if (ready && session == null) load(status.url)
    }

    private fun openSession(): GeckoSession? {
        val runtime = try {
            engine.runtime()
        } catch (e: RuntimeException) {
            fail("Browser-Engine startet nicht: ${e.message}")
            return null
        }
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .suspendMediaWhenInactive(true)
                .build(),
        )
        s.progressDelegate = progress
        s.navigationDelegate = navigation
        s.contentDelegate = content
        s.promptDelegate = prompts
        s.permissionDelegate = permissions
        extension?.let { s.webExtensionController.setMessageDelegate(it, messages, WebBridge.NATIVE_APP) }
        s.open(runtime)
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        // The surface only keeps the compositor going; captures read the compositor, not these frames.
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, engine.surfaces)
        val d = s.acquireDisplay()
        d.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(reader.surface).size(width, height).build())
        s.setFocused(true)
        applyActive(s)
        session = s
        display = d
        surface = reader
        composited = false
        return s
    }

    /** Without a visible view Gecko would throttle the page; a resting page may be throttled (05 §5). */
    private fun applyActive(s: GeckoSession) {
        s.setActive(active)
        s.setPriorityHint(if (active) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        port = null
        display?.let { d ->
            d.surfaceDestroyed()
            s.releaseDisplay(d)
        }
        display = null
        s.close()
        surface?.close()
        surface = null
    }

    /** The page's process ended; the next action opens it again. */
    private fun lost(message: String) {
        closeSession()
        status = status.copy(state = WebState.ERROR, field = null, message = message)
        report()
    }

    private fun fail(message: String) {
        status = status.copy(state = WebState.ERROR, message = message)
        report()
    }

    // --- Status -----------------------------------------------------------------------------------

    /** Tells the host what changed; progress in tenths is enough for a status line. */
    private fun report() {
        if (released) return
        val shown = status.copy(progress = if (status.progress >= 100) 100 else status.progress / 10 * 10)
        if (shown == reported) return
        reported = shown
        listener.onState(shown)
        // A note (a link the watch does not follow, an alert) is told once.
        if (status.state != WebState.ERROR && status.message != null) status = status.copy(message = null)
    }

    private fun note(message: String) {
        status = status.copy(message = message)
        report()
    }

    // --- Pictures ---------------------------------------------------------------------------------

    private fun acted(delayMs: Long) {
        plan.acted(now(), delayMs)
        schedule()
    }

    private fun schedule() {
        if (released) return
        wake?.cancel()
        val at = plan.nextAt() ?: return
        wake = scope.launch {
            delay((at - now()).coerceAtLeast(0))
            pump()
        }
    }

    /** Takes a picture if one is due; the capture runs as its own job, so a new plan does not cut it off. */
    private fun pump() {
        if (capturing || display == null) return
        if (!plan.take(now())) {
            schedule()
            return
        }
        capturing = true
        scope.launch {
            try {
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engine.note("Bild der Seite fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                capturing = false
            }
            schedule()
        }
    }

    private suspend fun capture() {
        val d = display ?: return
        // Animations and videos held, so both captures show the same moment.
        val frozen = stage("freeze")
        try {
            val layout = (request { WebBridge.layoutRequest(it) } as? PageMessage.Layout)?.layout
            // Pixels exist only once the compositor has drawn into the surface.
            withTimeoutOrNull(COMPOSITE_WAIT_MS) { while (!composited) delay(50) }
            val page = pixels(d) ?: return
            // The same moment without text: the difference is exactly the glyphs (05 §10.1).
            var textless: IntArray? = null
            if (frozen && stage("hide-text")) {
                delay(COMPOSITE_MS)
                textless = pixels(d)
            }
            val style = contrast
            val raster = withContext(engine.work) { WebPicture.raster(width, height, page, layout, textless, style) }
            if (released) return
            if (!raster.pixels.contentEquals(lastPicture)) {
                lastPicture = raster.pixels.copyOf()
                listener.onFrame(raster)
            }
        } finally {
            if (frozen) stage("restore")
        }
    }

    /** One stage of the double capture; true once the page has painted it. */
    private suspend fun stage(name: String): Boolean = request { WebBridge.stage(it, name) } is PageMessage.Staged

    /** The surface as ARGB of the picture's size; `capturePixels` fails while the compositor is not ready. */
    private suspend fun pixels(d: GeckoDisplay): IntArray? {
        var bitmap: Bitmap? = null
        for (attempt in 1..CAPTURE_TRIES) {
            bitmap = try {
                d.capturePixels().await()
            } catch (e: IllegalStateException) {
                null
            }
            if (bitmap != null || attempt == CAPTURE_TRIES) break
            delay(CAPTURE_RETRY_MS)
        }
        val b = bitmap ?: return null
        val fitting = if (b.width == width && b.height == height) b else b.scale(width, height)
        val argb = IntArray(width * height)
        fitting.getPixels(argb, 0, width, 0, 0, width, height)
        if (fitting !== b) fitting.recycle()
        b.recycle()
        return argb
    }

    // --- The extension ----------------------------------------------------------------------------

    /** Sends a message to the page's script; false when no script runs there. */
    private fun send(text: String): Boolean {
        val p = port ?: return false
        p.postMessage(JSONObject().put(WebBridge.FIELD, text))
        return true
    }

    /** A message with an id and its answer, or null after [REPLY_MS] (or without a script). */
    private suspend fun request(message: (Long) -> String): PageMessage? {
        val id = nextId++
        val answer = CompletableDeferred<PageMessage>()
        replies[id] = answer
        try {
            if (!send(message(id))) return null
            return withTimeoutOrNull(REPLY_MS) { answer.await() }
        } finally {
            replies.remove(id)
        }
    }

    private fun received(m: PageMessage) {
        when (m) {
            is PageMessage.Hello -> send(WebBridge.config(readerWanted))
            is PageMessage.Layout -> replies[m.id]?.complete(m)
            is PageMessage.Staged -> replies[m.id]?.complete(m)
            is PageMessage.Field -> {
                status = status.copy(field = m.field)
                report()
            }
            is PageMessage.Reader -> {
                status = status.copy(reader = m.on, readable = m.readable)
                plan.readerDecided(now())
                report()
                schedule()
            }
            PageMessage.Changed -> {
                plan.changed(now())
                schedule()
            }
        }
    }

    private val messages = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            // Every document has its own script and port; only the newest counts.
            this@GeckoWebPage.port = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (released || port !== this@GeckoWebPage.port) return
                    val text = (message as? JSONObject)?.optString(WebBridge.FIELD).orEmpty()
                    WebBridge.decode(text)?.let(::received)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (this@GeckoWebPage.port === port) this@GeckoWebPage.port = null
                }
            })
        }
    }

    // --- GeckoView's delegates (main thread) ------------------------------------------------------

    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (released || url == "about:blank") return
            val error = url.startsWith("data:")
            // Gecko's error page keeps the failed address and the error.
            if (!error) {
                status = status.copy(state = WebState.LOADING, url = url, progress = 0, reader = false, readable = false, field = null, message = null)
            }
            plan.started(now(), waitForReader = readerWanted && !error)
            report()
            schedule()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (released) return
            status = status.copy(progress = progress.coerceIn(0, 100))
            plan.progressed(now())
            report()
            schedule()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (released) return
            if (status.state == WebState.LOADING) status = status.copy(state = WebState.READY, progress = 100)
            plan.stopped(now())
            report()
            schedule()
        }
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (released || url == null || url == "about:blank" || url.startsWith("data:")) return
            status = status.copy(url = url)
            report()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            status = status.copy(canBack = canGoBack)
            report()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            status = status.copy(canForward = canGoForward)
            report()
        }

        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val scheme = WebErrors.foreignScheme(request.uri) ?: return GeckoResult.allow()
            note(WebErrors.foreignLink(scheme))
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // A link for a new window opens here: the glasses show one page at a time.
            engine.main.post { if (!released) this@GeckoWebPage.session?.loadUri(uri) }
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
            val message = WebErrors.text(error.category, error.code)
            status = status.copy(state = WebState.ERROR, url = uri ?: status.url, progress = 100, field = null, message = message)
            report()
            return GeckoResult.fromValue(WebErrors.page(message, uri.orEmpty()))
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            status = status.copy(title = title.orEmpty().trim())
            report()
        }

        override fun onFirstComposite(session: GeckoSession) {
            composited = true
        }

        override fun onFirstContentfulPaint(session: GeckoSession) {
            plan.painted(now())
            schedule()
        }

        override fun onCrash(session: GeckoSession) = lost("Die Seite ist abgestürzt")

        override fun onKill(session: GeckoSession) = lost("Wear OS hat die Seite beendet (zu wenig Speicher?)")

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            response.body?.close()
            note("Herunterladen geht auf der Uhr nicht")
        }

        override fun onSlowScript(session: GeckoSession, scriptFileName: String): GeckoResult<SlowScriptResponse> =
            GeckoResult.fromValue(SlowScriptResponse.STOP)
    }

    /** Nothing on the page can ask the wearer: dialogs close, leaving a page is always allowed. */
    private val prompts = object : GeckoSession.PromptDelegate {
        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            prompt.message?.takeIf { it.isNotBlank() }?.let { note("Die Seite meldet: ${it.trim().take(80)}") }
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onAuthPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
        ): GeckoResult<PromptResponse> = GeckoResult.fromValue(prompt.dismiss())
    }

    /** Pages get nothing: no location, camera, microphone, notifications or storage beyond the usual. */
    private val permissions = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) = callback.reject()

        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> =
            GeckoResult.fromValue(ContentPermission.VALUE_DENY)

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) = callback.reject()
    }

    /** A finger on the surface at ([x], [y]). */
    private fun touch(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.toFloat()
            this.y = y.toFloat()
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
        accept(
            { value -> if (cont.isActive) cont.resume(value) },
            { error -> if (cont.isActive) cont.resumeWithException(error ?: IllegalStateException("GeckoResult")) },
        )
    }

    private companion object {
        /** A finger rests this long on the page for a tap. */
        const val TAP_MS = 60L

        /** How long the page's script may take to answer. */
        const val REPLY_MS = 3_000L
        const val COMPOSITE_WAIT_MS = 3_000L

        /** After the page painted a stage, until the compositor has it. */
        const val COMPOSITE_MS = 120L
        const val CAPTURE_TRIES = 6
        const val CAPTURE_RETRY_MS = 300L
    }
}

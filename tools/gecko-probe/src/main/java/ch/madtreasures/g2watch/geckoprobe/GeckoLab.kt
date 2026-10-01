package ch.madtreasures.g2watch.geckoprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import ch.madtreasures.g2watch.webraster.GlassesRasterizer
import ch.madtreasures.g2watch.webraster.LayoutParser
import ch.madtreasures.g2watch.webraster.Levels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The measurements of M2 (05 §5.1) on the watch's main thread: GeckoView without a visible view,
 * the bridge Even Hub apps use (as a built-in extension), three test apps from a local server,
 * timers with the screen on and off, memory, battery, a 30-minute run, and the render path of
 * the future browser (an unseen surface, captured and turned into the glasses' raster).
 */
class GeckoLab(private val app: Context) {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val _results = MutableStateFlow(ProbeResults(device = DeviceProbe.info(app), geckoVersion = BuildConfig.GECKOVIEW_VERSION))
    val results: StateFlow<ProbeResults> = _results.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Name of the test that runs, or null. */
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running.asStateFlow()

    /** What the wearer should do now, e.g. let the screen go dark. */
    private val _instruction = MutableStateFlow<String?>(null)
    val instruction: StateFlow<String?> = _instruction.asStateFlow()

    /** The activity keeps the screen on while this is true (quick and render test, first half of the timer test). */
    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    /** The latest picture: the canvas app's image or the rendered page as the glasses show it. */
    private val _preview = MutableStateFlow<Pair<String, Bitmap>?>(null)
    val preview: StateFlow<Pair<String, Bitmap>?> = _preview.asStateFlow()

    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private val server = AssetServer { path -> readAsset("probe-apps/$path") }
    private val sessions = LinkedHashMap<String, GeckoSession>()
    private val ports = HashMap<String, WebExtension.Port>()
    private val timerReports = ArrayList<TimerReport>()
    private var job: Job? = null
    private var nextLayoutId = 1L

    // Every bridge event with a sequence number, so a test can wait for what came after a point.
    private val history = ArrayList<Pair<Long, BridgeEvent>>()
    private val latest = MutableStateFlow(0L)

    // --- Tests ---------------------------------------------------------------------------------------

    /** Engine, bridge and the three test apps; cold start, round trip, memory. About a minute. */
    fun quickTest() = start("Schnelltest", screenOn = true) {
        val cold = runtime == null
        val t0 = clock()
        val rt = ensureRuntime()
        if (cold) _results.update { it.copy(runtimeMs = clock() - t0) }
        ensureExtension(rt)
        server.start()
        _results.update { it.copy(apps = linkedMapOf(TEXT to null, CANVAS to null, VUE to null), appDetails = emptyMap()) }

        var since = mark()
        openApp("text")
        val first = awaitCall(since, 30_000) { it.app == "text" && it.method == "createStartUpPageContainer" }
        if (first == null) {
            appDone(TEXT, "keine Antwort in 30 s")
        } else {
            val startMs = clock() - t0
            if (cold) _results.update { it.copy(coldStartMs = startMs) } else note("Warmstart bis erster Aufruf: $startMs ms (Kaltstart nur beim ersten Lauf nach dem App-Start)")
            since = mark()
            val token = "t${clock()}"
            val sent = clock()
            push("text", "evenHubEvent", buildJsonObject { put("token", token); putJsonObject("sysEvent") { put("eventType", 0) } })
            val echo = awaitCall(since, 10_000) { it.app == "text" && it.method == "textContainerUpgrade" && (it.data["echo"] as? JsonPrimitive)?.content == token }
            if (echo != null) _results.update { it.copy(roundTripMs = clock() - sent) }
            appDone(TEXT, if (echo != null) "" else "kein Echo auf das Ereignis")
        }

        since = mark()
        openApp("canvas")
        val image = awaitCall(since, 30_000) { it.app == "canvas" && it.method == "updateImageRawData" }
        if (image == null) {
            appDone(CANVAS, "keine Antwort in 30 s")
        } else {
            val shown = gray4Preview(image)
            appDone(CANVAS, if (shown) "" else "Bild unlesbar")
            val report = awaitCall(since, 5_000) { it.app == "canvas" && it.method == "probeReport" }
            report?.let { detail(CANVAS, "gezeichnet ${ms(it, "drawnMs")}, gepackt ${ms(it, "encodedMs")}") }
        }

        since = mark()
        openApp("vue-wasm")
        val vue = awaitCall(since, 30_000) { it.app == "vue-wasm" && it.method == "probeReport" }
        when {
            vue == null -> appDone(VUE, "keine Antwort in 30 s")
            vue.data["error"] != null -> appDone(VUE, (vue.data["error"] as? JsonPrimitive)?.content ?: "Fehler")
            (vue.data["fib30"] as? JsonPrimitive)?.intOrNull != 832_040 -> appDone(VUE, "WebAssembly rechnet falsch")
            else -> {
                appDone(VUE, "")
                detail(VUE, "Vue ${(vue.data["vue"] as? JsonPrimitive)?.content}, WebAssembly ${ms(vue, "wasmMs")}, fertig ${ms(vue, "mountedMs")}")
            }
        }

        // Memory with all three apps loaded and settled.
        delay(3_000)
        samplePss()
        note("Prozesse: " + DeviceProbe.processes(app).joinToString())
        closeApps()
    }

    /** A 100 ms interval for 2 minutes with the screen on, then 2 minutes with the screen off. */
    fun timerTest() = start("Timer-Test", screenOn = true) {
        ensureExtension(ensureRuntime())
        server.start()
        timerReports.clear()
        openApp("timer")
        _instruction.value = "Bildschirm an lassen (2 min)"
        delay(PHASE_MS)
        _keepScreenOn.value = false
        vibrate()
        _instruction.value = "Jetzt Handgelenk senken: Bildschirm aus (2 min)"
        delay(PHASE_MS)
        vibrate()
        _instruction.value = null
        val on = TimerSummary.of(timerReports.filter { it.screenOn })
        val off = TimerSummary.of(timerReports.filter { !it.screenOn })
        _results.update { it.copy(timerOn = on ?: it.timerOn, timerOff = off ?: it.timerOff) }
        if (off == null) note("Keine Messung mit Bildschirm aus – bitte wiederholen und die Uhr ruhen lassen")
        closeApps()
    }

    /** 30 minutes with the text and timer apps running: losses, memory peak, battery per hour. */
    fun enduranceTest() = start("Dauertest", screenOn = false) {
        ensureExtension(ensureRuntime())
        server.start()
        if (DeviceProbe.charging(app)) note("Achtung: Die Uhr lädt – der Akku-Wert ist so nicht brauchbar")
        timerReports.clear()
        openApp("text")
        openApp("timer")
        val battery = arrayListOf(DeviceProbe.battery(app, clock()))
        val lossesBefore = _results.value.losses.size
        for (minute in 1..Limits.ENDURANCE_MINUTES) {
            _instruction.value = "Dauertest: $minute/${Limits.ENDURANCE_MINUTES} min – Uhr einfach tragen"
            delay(60_000)
            battery += DeviceProbe.battery(app, clock())
            samplePss()
            if (_results.value.losses.size > lossesBefore) note("Abbruch in Minute $minute")
        }
        _instruction.value = null
        val timers = TimerSummary.of(timerReports)
        timers?.let { note("Timer im Dauertest: ${(it.deviation * 100).toInt()} % Abweichung, längste Lücke ${it.maxGapMs.toInt()} ms") }
        _results.update { it.copy(enduranceMinutes = Limits.ENDURANCE_MINUTES, batteryPerHour = BatteryEstimate.perHour(battery)) }
        closeApps()
    }

    /**
     * The browser path of M7: GeckoView paints [url] (default: a local test page) into an unseen
     * 576 × 260 surface at 1.5 device pixels per CSS pixel, the content script reports text,
     * pictures and backgrounds, and web-raster turns the capture into the glasses' picture.
     */
    fun renderTest(url: String? = null) = start("Seite rendern", screenOn = true) {
        val rt = ensureRuntime()
        val ext = ensureExtension(rt)
        server.start()
        val target = url ?: server.url("render/index.html")
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .contextId("render")
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build(),
        )
        // The session opens with about:blank; only a stop after the target started counts.
        var started = false
        var stopped = false
        var composited = false
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (url == "about:blank") return
                started = true
                stopped = false
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (started) stopped = true
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstComposite(session: GeckoSession) {
                composited = true
            }

            override fun onCrash(session: GeckoSession) = loss("render abgestürzt (onCrash)")

            override fun onKill(session: GeckoSession) = loss("render vom System beendet (onKill)")
        }
        session.webExtensionController.setMessageDelegate(ext, messageDelegate("render"), NATIVE_APP)
        session.open(rt)
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        // The unseen surface only has to keep the compositor going; its frames are dropped.
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val display = session.acquireDisplay()
        display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(reader.surface).size(WIDTH, HEIGHT).build())
        session.setActive(true)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        try {
            val t0 = clock()
            session.loadUri(target)
            withTimeoutOrNull(30_000) { while (!stopped) delay(50) }
            val loadMs = clock() - t0
            if (!stopped) note("Seite lud nicht fertig in 30 s – nehme, was da ist")
            delay(SETTLE_MS)

            // Animations and videos held, so both captures show the same moment.
            val frozen = stage("freeze")
            val id = nextLayoutId++
            val lt0 = clock()
            val since = mark()
            ports["render"]?.postMessage(wrap(BridgeProtocol.layoutRequest(id)))
            val layout = await<BridgeEvent.LayoutReady>(since, 5_000) { it.app == "render" && it.id == id }
            val layoutMs = clock() - lt0
            if (layout == null) note("Kein Layout vom Inhalts-Skript – Raster nur aus den Pixeln")

            // Pixels exist only once the compositor has drawn into the surface.
            withTimeoutOrNull(5_000) { while (!composited) delay(50) }
            val ct0 = clock()
            val bitmap = capture(display) ?: error("capturePixels lieferte nichts")
            val captureMs = clock() - ct0
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            // The same moment without text: the difference is exactly the glyphs (outlines on photos).
            var textless: IntArray? = null
            var bare: Bitmap? = null
            if (frozen && stage("hide-text")) {
                delay(COMPOSITE_MS)
                bare = capture(display)
                bare?.takeIf { it.width == w && it.height == h }?.let { b -> textless = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
            }
            stage("restore")
            if (textless == null) note("Keine Aufnahme ohne Schrift – Buchstaben nur geschätzt")
            val raster = withContext(Dispatchers.Default) { GlassesRasterizer.rasterize(LayoutParser.capture(w, h, argb, layout?.layout, textless)) }
            val glasses = greenBitmap(raster.width, raster.height) { raster.pixels[it].toInt() and 0xFF }
            _preview.value = "Brille: $target" to glasses
            saveImages(bitmap, glasses, bare)
            val r = raster.report
            _results.update {
                it.copy(render = RenderResult(target, loadMs, captureMs, layoutMs, r.millis, w, h, r.textRuns, r.negativeRuns, r.overloaded))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _results.update { it.copy(render = RenderResult(target, 0, 0, 0, 0.0, WIDTH, HEIGHT, 0, 0, false, error = "${e.javaClass.simpleName}: ${e.message}")) }
        } finally {
            display.surfaceDestroyed()
            session.releaseDisplay(display)
            session.close()
            reader.close()
            ports.remove("render")
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** One stage of the double capture in the render session; true once the page has painted it. */
    private suspend fun stage(name: String): Boolean {
        val port = ports["render"] ?: return false
        val id = nextLayoutId++
        val since = mark()
        port.postMessage(wrap(BridgeProtocol.stage(id, name)))
        return await<BridgeEvent.Staged>(since, 3_000) { it.app == "render" && it.id == id } != null
    }

    /** `capturePixels` fails while the compositor is not ready yet; a few tries, half a second apart. */
    private suspend fun capture(display: GeckoDisplay): Bitmap? {
        repeat(CAPTURE_TRIES - 1) {
            try {
                return display.capturePixels().await()
            } catch (e: IllegalStateException) {
                delay(500)
            }
        }
        return display.capturePixels().await()
    }

    /** The report for the chat: all values, then the log. */
    fun saveReport(): File {
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val file = File(dir, REPORT)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        file.writeText(
            buildString {
                appendLine("G2 Gecko-Test ${BuildConfig.VERSION_NAME} · $stamp")
                _results.value.lines().forEach { appendLine(it) }
                appendLine()
                appendLine("Protokoll:")
                _log.value.forEach { appendLine(it) }
            },
        )
        return file
    }

    // --- Engine --------------------------------------------------------------------------------------

    private fun ensureRuntime(): GeckoRuntime = runtime ?: GeckoRuntime.create(
        app,
        GeckoRuntimeSettings.Builder()
            // 05 §5: few processes on the watch; isolated ones off so their memory can be read.
            .fissionEnabled(false)
            .extensionsProcessEnabled(false)
            .isolatedProcessEnabled(false)
            .consoleOutput(true)
            // 576 device pixels are 384 CSS pixels: pages lay out like on a small phone.
            .displayDensityOverride(DENSITY)
            .build(),
    ).also { rt ->
        rt.delegate = GeckoRuntime.Delegate { loss("Engine beendet (onShutdown)") }
        runtime = rt
        note("GeckoView ${BuildConfig.GECKOVIEW_VERSION} gestartet")
    }

    private suspend fun ensureExtension(rt: GeckoRuntime): WebExtension = extension
        ?: (rt.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).await() ?: error("Brücke nicht installiert")).also {
            extension = it
            note("Brücke (Erweiterung) installiert")
        }

    private fun openApp(name: String): GeckoSession {
        sessions.remove(name)?.close()
        val session = GeckoSession(GeckoSessionSettings.Builder().contextId(name).build())
        session.contentDelegate = lossDelegate(name)
        extension?.let { session.webExtensionController.setMessageDelegate(it, messageDelegate(name), NATIVE_APP) }
        session.open(runtime ?: error("Engine fehlt"))
        // Without a visible view Gecko would throttle the page; the watch app will do the same (05 §5).
        session.setActive(true)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        session.loadUri(server.url("$name/index.html"))
        sessions[name] = session
        return session
    }

    private fun closeApps() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        ports.clear()
    }

    private fun lossDelegate(name: String) = object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) = loss("$name abgestürzt (onCrash)")

        override fun onKill(session: GeckoSession) = loss("$name vom System beendet (onKill)")
    }

    private fun messageDelegate(name: String): WebExtension.MessageDelegate {
        val bridge = ProbeBridge(name) { record(it) }
        return object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                ports[name] = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val text = (message as? JSONObject)?.optString(BridgeProtocol.FIELD).orEmpty()
                        bridge.receive(text)?.let { port.postMessage(wrap(it)) }
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (ports[name] === port) ports.remove(name)
                    }
                })
            }
        }
    }

    private fun push(name: String, method: String, data: kotlinx.serialization.json.JsonObject) {
        ports[name]?.postMessage(wrap(BridgeProtocol.push(method, data))) ?: note("$name: keine Verbindung zur App")
    }

    /** A port message: the JSON text in one string field (see [BridgeProtocol]). */
    private fun wrap(text: String): JSONObject = JSONObject().put(BridgeProtocol.FIELD, text)

    // --- Events --------------------------------------------------------------------------------------

    private fun record(e: BridgeEvent) {
        val seq = latest.value + 1
        history += seq to e
        if (history.size > MAX_HISTORY) history.removeAt(0)
        if (e is BridgeEvent.Called && e.method == "timerReport") {
            val d = e.data
            timerReports += TimerReport(
                count = (d["count"] as? JsonPrimitive)?.intOrNull ?: 0,
                meanMs = (d["meanMs"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                maxGapMs = (d["maxGapMs"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                late = (d["late"] as? JsonPrimitive)?.intOrNull ?: 0,
                screenOn = app.getSystemService(PowerManager::class.java).isInteractive,
            )
        }
        latest.value = seq
    }

    private fun mark(): Long = latest.value

    private suspend inline fun <reified E : BridgeEvent> await(since: Long, timeoutMs: Long, crossinline match: (E) -> Boolean): E? =
        withTimeoutOrNull(timeoutMs) {
            latest.map { _ -> history.firstOrNull { (seq, e) -> seq > since && e is E && match(e) }?.second as E? }.filterNotNull().first()
        }

    private suspend fun awaitCall(since: Long, timeoutMs: Long, match: (BridgeEvent.Called) -> Boolean): BridgeEvent.Called? =
        await<BridgeEvent.Called>(since, timeoutMs) { match(it) }

    // --- Results -------------------------------------------------------------------------------------

    private fun appDone(name: String, problem: String) {
        _results.update { it.copy(apps = it.apps + (name to problem)) }
        note("$name: " + problem.ifEmpty { "in Ordnung" })
    }

    private fun detail(name: String, text: String) {
        _results.update { it.copy(appDetails = it.appDetails + (name to text)) }
    }

    private fun samplePss() {
        val pss = DeviceProbe.pssMb(app) ?: return
        _results.update { it.copy(pssMb = pss, pssPeakMb = maxOf(it.pssPeakMb ?: 0, pss)) }
    }

    private fun loss(what: String) {
        _results.update { it.copy(losses = it.losses + what) }
        note("ABBRUCH: $what")
    }

    private fun note(line: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _log.update { (it + "$stamp $line").takeLast(200) }
    }

    private fun ms(call: BridgeEvent.Called, key: String): String =
        (call.data[key] as? JsonPrimitive)?.doubleOrNull?.let { "${it.toInt()} ms" } ?: "–"

    /** The canvas app's 4-bit picture as the glasses would show it. */
    private fun gray4Preview(call: BridgeEvent.Called): Boolean {
        val w = (call.data["width"] as? JsonPrimitive)?.intOrNull ?: return false
        val h = (call.data["height"] as? JsonPrimitive)?.intOrNull ?: return false
        val data = (call.data["data"] as? JsonPrimitive)?.content ?: return false
        val packed = try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (packed.size < w * h / 2) return false
        _preview.value = "Canvas-App (4 Bit)" to greenBitmap(w, h) { i ->
            val b = packed[i / 2].toInt() and 0xFF
            Levels.gray(if (i % 2 == 0) b shr 4 else b and 0x0F)
        }
        return true
    }

    private fun greenBitmap(w: Int, h: Int, gray: (Int) -> Int): Bitmap {
        val px = IntArray(w * h) { i ->
            val l = Levels.of(gray(i)) * 17
            (0xFF shl 24) or ((0x7C * l / 255) shl 16) or ((0xFF * l / 255) shl 8) or (0xA0 * l / 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun saveImages(page: Bitmap, glasses: Bitmap, bare: Bitmap?) {
        val dir = app.getExternalFilesDir(null) ?: return
        try {
            FileOutputStream(File(dir, "render-seite.png")).use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bare?.let { b -> FileOutputStream(File(dir, "render-ohne-schrift.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            FileOutputStream(File(dir, "render-brille.png")).use { glasses.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: IOException) {
            note("Bilder nicht gespeichert: ${e.message}")
        }
    }

    private fun vibrate() {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun readAsset(path: String): ByteArray? = try {
        app.assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    /** Runs one test at a time; [screenOn] keeps the watch screen on until the test changes it. */
    private fun start(name: String, screenOn: Boolean, body: suspend () -> Unit) {
        if (job?.isActive == true) return
        _running.value = name
        _keepScreenOn.value = screenOn
        ProbeService.start(app, name)
        note("— $name —")
        job = scope.launch {
            try {
                body()
            } catch (e: CancellationException) {
                note("$name abgebrochen")
                closeApps()
                throw e
            } catch (e: Exception) {
                note("Fehler: ${e.javaClass.simpleName}: ${e.message}")
                closeApps()
            } finally {
                _running.value = null
                _keepScreenOn.value = false
                _instruction.value = null
                ProbeService.stop(app)
            }
        }
    }

    private suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
        accept(
            { value -> if (cont.isActive) cont.resume(value) },
            { error -> if (cont.isActive) cont.resumeWithException(error ?: IllegalStateException("GeckoResult")) },
        )
    }

    companion object {
        const val WIDTH = 576
        const val HEIGHT = 260
        const val DENSITY = 1.5f
        const val PHASE_MS = 120_000L
        const val SETTLE_MS = 1_500L

        /** After the page painted a stage, until the compositor has it in the surface. */
        const val COMPOSITE_MS = 120L
        const val CAPTURE_TRIES = 6
        const val REPORT = "g2-gecko-bericht.txt"
        const val NATIVE_APP = "g2probe"
        const val EXTENSION_URI = "resource://android/assets/probe-bridge/"
        const val EXTENSION_ID = "g2probe@madtreasures.ch"
        const val MAX_HISTORY = 500
        const val TEXT = "Text"
        const val CANVAS = "Canvas"
        const val VUE = "Vue+WASM"
    }
}

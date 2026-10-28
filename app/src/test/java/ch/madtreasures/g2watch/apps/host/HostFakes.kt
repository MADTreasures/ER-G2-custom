package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.assetFile
import ch.madtreasures.g2watch.apps.files.DownloadEngine
import ch.madtreasures.g2watch.apps.files.DownloadJob
import ch.madtreasures.g2watch.apps.files.DownloadListener
import ch.madtreasures.g2watch.apps.files.DownloadRequest
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.location.LocationEngine
import ch.madtreasures.g2watch.apps.media.MediaEngine
import ch.madtreasures.g2watch.apps.media.MediaLine
import ch.madtreasures.g2watch.apps.media.MediaListener
import ch.madtreasures.g2watch.apps.media.MediaSnapshot
import ch.madtreasures.g2watch.apps.media.StreamEngine
import ch.madtreasures.g2watch.apps.media.StreamListener
import ch.madtreasures.g2watch.apps.media.StreamPlayer
import ch.madtreasures.g2watch.apps.location.LocationLine
import ch.madtreasures.g2watch.apps.location.LocationListener
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebField
import ch.madtreasures.g2watch.apps.WebState
import ch.madtreasures.g2watch.apps.phone.PhoneEngine
import ch.madtreasures.g2watch.apps.phone.PhoneLine
import ch.madtreasures.g2watch.apps.phone.PhoneListener
import ch.madtreasures.g2watch.apps.video.VideoEngine
import ch.madtreasures.g2watch.apps.video.VideoListener
import ch.madtreasures.g2watch.apps.video.VideoPlayer
import ch.madtreasures.g2watch.apps.video.VideoRequest
import ch.madtreasures.g2watch.apps.web.WebEngine
import ch.madtreasures.g2watch.apps.web.WebListener
import ch.madtreasures.g2watch.apps.web.WebPage
import ch.madtreasures.g2watch.apps.web.WebRequest
import ch.madtreasures.g2watch.apps.web.WebStatus
import ch.madtreasures.g2watch.desktop.AppScreen
import ch.madtreasures.g2watch.desktop.AppView
import ch.madtreasures.g2watch.desktop.GrayRaster
import java.awt.color.ColorSpace
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Records what the host shows instead of drawing a desktop. */
class FakeScreen : AppScreen {
    val views = mutableListOf<AppView>()
    var closed = 0

    val last: AppView get() = views.last()

    override fun showApp(view: AppView) {
        views += view
    }

    override fun closeApp() {
        closed++
    }
}

/** In-memory platform: stores, permission answers, requests to answer, assets from src/main/assets or the test resources. */
class FakePorts : HostPorts {
    val stores = HashMap<String, FakeAppContext.MemoryStorage>()
    val answers = HashMap<String, Set<Permission>>()
    val vibrations = mutableListOf<Vibration>()
    val requests = mutableListOf<Pair<HttpRequest, (HttpResult) -> Unit>>()
    val logs = mutableListOf<String>()
    val assets = HashMap<String, ByteArray>()

    override fun storage(appId: String): AppStorage = stores.getOrPut(appId) { FakeAppContext.MemoryStorage() }

    override fun grantedPermissions(appId: String, version: String): Set<Permission>? = answers["$appId@$version"]

    override fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>) {
        answers["$appId@$version"] = granted
    }

    override fun vibrate(pattern: Vibration) {
        vibrations += pattern
    }

    override fun fetch(request: HttpRequest, done: (HttpResult) -> Unit) {
        requests += Pair(request, done)
    }

    override fun asset(path: String): ByteArray? = assets[path] ?: assetFile(path).takeIf { it.isFile }?.readBytes()

    /**
     * Like the watch: luminance times alpha, so transparent parts are black. Grey PNGs are read as they
     * are stored, as Android does; Java's getRGB would treat their grey as linear and brighten it.
     */
    override fun decodeImage(bytes: ByteArray): GrayRaster? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val out = GrayRaster(image.width, image.height)
        val model = image.colorModel
        val gray = model.colorSpace.type == ColorSpace.TYPE_GRAY
        for (y in 0 until image.height) for (x in 0 until image.width) {
            out[x, y] = if (gray) {
                fun sample(band: Int) = image.raster.getSample(x, y, band) * 255 / ((1 shl model.getComponentSize(band)) - 1)
                val alpha = if (model.hasAlpha()) sample(1) else 255
                sample(0) * alpha / 255
            } else {
                val c = image.getRGB(x, y)
                val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                luma * (c ushr 24 and 0xFF) / 255
            }
        }
        return out
    }

    override fun log(line: String) {
        logs += line
    }

    /** Open text questions: prompt, suggestions and how to answer them. */
    val questions = mutableListOf<Triple<String, List<String>, (String?) -> Unit>>()
    var textCancels = 0

    override fun askText(prompt: String, suggestions: List<String>, done: (String?) -> Unit) {
        questions += Triple(prompt, suggestions, done)
    }

    override fun cancelText() {
        textCancels++
    }

    /** A Bluetooth keyboard is connected. */
    override var hardwareKeyboard = false

    override val video = FakeVideoEngine()

    override val web = FakeWebEngine()

    override val phone = FakePhoneEngine()

    /** The apps' folders for large files (interface 5), under one temporary folder per test. */
    val filesRoot: File by lazy { kotlin.io.path.createTempDirectory("g2-app-data").toFile().apply { deleteOnExit() } }

    override fun files(appId: String): File? = File(filesRoot, appId).apply { mkdirs() }

    override val downloads = FakeDownloadEngine()

    override val location = FakeLocationEngine()

    /** Background tasks of the apps (interface 5), waiting until the test runs them with [runWork]. */
    val work = mutableListOf<Runnable>()

    override fun runWork(job: Runnable) {
        work += job
    }

    /** Runs the waiting background tasks, oldest first, until none is left. */
    fun runWork() {
        while (work.isNotEmpty()) work.removeAt(0).run()
    }

    override val media = FakeMediaEngine()

    override val streams = FakeStreamEngine()
}

/** The players on the watch for tests (interface 6): a test changes them with [change]; commands are kept. */
class FakeMediaEngine : MediaEngine {
    var snapshot = MediaSnapshot(emptyList(), 7, 15, "Kopfhörer: Pixel Buds")
    val listeners = mutableListOf<MediaListener>()
    val commands = mutableListOf<MediaAction>()

    /** What [command] answers: null when it worked, else a note for the wearer. */
    var note: String? = null

    override fun follow(listener: MediaListener): MediaLine {
        listeners += listener
        listener.onChange(snapshot)
        return MediaLine { listeners.remove(listener) }
    }

    override fun command(action: MediaAction): String? {
        commands += action
        return note
    }

    fun change(next: MediaSnapshot) {
        snapshot = next
        listeners.toList().forEach { it.onChange(next) }
    }
}

/** The apps' streams for tests; a test drives one through its [FakeStream.listener]. */
class FakeStreamEngine : StreamEngine {
    val opened = mutableListOf<FakeStream>()

    override fun open(url: String, title: String, listener: StreamListener): StreamPlayer = FakeStream(url, title, listener).also { opened += it }
}

class FakeStream(val url: String, val title: String, val listener: StreamListener) : StreamPlayer {
    var paused = false
        private set
    var released = false
        private set

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun release() {
        released = true
    }
}

/** Records the downloads the host starts; a test drives one through its [FakeDownload.listener]. */
class FakeDownloadEngine : DownloadEngine {
    val started = mutableListOf<FakeDownload>()

    override fun start(request: DownloadRequest, listener: DownloadListener): DownloadJob =
        FakeDownload(request, listener).also { started += it }
}

class FakeDownload(val request: DownloadRequest, val listener: DownloadListener) : DownloadJob {
    var cancelled = false
        private set

    override fun cancel() {
        cancelled = true
    }
}

/** Records the GPS lines the host opens; a test sends fixes through [FakeLocationLine.listener]. */
class FakeLocationEngine : LocationEngine {
    val lines = mutableListOf<FakeLocationLine>()

    override fun start(intervalMs: Long, listener: LocationListener): LocationLine =
        FakeLocationLine(intervalMs, listener).also { lines += it }
}

class FakeLocationLine(val intervalMs: Long, val listener: LocationListener) : LocationLine {
    var stopped = false
        private set

    override fun stop() {
        stopped = true
    }

    fun fix(lat: Double, lon: Double, acc: Float = 5f, t: Long = 1_000L, speed: Float? = null, bearing: Float? = null) =
        listener.onFix(AppEvent.Location(lat, lon, acc, t, speed, bearing))
}

/** Records the connections apps open to the phone; a test drives one through its [FakePhoneLine.listener]. */
class FakePhoneEngine : PhoneEngine {
    val lines = mutableListOf<FakePhoneLine>()

    override fun connect(app: String, version: String, partner: String, listener: PhoneListener): PhoneLine =
        FakePhoneLine(app, version, partner, listener).also { lines += it }
}

/** A connection to the phone that only records what it is told. */
class FakePhoneLine(val app: String, val version: String, val partner: String, val listener: PhoneListener) : PhoneLine {
    val sent = mutableListOf<String>()

    /** Every microphone(on) in order. */
    val mic = mutableListOf<Boolean>()
    var closed = false
        private set

    override fun send(text: String) {
        sent += text
    }

    override fun microphone(on: Boolean) {
        mic += on
    }

    override fun close() {
        closed = true
    }

    fun state(state: PhoneState, text: String? = null, message: String? = null) = listener.onState(state, text, message)

    fun notice(message: String) = listener.onNotice(message)
}

/** Records searches and players; a test drives a player through its [FakeVideoPlayer.listener]. */
class FakeVideoEngine : VideoEngine {
    val searches = mutableListOf<Pair<String, (VideoSearchResult) -> Unit>>()
    val players = mutableListOf<FakeVideoPlayer>()

    override fun search(query: String, done: (VideoSearchResult) -> Unit) {
        searches += Pair(query, done)
    }

    override fun open(request: VideoRequest, listener: VideoListener): VideoPlayer = FakeVideoPlayer(request, listener).also { players += it }
}

/** A player that only records what it is told. */
class FakeVideoPlayer(val request: VideoRequest, val listener: VideoListener) : VideoPlayer {
    val calls = mutableListOf<String>()
    var released = false
        private set

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek $positionMs"
    }

    override fun setProfile(profile: VideoProfile) {
        calls += "profile ${profile.json}"
    }

    override fun release() {
        released = true
        calls += "release"
    }

    fun state(state: VideoState, positionMs: Long = 0, durationMs: Long = 60_000, message: String? = null) =
        listener.onState(state, positionMs, durationMs, message)
}

/** Records the pages it opens; a test drives a page through its [FakeWebPage.listener]. */
class FakeWebEngine : WebEngine {
    val pages = mutableListOf<FakeWebPage>()

    override fun open(request: WebRequest, listener: WebListener): WebPage = FakeWebPage(request, listener).also { pages += it }
}

/** A web page that only records what it is told. */
class FakeWebPage(val request: WebRequest, val listener: WebListener) : WebPage {
    val calls = mutableListOf<String>()
    var active = true
        private set
    var released = false
        private set

    override fun open(url: String, reader: Boolean) {
        calls += "open $url${if (reader) " reader" else ""}"
    }

    override fun back() {
        calls += "back"
    }

    override fun forward() {
        calls += "forward"
    }

    override fun reload() {
        calls += "reload"
    }

    override fun scrollBy(dy: Int) {
        calls += "scroll $dy"
    }

    override fun tap(x: Int, y: Int) {
        calls += "tap $x $y"
    }

    override fun type(text: String, enter: Boolean) {
        calls += "type $text${if (enter) " enter" else ""}"
    }

    override fun key(key: String, shift: Boolean, ctrl: Boolean, alt: Boolean) {
        calls += "key " + listOfNotNull("shift".takeIf { shift }, "ctrl".takeIf { ctrl }, "alt".takeIf { alt }, key).joinToString("+")
    }

    override fun insert(text: String) {
        calls += "insert $text"
    }

    override fun setReader(on: Boolean) {
        calls += "reader $on"
    }

    override fun setContrast(contrast: WebContrast) {
        calls += "contrast ${contrast.json}"
    }

    override fun setActive(active: Boolean) {
        this.active = active
        calls += "active $active"
    }

    override fun release() {
        released = true
        calls += "release"
    }

    fun state(
        state: WebState,
        url: String = request.url,
        title: String = "",
        canBack: Boolean = false,
        field: WebField? = null,
        message: String? = null,
    ) = listener.onState(WebStatus(state, url, title, if (state == WebState.LOADING) 0 else 100, canBack, field = field, message = message))
}

/** An app for host tests: records its events and runs [handler] for each. */
class ScriptedApp(
    override val manifest: AppManifest,
    private val handler: ScriptedApp.(AppEvent, AppContext) -> Unit = { _, _ -> },
) : G2App {
    val events = mutableListOf<AppEvent>()

    override fun onEvent(event: AppEvent, ui: AppContext) {
        events += event
        handler(event, ui)
    }
}

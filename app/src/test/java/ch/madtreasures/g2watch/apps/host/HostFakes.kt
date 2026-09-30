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
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.apps.video.VideoEngine
import ch.madtreasures.g2watch.apps.video.VideoListener
import ch.madtreasures.g2watch.apps.video.VideoPlayer
import ch.madtreasures.g2watch.apps.video.VideoRequest
import ch.madtreasures.g2watch.desktop.AppScreen
import ch.madtreasures.g2watch.desktop.AppView
import ch.madtreasures.g2watch.desktop.GrayRaster
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Records what the host shows instead of drawing a desktop. */
class FakeScreen : AppScreen {
    val views = mutableListOf<AppView>()
    var closed = 0
    var desktopClicks = 0
    var desktopBacks = 0

    val last: AppView get() = views.last()

    override fun showApp(view: AppView) {
        views += view
    }

    override fun closeApp() {
        closed++
    }

    override fun click() {
        desktopClicks++
    }

    override fun back() {
        desktopBacks++
    }
}

/** In-memory platform: stores, permission answers, requests to answer, assets from src/main/assets. */
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

    // Unit tests run in the module directory.
    override fun asset(path: String): ByteArray? = assets[path] ?: File("src/main/assets", path).takeIf { it.isFile }?.readBytes()

    override fun decodeImage(bytes: ByteArray): GrayRaster? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val out = GrayRaster(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = image.getRGB(x, y)
            out[x, y] = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
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

    override val video = FakeVideoEngine()
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

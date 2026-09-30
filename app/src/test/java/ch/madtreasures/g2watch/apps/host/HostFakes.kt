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

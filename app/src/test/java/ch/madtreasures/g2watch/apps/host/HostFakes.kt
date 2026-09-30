package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.webraster.Dither
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** [AppPlatform] in memory; pictures are decoded with the JDK instead of Android. */
class FakeAppPlatform : AppPlatform {
    val assets = HashMap<String, ByteArray>()
    val stores = HashMap<String, Map<String, String>>()
    val permissions = HashMap<String, Set<Permission>>()
    val vibrations = mutableListOf<Vibration>()
    val logs = mutableListOf<String>()
    val requests = mutableListOf<HttpRequest>()
    var respond: (HttpRequest) -> HttpResult = { HttpResult(200, "ok".toByteArray()) }

    /** Makes the real assets of the app module available, e.g. the shopping list's ui.json. */
    fun withRealAssets(): FakeAppPlatform = apply {
        val root = File("src/main/assets")
        root.walkTopDown().filter { it.isFile }.forEach { assets[it.relativeTo(root).path.replace(File.separatorChar, '/')] = it.readBytes() }
    }

    override fun readAsset(path: String): ByteArray? = assets[path]

    override fun loadStorage(appId: String): Map<String, String> = stores[appId].orEmpty()

    override fun saveStorage(appId: String, values: Map<String, String>) {
        stores[appId] = values
    }

    override fun grantedPermissions(appId: String, version: String): Set<Permission>? = permissions["$appId@$version"]

    override fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>) {
        permissions["$appId@$version"] = granted
    }

    override fun vibrate(pattern: Vibration) {
        vibrations += pattern
    }

    override fun http(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        requests += request
        onResult(respond(request))
    }

    override fun decodeImage(bytes: ByteArray, maxWidth: Int, maxHeight: Int): GrayRaster? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scale = minOf(maxWidth.toFloat() / image.width, maxHeight.toFloat() / image.height)
        val w = max(1, (image.width * scale).roundToInt()).coerceAtMost(maxWidth)
        val h = max(1, (image.height * scale).roundToInt()).coerceAtMost(maxHeight)
        val out = GrayRaster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = image.getRGB((x * image.width / w).coerceAtMost(image.width - 1), (y * image.height / h).coerceAtMost(image.height - 1))
            out[x, y] = ((c shr 16 and 0xFF) * 2126 + (c shr 8 and 0xFF) * 7152 + (c and 0xFF) * 722) / 10000
        }
        Dither.forGlasses(out.pixels, w, h).copyInto(out.pixels)
        return out
    }

    override fun log(line: String) {
        logs += line
    }
}

/** Collects what the host shows. */
class FakeScreen : AppScreen {
    val frames = mutableListOf<AppFrame>()
    var closed = 0

    val last: AppFrame get() = frames.last()

    override fun showApps(frame: AppFrame) {
        frames += frame
    }

    override fun closeApps() {
        closed++
    }
}

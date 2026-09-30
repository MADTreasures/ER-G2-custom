package ch.madtreasures.g2watch.apps.host

import android.content.Context
import android.graphics.BitmapFactory
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.edit
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.desktop.GrayRaster
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** [HostPorts] on the watch. [logSink] receives lines for the watch log from any thread. */
class AndroidHostPorts(context: Context, private val logSink: (String) -> Unit) : HostPorts {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("g2watch.apps", Context.MODE_PRIVATE)
    private val stores = HashMap<String, AppStorage>()
    private val network = Executors.newFixedThreadPool(2) { r -> Thread(r, "G2Watch-fetch").apply { isDaemon = true } }

    override fun storage(appId: String): AppStorage =
        stores.getOrPut(appId) { FileAppStorage(File(File(context.filesDir, "apps"), appId)) }

    override fun grantedPermissions(appId: String, version: String): Set<Permission>? {
        val stored = prefs.getString(key(appId, version), null) ?: return null
        return stored.split(',').mapNotNull { Permission.of(it) }.toSet()
    }

    override fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>) {
        prefs.edit { putString(key(appId, version), granted.joinToString(",") { it.json }) }
    }

    private fun key(appId: String, version: String) = "permissions:$appId:$version"

    override fun vibrate(pattern: Vibration) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            when (pattern) {
                Vibration.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Vibration.DOUBLE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Vibration.LONG -> VibrationEffect.createOneShot(LONG_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            },
        )
    }

    override fun fetch(request: HttpRequest, done: (HttpResult) -> Unit) {
        network.execute { done(runRequest(request)) }
    }

    private fun runRequest(request: HttpRequest): HttpResult {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResult(0, "", "Adresse ungültig: ${e.message}")
        } catch (e: ClassCastException) {
            return HttpResult(0, "", "Keine HTTP-Adresse")
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = request.method
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BODY) return HttpResult(status, "", "Antwort größer als 1 MB")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            HttpResult(status, bytes.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            HttpResult(0, "", e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    override fun asset(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    /** Luminance of every pixel; transparent parts count as black (dark is see-through on the lens). */
    override fun decodeImage(bytes: ByteArray): GrayRaster? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val out = GrayRaster(w, h)
        for (i in argb.indices) {
            val c = argb[i]
            val a = c ushr 24 and 0xFF
            val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            out.pixels[i] = (luma * a / 255).toByte()
        }
        return out
    }

    override fun log(line: String) = logSink(line)

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BODY = 1024 * 1024
        const val LONG_VIBRATION_MS = 400L
    }
}

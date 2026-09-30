package ch.madtreasures.g2watch.apps.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.edit
import androidx.core.graphics.scale
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.webraster.Dither
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [AppPlatform] on the watch: assets from the APK, each app's store as `files/apps/<id>/store.json`,
 * permission answers in shared preferences, the watch's vibrator, HTTP on two background threads.
 */
class AndroidAppPlatform(context: Context, private val logSink: (String) -> Unit) : AppPlatform {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val http: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "G2Watch-http").apply { isDaemon = true } }

    override fun readAsset(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    private fun storeFile(appId: String) = File(context.filesDir, "apps/$appId/store.json")

    override fun loadStorage(appId: String): Map<String, String> {
        val file = storeFile(appId)
        if (!file.isFile) return emptyMap()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)
                ?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
                .orEmpty()
        } catch (e: IOException) {
            logSink("Speicher von $appId nicht lesbar: ${e.message}")
            emptyMap()
        } catch (e: SerializationException) {
            logSink("Speicher von $appId beschädigt, beginne leer")
            emptyMap()
        }
    }

    override fun saveStorage(appId: String, values: Map<String, String>) {
        val file = storeFile(appId)
        try {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "store.json.part")
            part.writeText(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
            if (!part.renameTo(file)) logSink("Speicher von $appId nicht geschrieben")
        } catch (e: IOException) {
            logSink("Speicher von $appId nicht geschrieben: ${e.message}")
        }
    }

    override fun grantedPermissions(appId: String, version: String): Set<Permission>? {
        val raw = prefs.getString(key(appId, version), null) ?: return null
        return raw.split(',').mapNotNull { Permission.of(it) }.toSet()
    }

    override fun saveGrantedPermissions(appId: String, version: String, granted: Set<Permission>) {
        prefs.edit { putString(key(appId, version), granted.joinToString(",") { it.json }) }
    }

    private fun key(appId: String, version: String) = "permissions:$appId@$version"

    override fun vibrate(pattern: Vibration) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            when (pattern) {
                Vibration.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Vibration.DOUBLE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Vibration.LONG -> VibrationEffect.createOneShot(LONG_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            },
        )
    }

    override fun http(request: HttpRequest, onResult: (HttpResult) -> Unit) {
        http.execute { onResult(AppHttp.run(request)) }
    }

    override fun decodeImage(bytes: ByteArray, maxWidth: Int, maxHeight: Int): GrayRaster? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode no larger than needed: a phone photo would otherwise take tens of MB.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height)
        val w = max(1, (decoded.width * scale).roundToInt()).coerceAtMost(maxWidth)
        val h = max(1, (decoded.height * scale).roundToInt()).coerceAtMost(maxHeight)
        val scaled = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
        val raster = toGray(scaled)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return raster
    }

    override fun log(line: String) = logSink(line)

    private companion object {
        const val LONG_MS = 400L

        /**
         * Relative luminance on black (transparent pixels stay see-through on the lens), dithered
         * to the 16 levels when it is a photo.
         */
        fun toGray(bitmap: Bitmap): GrayRaster {
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            val raster = GrayRaster(w, h)
            for (i in argb.indices) {
                val c = argb[i]
                val a = c ushr 24 and 0xFF
                val luma = (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) * a / 255f
                raster.pixels[i] = luma.roundToInt().coerceIn(0, 255).toByte()
            }
            Dither.forGlasses(raster.pixels, w, h).copyInto(raster.pixels)
            return raster
        }
    }
}

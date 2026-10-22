package ch.madtreasures.g2watch.apps

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The version of this app interface; app packages record the one they were built against (09 §5). */
object G2AppApi {
    /**
     * Raised with every change to [G2App], [AppContext], events, commands or pages. The watch app runs
     * packages built for this version or an older one: the interface only grows.
     *
     * 1: the first app packages (v0.6.0). 2: web pages in image blocks ([AppContext.web], [WebAction],
     * [AppEvent.Web], [AppEvent.ImageClick], [AppEvent.ImageScroll]; v0.8.0). 3: Bluetooth keyboards
     * ([Sensor.KEYS], [AppEvent.Key], [Keys], [WebAction.Key], [WebAction.Insert], [AppContext.keyboard];
     * v0.9.0). 4: an app on the phone ([AppContext.phone], [PhoneAction], [AppEvent.Phone], [PhoneState],
     * [Permission.PHONE], [Permission.WATCH_MIC]; v0.10.0).
     */
    const val VERSION = 4
}

/** A broken or unsuitable app package; [message] is German and meant for the wearer. */
class PackageFormatException(message: String) : Exception(message)

/**
 * The file `g2app.json` inside an app package (docs/app-entwicklung/09 §2): which [api] version the
 * package was built for, the class that implements [G2App] ([main]) and the app's [AppManifest] as
 * that class reports it, so the watch can list and check a package without running its code.
 *
 * [icon] is the package file of the app's launcher icon under `assets/` (`apps/<app id>/icon.png`), if
 * the package has one. It belongs to the package, not to the app interface, so it needs no new
 * [G2AppApi.VERSION] of its own: an older watch app's parser ignores it. A package built now carries
 * [api] = [G2AppApi.VERSION] all the same, and needs a watch app that knows that version (09 §5).
 */
data class PackageManifest(val api: Int, val main: String, val app: AppManifest, val icon: String? = null) {

    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("api", api)
        put("main", main)
        put("id", app.id)
        put("name", app.name)
        put("version", app.version)
        put("input", app.input.json)
        put("permissions", buildJsonArray { app.permissions.forEach { add(JsonPrimitive(it.json)) } })
        app.ui?.let { put("ui", it) }
        put("description", app.description)
        icon?.let { put("icon", it) }
    }.toString()

    companion object {
        const val FORMAT = "g2app-paket@1"
        const val FILE = "g2app.json"
        const val EXTENSION = "g2app"

        /** The launcher icon: a square PNG of 16 to 256 px (64 × 64 recommended), at most 64 KiB (09 §2). */
        const val ICON_FILE = "icon.png"
        const val ICON_MAX_BYTES = 64 * 1024
        const val ICON_MIN_PX = 16
        const val ICON_MAX_PX = 256

        private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+")

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Where the build step looks for the icon of app [id], relative to `src/main/assets`. */
        fun iconPath(id: String): String = "apps/$id/$ICON_FILE"

        /**
         * Why [bytes] cannot be the icon [path] (German, for the wearer), or null if it can. Only the
         * bytes are checked, nothing is decoded: size, the PNG signature and the picture size in its
         * IHDR chunk.
         */
        fun iconProblem(path: String, bytes: ByteArray): String? {
            if (bytes.size > ICON_MAX_BYTES) return "Symbol $path ist zu groß (höchstens ${ICON_MAX_BYTES / 1024} KB)"
            val header = bytes.size >= 24 && bytes.copyOf(8).contentEquals(PNG_SIGNATURE) &&
                String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR"
            if (!header) return "Symbol $path ist kein PNG"
            val w = bigEndian(bytes, 16)
            val h = bigEndian(bytes, 20)
            if (w != h) return "Symbol $path ist nicht quadratisch ($w × $h Pixel)"
            if (w !in ICON_MIN_PX..ICON_MAX_PX) return "Symbol $path muss $ICON_MIN_PX bis $ICON_MAX_PX Pixel groß sein, nicht $w × $h"
            return null
        }

        private fun bigEndian(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

        /** A relative `.png` path inside the package, without `..`, `.`, empty parts, drive letters or backslashes. */
        private fun safeIconPath(path: String): Boolean =
            path.endsWith(".png") && !path.startsWith("/") && '\\' !in path && ':' !in path &&
                path.split('/').none { it == ".." || it == "." || it.isEmpty() }

        /** Reads `g2app.json`; everything the watch relies on is checked here. */
        fun parse(text: String): PackageManifest {
            val o = try {
                AppJson.json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: throw PackageFormatException("$FILE ist kein JSON-Objekt")
            fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun need(key: String): String = str(key) ?: throw PackageFormatException("„$key“ fehlt in $FILE")

            val format = str("format")
            if (format != FORMAT) throw PackageFormatException("Kein App-Paket ($FORMAT), sondern „${format ?: "?"}“")
            val api = (o["api"] as? JsonPrimitive)?.intOrNull ?: throw PackageFormatException("„api“ fehlt in $FILE")
            if (api < 1) throw PackageFormatException("Ungültige Schnittstellen-Version $api")
            val main = need("main")
            if (!CLASS_NAME.matches(main)) throw PackageFormatException("„main“ ist kein Klassenname: $main")
            val input = str("input")?.let { InputMode.of(it) ?: throw PackageFormatException("Unbekannte Eingabeart „$it“") }
                ?: InputMode.POINTER
            val permissions = (o["permissions"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
                val name = (e as? JsonPrimitive)?.contentOrNull
                Permission.of(name) ?: throw PackageFormatException(
                    // A newer interface brings new permissions: say what the wearer can do about it.
                    if (api > G2AppApi.VERSION) "${str("name") ?: "Die App"} braucht eine neuere Uhr-App (Schnittstelle $api)"
                    else "Unbekannte Berechtigung „$name“",
                )
            }.toSet()
            val app = try {
                AppManifest(
                    id = need("id"),
                    name = need("name"),
                    version = need("version"),
                    input = input,
                    permissions = permissions,
                    ui = str("ui"),
                    description = str("description").orEmpty(),
                )
            } catch (e: IllegalArgumentException) {
                throw PackageFormatException(e.message ?: "Ungültiges Manifest")
            }
            val icon = str("icon")?.also {
                if (!safeIconPath(it)) throw PackageFormatException("„icon“ ist kein Pfad zu einer PNG-Datei im Paket: $it")
            }
            return PackageManifest(api, main, app, icon)
        }
    }
}

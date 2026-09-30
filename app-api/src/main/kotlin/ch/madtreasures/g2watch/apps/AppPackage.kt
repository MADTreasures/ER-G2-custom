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
     */
    const val VERSION = 1
}

/** A broken or unsuitable app package; [message] is German and meant for the wearer. */
class PackageFormatException(message: String) : Exception(message)

/**
 * The file `g2app.json` inside an app package (docs/app-entwicklung/09 §2): which [api] version the
 * package was built for, the class that implements [G2App] ([main]) and the app's [AppManifest] as
 * that class reports it, so the watch can list and check a package without running its code.
 */
data class PackageManifest(val api: Int, val main: String, val app: AppManifest) {

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
    }.toString()

    companion object {
        const val FORMAT = "g2app-paket@1"
        const val FILE = "g2app.json"
        const val EXTENSION = "g2app"

        private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+")

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
                Permission.of(name) ?: throw PackageFormatException("Unbekannte Berechtigung „$name“")
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
            return PackageManifest(api, main, app)
        }
    }
}

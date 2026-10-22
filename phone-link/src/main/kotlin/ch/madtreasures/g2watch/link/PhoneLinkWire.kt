package ch.madtreasures.g2watch.link

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How the watch app and "G2 Handy" talk about connections of watch apps to partner apps on the phone
 * (packages/fluency/PLATTFORM.md §6). Wear OS Data Layer messages on `/g2/link/<partner>/<type>`, where
 * `<partner>` is the partner's Android package name:
 *
 * | Type | Direction | Payload |
 * |---|---|---|
 * | `open` | watch → phone | [open]: which watch app connects, and its version |
 * | `opened` | phone → watch | – |
 * | `failed` | phone → watch | [reason]: why there is no connection |
 * | `msg` | both | one message of the app, UTF-8, passed on unchanged |
 * | `close` | both | [reason], may be empty |
 * | `ping` | watch → phone, as a request every [PING_MS] | answer [ALIVE] or [GONE] |
 * | `audio` | watch → phone, a channel | the watch's microphone: PCM s16le, [SAMPLE_RATE] Hz, mono |
 */
object PhoneLinkWire {
    const val PREFIX = "/g2/link/"

    /** Capabilities in res/values/wear.xml, how the two apps find each other. */
    const val WATCH_CAPABILITY = "g2_watch"
    const val PHONE_CAPABILITY = "g2_handy"

    const val SAMPLE_RATE = 16_000

    /** 20 ms of sound: 320 samples of 2 bytes. */
    const val BLOCK_BYTES = 640

    /** The most text one message may carry (the interface of the watch apps allows 64 KiB). */
    const val MAX_TEXT_BYTES = 64 * 1024

    /** How often the watch asks whether the connection still stands. */
    const val PING_MS = 10_000L

    /** Answers to a ping. */
    const val ALIVE = "alive"
    const val GONE = "gone"

    /** Why a connection ended that G2 Handy no longer has (it was restarted in between). */
    const val LOST = "Die Verbindung auf dem Handy ist weg"

    enum class Type(val path: String) {
        OPEN("open"),
        OPENED("opened"),
        FAILED("failed"),
        MSG("msg"),
        CLOSE("close"),
        PING("ping"),
        AUDIO("audio"),
    }

    /** The path of a [type] message for [partner]. */
    fun path(partner: String, type: Type): String {
        require(isPackageName(partner)) { "not a package name: $partner" }
        return "$PREFIX$partner/${type.path}"
    }

    /** The partner and the type of [path], or null when it is no link path. */
    fun parse(path: String): Pair<String, Type>? {
        if (!path.startsWith(PREFIX)) return null
        val rest = path.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val partner = rest.substring(0, slash)
        val type = Type.entries.firstOrNull { it.path == rest.substring(slash + 1) } ?: return null
        return if (isPackageName(partner)) partner to type else null
    }

    /** An Android package name, such as `ch.madtreasures.fluency`. */
    fun isPackageName(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)

    /** Who connects: the app on the watch ([app], its package id) and its [version]. */
    data class Opener(val app: String, val version: String)

    fun open(opener: Opener): ByteArray = buildJsonObject {
        put("app", opener.app)
        put("version", opener.version)
    }.toString().encodeToByteArray()

    fun readOpen(bytes: ByteArray): Opener? {
        val o = json(bytes) ?: return null
        val app = o.string("app") ?: return null
        return Opener(app, o.string("version").orEmpty())
    }

    /** Why a connection failed or ended; German, for the wearer. */
    fun reason(message: String?): ByteArray =
        if (message.isNullOrBlank()) ByteArray(0) else buildJsonObject { put("message", message) }.toString().encodeToByteArray()

    fun readReason(bytes: ByteArray): String? = json(bytes)?.string("message")?.takeIf { it.isNotBlank() }

    fun text(message: String): ByteArray = message.encodeToByteArray()

    fun readText(bytes: ByteArray): String = bytes.decodeToString()

    private fun json(bytes: ByteArray): JsonObject? {
        if (bytes.isEmpty()) return null
        return try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}

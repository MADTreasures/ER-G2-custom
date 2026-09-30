package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What an app can do (03 §2). Every call is a command of 02 §6.2; the host checks it against the
 * known pages and ids and the app's permissions. An invalid command is not carried out: the host
 * writes it to the log (Settings → Protokoll), and the test fake throws [IllegalArgumentException].
 * Call only on the app thread, i.e. from [G2App.onEvent] or a callback the host delivers there.
 */
interface AppContext {
    /** Makes pages known; replaces pages with the same id. Ids must be unique across the app. */
    fun definePages(pages: List<Page>)

    fun definePages(project: BaukastenProject)

    /** Shows a page and puts it on the history (Back returns). */
    fun show(pageId: String)

    /** Shows a page instead of the current one, without history. */
    fun replace(pageId: String)

    /** Changes fields of blocks on a page; only the named fields change (02 §6.2). */
    fun patch(pageId: String, changes: PatchBuilder.() -> Unit)

    /** Replaces all blocks of a page, e.g. for dynamic lists. */
    fun setBlocks(pageId: String, blocks: List<Block>)

    /** A short note at the bottom of the app area. */
    fun toast(text: String, ms: Int = 2000)

    fun vibrate(pattern: Vibration = Vibration.TICK)

    /** Own entries at the top of the app menu, at most 10; an empty list removes them. */
    fun menu(items: List<MenuItem>)

    /** The glasses' buzzer ([Permission.BUZZER]; available with M6). */
    fun buzz(notes: List<BuzzNote>)

    /** A [AppEvent.Timer] after [ms], again every [ms] with [repeat]. The same [tag] replaces a timer. */
    fun timer(tag: String, ms: Long, repeat: Boolean = false)

    fun cancelTimer(tag: String)

    /** Sensor events; needs the sensor's permission (sensors other than gestures arrive with M6). 0 = default rate. */
    fun subscribe(sensor: Sensor, rate: Int = 0)

    fun unsubscribe(sensor: Sensor)

    /** The glasses microphone ([Permission.MIC]; available with M6). */
    fun audio(on: Boolean)

    /**
     * An HTTPS request ([Permission.NETWORK]) on a background thread; [onResult] runs on the app
     * thread. Limits: 10 s, 1 MB response, `https://` only.
     */
    fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit)

    /** A small store of the app's own, at most 256 KiB in total. */
    val storage: AppStorage

    /** A line in Settings → Protokoll. */
    fun log(message: String)

    /** Ends the app. */
    fun close()
}

/** A command as JSON block changes, like the `changes` of a `patch` (02 §6.2). */
class PatchBuilder {
    private val changes = LinkedHashMap<String, MutableMap<String, JsonElement>>()

    private fun set(block: String, field: String, value: JsonElement) {
        changes.getOrPut(block) { LinkedHashMap() }[field] = value
    }

    /** Heading, text, button, toggle, value, progress: the text or label. */
    fun text(block: String, text: String) = set(block, "text", JsonPrimitive(text))

    fun align(block: String, align: Align) = set(block, "align", JsonPrimitive(align.json))

    /** Button: a page id, [Block.BACK] or null. */
    fun target(block: String, target: String?) = set(block, "target", target?.let { JsonPrimitive(it) } ?: JsonNull)

    fun on(block: String, on: Boolean) = set(block, "on", JsonPrimitive(on))

    /** Value block: the value on the right, e.g. "80 %". */
    fun value(block: String, value: String) = set(block, "value", JsonPrimitive(value))

    /** Progress block: 0–100. */
    fun progress(block: String, value: Int) = set(block, "value", JsonPrimitive(value))

    /** List block: all rows. */
    fun items(block: String, items: List<ListItem>) = set(
        block,
        "items",
        buildJsonArray {
            items.forEach { add(buildJsonObject { put("text", it.text); put("done", it.done) }) }
        },
    )

    /** List block: one row; null fields stay. */
    fun item(block: String, index: Int, text: String? = null, done: Boolean? = null) = set(
        block,
        "item",
        buildJsonObject {
            put("index", index)
            if (text != null) put("text", text)
            if (done != null) put("done", done)
        },
    )

    /** Image block: a `data:`/`asset:` source (or null for black) and optionally a new size. */
    fun image(block: String, src: String?, w: Int? = null, h: Int? = null) {
        set(block, "src", src?.let { JsonPrimitive(it) } ?: JsonNull)
        if (w != null) set(block, "w", JsonPrimitive(w))
        if (h != null) set(block, "h", JsonPrimitive(h))
    }

    fun build(): Map<String, JsonObject> = changes.mapValues { JsonObject(it.value) }
}

/** An own entry of the app menu; [text] at most 32 bytes of UTF-8. */
data class MenuItem(val id: String, val text: String)

/** One step of the buzzer: frequency, duty cycle in percent, duration. */
data class BuzzNote(val freqHz: Int, val dutyPercent: Int, val ms: Int)

enum class Vibration(val json: String) {
    TICK("tick"),
    DOUBLE("double"),
    LONG("long"),
    ;

    companion object {
        fun of(json: String): Vibration? = entries.firstOrNull { it.json == json }
    }
}

enum class Sensor(val json: String, val permission: Permission?) {
    IMU("imu", Permission.IMU),
    COMPASS("compass", Permission.COMPASS),
    LOCATION("location", Permission.LOCATION),

    /** Raw gestures while in pointer mode; needs no permission. */
    GESTURES("gestures", null),
    ;

    companion object {
        fun of(json: String): Sensor? = entries.firstOrNull { it.json == json }
    }
}

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HttpResult(
    /** HTTP status, or 0 when no response arrived (see [error]). */
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    /** Why there is no usable response: timeout, too large, not allowed, no network. */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && status in 200..299

    fun text(): String = body.toString(Charsets.UTF_8)
}

/** Per-app key-value store of JSON values. */
interface AppStorage {
    fun get(key: String): JsonElement?

    /** Stores [value]; null removes the key. Refused when the app would exceed 256 KiB. */
    fun set(key: String, value: JsonElement?)
}

package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.AppJson
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.WebField
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the content script of the browser's extension reports (assets/webbridge/content.js). */
sealed interface PageMessage {
    /** The script runs in a new document. */
    data class Hello(val url: String) : PageMessage

    /** The answer to [WebBridge.layoutRequest]: `collectLayout()` of page-layout.js. */
    data class Layout(val id: Long, val layout: JsonObject) : PageMessage

    /** A stage of the double capture ([WebBridge.stage]) is painted. */
    data class Staged(val id: Long) : PageMessage

    /** A text field got the cursor, or (null) none has it any more. */
    data class Field(val field: WebField?) : PageMessage

    /** Reading mode shows ([on]) or not; [readable]: the page is an article. */
    data class Reader(val on: Boolean, val readable: Boolean) : PageMessage

    /** Something on the page changed by itself: content arrived, a picture loaded, it scrolled. */
    data object Changed : PageMessage
}

/**
 * The messages between [GeckoWebPage] and the content script, as JSON text both ways. On the port
 * itself each one travels as `{ "json": "<text>" }` ([FIELD]): GeckoView carries port messages as
 * GeckoBundles, which cannot hold the nested arrays of the layout report.
 */
object WebBridge {
    const val FIELD = "json"

    /** Name of the native app the content script connects to. */
    const val NATIVE_APP = "g2web"

    fun decode(text: String): PageMessage? {
        val m = try {
            AppJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        val id = (m["id"] as? JsonPrimitive)?.longOrNull
        return when ((m["kind"] as? JsonPrimitive)?.contentOrNull) {
            "hello" -> PageMessage.Hello(string(m["url"]) ?: "")
            "layout" -> PageMessage.Layout(id ?: return null, m["layout"] as? JsonObject ?: return null)
            "staged" -> PageMessage.Staged(id ?: return null)
            "field" -> PageMessage.Field(field(m["field"]))
            "reader" -> PageMessage.Reader(bool(m["on"]), bool(m["readable"]))
            "changed" -> PageMessage.Changed
            else -> null
        }
    }

    /** What the page may need to know first: whether reading mode is wanted. */
    fun config(reader: Boolean): String = buildJsonObject {
        put("kind", "config")
        put("reader", reader)
    }.toString()

    /** Reading mode on or off for the page that shows. */
    fun reader(on: Boolean): String = buildJsonObject {
        put("kind", "reader")
        put("on", on)
    }.toString()

    fun layoutRequest(id: Long): String = buildJsonObject {
        put("kind", "layout")
        put("id", id)
    }.toString()

    /** [name]: "freeze", "hide-text" or "restore" (page-layout.js). */
    fun stage(id: Long, name: String): String = buildJsonObject {
        put("kind", "stage")
        put("id", id)
        put("stage", name)
    }.toString()

    /** Scrolls by [dy] CSS pixels. */
    fun scroll(dy: Double): String = buildJsonObject {
        put("kind", "scroll")
        put("dy", dy)
    }.toString()

    fun type(text: String, enter: Boolean): String = buildJsonObject {
        put("kind", "type")
        put("text", text)
        put("enter", enter)
    }.toString()

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun bool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

    private fun field(e: JsonElement?): WebField? {
        if (e == null || e is JsonNull) return null
        return try {
            AppJson.decodeField(e)
        } catch (x: CommandException) {
            null
        }
    }
}

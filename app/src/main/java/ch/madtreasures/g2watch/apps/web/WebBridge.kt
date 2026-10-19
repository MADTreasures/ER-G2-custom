package ch.madtreasures.g2watch.apps.web

import android.view.KeyEvent
import ch.madtreasures.g2watch.apps.AppJson
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.Keys
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

    /** A key pressed on the page ([ch.madtreasures.g2watch.apps.WebAction.Key]). */
    fun key(key: String, shift: Boolean, ctrl: Boolean, alt: Boolean): String = buildJsonObject {
        put("kind", "key")
        put("key", key)
        put("shift", shift)
        put("ctrl", ctrl)
        put("alt", alt)
    }.toString()

    /** Text typed at the cursor ([ch.madtreasures.g2watch.apps.WebAction.Insert]). */
    fun insert(text: String): String = buildJsonObject {
        put("kind", "insert")
        put("text", text)
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

/**
 * Web key names ([Keys]) as Android key codes, for GeckoView's own key input on pages where our script
 * does not run ([GeckoWebPage.key]). Characters go in as text instead.
 */
object WebKeys {
    private val CODES = mapOf(
        Keys.ENTER to KeyEvent.KEYCODE_ENTER,
        Keys.ESCAPE to KeyEvent.KEYCODE_ESCAPE,
        Keys.BACKSPACE to KeyEvent.KEYCODE_DEL,
        Keys.DELETE to KeyEvent.KEYCODE_FORWARD_DEL,
        Keys.TAB to KeyEvent.KEYCODE_TAB,
        Keys.SPACE to KeyEvent.KEYCODE_SPACE,
        Keys.ARROW_UP to KeyEvent.KEYCODE_DPAD_UP,
        Keys.ARROW_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        Keys.ARROW_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        Keys.ARROW_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        Keys.PAGE_UP to KeyEvent.KEYCODE_PAGE_UP,
        Keys.PAGE_DOWN to KeyEvent.KEYCODE_PAGE_DOWN,
        Keys.HOME to KeyEvent.KEYCODE_MOVE_HOME,
        Keys.END to KeyEvent.KEYCODE_MOVE_END,
    )

    /** The key code of [key], or [KeyEvent.KEYCODE_UNKNOWN] for a character or a key the page has no use for. */
    fun keyCode(key: String): Int = CODES[key] ?: KeyEvent.KEYCODE_UNKNOWN

    /** What [key] types as text: itself if it is one character, null for a name such as "F5" or "MediaPlayPause". */
    fun text(key: String): String? = key.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }

    /** The meta state of the modifiers, as a left key of each. */
    fun meta(shift: Boolean, ctrl: Boolean, alt: Boolean): Int =
        (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0) or
            (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0)
}

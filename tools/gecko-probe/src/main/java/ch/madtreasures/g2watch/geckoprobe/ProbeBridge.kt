package ch.madtreasures.g2watch.geckoprobe

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What arrives from a page over the extension's native port (see assets/probe-bridge/content.js). */
sealed interface BridgeMessage {
    /** The content script runs: the page started loading. */
    data class Hello(val url: String) : BridgeMessage

    /** `callHandler("evenAppMessage", {type, method, data})`, the way Even Hub apps call their host. */
    data class Call(val id: Long, val method: String, val data: JsonObject) : BridgeMessage

    /** The answer to a layout request of the render test. */
    data class Layout(val id: Long, val layout: JsonObject) : BridgeMessage

    /** A stage of the double capture (freeze, hide-text, restore) is painted. */
    data class Staged(val id: Long) : BridgeMessage
}

/**
 * The port messages as JSON text, both ways. On the port itself each one travels as
 * `{ "json": "<text>" }` ([FIELD]): GeckoView carries port messages as GeckoBundles, which cannot
 * hold nested or mixed arrays, and the layout of the render test is made of them.
 */
object BridgeProtocol {
    const val FIELD = "json"

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): BridgeMessage? {
        val m = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return when ((m["kind"] as? JsonPrimitive)?.content) {
            "hello" -> BridgeMessage.Hello((m["url"] as? JsonPrimitive)?.content.orEmpty())
            "call" -> {
                val id = (m["id"] as? JsonPrimitive)?.longOrNull ?: return null
                val payload = (m["payload"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return BridgeMessage.Call(id, "", JsonObject(emptyMap()))
                val even = try {
                    json.parseToJsonElement(payload) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
                val method = (even?.get("method") as? JsonPrimitive)?.content.orEmpty()
                BridgeMessage.Call(id, method, even?.get("data") as? JsonObject ?: JsonObject(emptyMap()))
            }
            "layout" -> {
                val id = (m["id"] as? JsonPrimitive)?.longOrNull ?: return null
                BridgeMessage.Layout(id, m["layout"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            "staged" -> BridgeMessage.Staged((m["id"] as? JsonPrimitive)?.longOrNull ?: return null)
            else -> null
        }
    }

    fun reply(id: Long, result: JsonElement): String = buildJsonObject {
        put("kind", "reply")
        put("id", id)
        put("result", result)
    }.toString()

    /** An event for `window._listenEvenAppMessage`, shaped like Even's host sends it. */
    fun push(method: String, data: JsonObject): String = buildJsonObject {
        put("kind", "push")
        put(
            "msg",
            buildJsonObject {
                put("type", "listen_even_app_data")
                put("method", method)
                put("data", data)
            },
        )
    }.toString()

    fun layoutRequest(id: Long): String = buildJsonObject {
        put("kind", "layout")
        put("id", id)
    }.toString()

    /** One step of the double capture: "freeze", "hide-text" or "restore". */
    fun stage(id: Long, stage: String): String = buildJsonObject {
        put("kind", "stage")
        put("id", id)
        put("stage", stage)
    }.toString()
}

/** What a test app did, for the measurements. */
sealed interface BridgeEvent {
    val app: String

    data class Hello(override val app: String, val url: String) : BridgeEvent
    data class Called(override val app: String, val method: String, val data: JsonObject) : BridgeEvent
    data class LayoutReady(override val app: String, val id: Long, val layout: JsonObject) : BridgeEvent
    data class Staged(override val app: String, val id: Long) : BridgeEvent
}

/**
 * The watch's side of the bridge for one test app: answers the SDK methods the way the EvenHub
 * runtime will (05 §4.1, reduced to what the test apps call) and reports every message.
 */
class ProbeBridge(private val app: String, private val events: (BridgeEvent) -> Unit) {

    /** Handles one port message; returns the reply to send back, if any. */
    fun receive(text: String): String? = when (val m = BridgeProtocol.decode(text)) {
        null -> null
        is BridgeMessage.Hello -> {
            events(BridgeEvent.Hello(app, m.url))
            null
        }
        is BridgeMessage.Call -> {
            events(BridgeEvent.Called(app, m.method, m.data))
            BridgeProtocol.reply(m.id, resultFor(m.method))
        }
        is BridgeMessage.Layout -> {
            events(BridgeEvent.LayoutReady(app, m.id, m.layout))
            null
        }
        is BridgeMessage.Staged -> {
            events(BridgeEvent.Staged(app, m.id))
            null
        }
    }

    private fun resultFor(method: String): JsonElement = when (method) {
        "createStartUpPageContainer", "updateImageRawData" -> JsonPrimitive(0)
        "rebuildPageContainer", "textContainerUpgrade", "shutDownPageContainer", "probeReport", "timerReport" -> JsonPrimitive(true)
        "getUserInfo" -> buildJsonObject { put("name", "G2 Watch") }
        else -> JsonNull
    }
}

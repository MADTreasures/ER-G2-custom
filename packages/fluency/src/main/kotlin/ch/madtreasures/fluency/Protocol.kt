package ch.madtreasures.fluency

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** An utterance from Fluency ([Protocol.Incoming.Utterance]), the type the rest of the app works with. */
typealias Utterance = Protocol.Incoming.Utterance

/**
 * The messages between this app and Fluency on the phone, protocol `fluency-g2@1` (PLATTFORM.md §6): one
 * JSON object per message with its type in `t`. Both sides ignore types and fields they do not know, so
 * each can grow without breaking the other; a change of meaning gets a new protocol name.
 */
object Protocol {
    const val NAME = "fluency-g2@1"

    /** What to translate: where the sound comes from, from and into which language ([Languages] codes; `from` may be "auto"). */
    data class Request(
        val source: Source,
        val from: String,
        val to: String,
        /** Fluency reads each finished translation aloud on the phone. */
        val speak: Boolean = false,
        /** Fluency also sends results while a sentence is still being spoken, not only finished ones. */
        val partials: Boolean = true,
    )

    /** The first message once connected; Fluency answers with [Incoming.Welcome]. */
    fun hello(appVersion: String): String = message("hello") {
        put("proto", NAME)
        put("app", appVersion)
    }

    /**
     * Start listening and translating. [n] numbers the app's requests (start, config, stop) in order;
     * Fluency's [Incoming.State] names the last one it carried out, so an answer to an older request is
     * recognisable as such.
     */
    fun start(request: Request, n: Int): String = message("start") {
        put("n", n)
        request(request)
    }

    /** Changed settings while listening; Fluency restarts what it must. */
    fun config(request: Request, n: Int): String = message("config") {
        put("n", n)
        request(request)
    }

    /** Stop listening; what is still being said gets finished first. */
    fun stop(n: Int): String = message("stop") { put("n", n) }

    private fun JsonObjectBuilder.request(r: Request) {
        put("source", r.source.json)
        put("from", r.from)
        put("to", r.to)
        put("speak", r.speak)
        put("partials", r.partials)
    }

    private fun message(type: String, fields: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("t", type)
            fields()
        }.toString()

    /** A message from Fluency. */
    sealed interface Incoming {
        /** Answer to [hello]: Fluency's protocol and version and, if it says, the language codes it can translate right now. */
        data class Welcome(val proto: String, val app: String, val languages: List<String>? = null) : Incoming

        /**
         * Whether Fluency listens (from `start` on, also while it still loads its models), whether it hears
         * speech right now, and a short German note on what it does or why it stopped. [re]: the number of
         * the request this answers, or null when Fluency changed by itself (an error, stopped on the phone).
         */
        data class State(val running: Boolean, val speech: Boolean = false, val note: String? = null, val re: Int? = null) : Incoming

        /**
         * An utterance as it grows: the recognised text [source] and its translation [text], [final] once
         * finished; [gone] when Fluency dropped it (noise, nothing said). [from] is the spoken language, if known.
         */
        data class Utterance(
            val id: Long,
            val source: String,
            val text: String,
            val final: Boolean,
            val from: String? = null,
            val gone: Boolean = false,
        ) : Incoming

        /** Something failed (no model for the pair, microphone busy …); [message] is German, for the wearer. */
        data class Error(val code: String, val message: String) : Incoming
    }

    /** [text] as a message from Fluency, or null if it is none this app knows (broken JSON, unknown type, missing fields). */
    fun parse(text: String): Incoming? {
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun bool(key: String): Boolean? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return when (str("t")) {
            "welcome" -> Incoming.Welcome(
                proto = str("proto") ?: return null,
                app = str("app").orEmpty(),
                languages = (o["languages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            )
            "state" -> Incoming.State(
                running = bool("running") ?: return null,
                speech = bool("speech") ?: false,
                note = str("note"),
                re = (o["re"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            )
            "utt" -> Incoming.Utterance(
                id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null,
                source = str("src").orEmpty(),
                text = str("text").orEmpty(),
                final = bool("final") ?: false,
                from = str("from"),
                gone = bool("gone") ?: false,
            )
            "error" -> Incoming.Error(code = str("code") ?: "error", message = str("message") ?: "Fehler in Fluency")
            else -> null
        }
    }
}

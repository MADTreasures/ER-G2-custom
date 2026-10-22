package ch.madtreasures.fluency

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.Page
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A connection to Fluency for tests: it records what the app sends; the test answers through [FluencyApp.onLink]. */
class FakePhoneLink(override val available: Boolean = true) : PhoneLink {
    var opened = 0
    val sent = mutableListOf<String>()
    val microphone = mutableListOf<Boolean>()
    var closed = false

    override fun open() {
        opened++
    }

    override fun send(message: String) {
        sent += message
    }

    override fun microphone(on: Boolean) {
        microphone += on
    }

    override fun close() {
        closed = true
    }

    val messages: List<JsonObject> get() = sent.map { Json.parseToJsonElement(it).jsonObject }

    /** Types of the messages sent, in order. */
    val types: List<String> get() = messages.map { it.type }

    /** The last message of [type] that was sent. */
    fun last(type: String): JsonObject = messages.last { it.type == type }
}

val JsonObject.type: String get() = getValue("t").jsonPrimitive.content

/**
 * [FluencyApp] against [FakeAppContext], with a virtual clock and the host's one-shot timers: a timer the
 * app sets is due its time later, and [advance] fires what falls due, in order.
 */
class Harness(val link: PhoneLink = NoPhoneLink) {
    var now = 0L
    val app = FluencyApp(clock = { now }, linkFor = { link })
    val ui = FakeAppContext.forApp(app)
    private val due = HashMap<String, Long>()
    private var seen = mapOf<String, Pair<Long, Boolean>>()

    fun send(event: AppEvent) {
        app.onEvent(event, ui)
        noteTimers()
    }

    fun link(event: LinkEvent) {
        app.onLink(event, ui)
        noteTimers()
    }

    /** A message from Fluency. */
    fun fluency(json: String) = link(LinkEvent.Message(json))

    /** A timer set (or set again) during the last event is due its time from now; a cancelled one is gone. */
    private fun noteTimers() {
        for ((tag, t) in ui.timers) if (seen[tag] !== t) due[tag] = now + t.first
        due.keys.retainAll(ui.timers.keys)
        seen = ui.timers.toMap()
    }

    fun started() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    fun click(page: String, block: String) = send(AppEvent.Click(page, block))

    /** Moves the clock on by [ms], firing every timer that falls due on the way. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = due.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
            now = next.value
            due.remove(next.key)
            ui.timers.remove(next.key)
            seen = ui.timers.toMap()
            send(AppEvent.Timer(next.key))
        }
        now = end
    }

    /** When [tag] fires next, or null. */
    fun dueOf(tag: String): Long? = due[tag]

    val heading: String get() = ui.page(FluencyApp.LIVE).heading()
    val original: String get() = ui.page(FluencyApp.LIVE).originalLine()
    val name: String get() = ui.page(FluencyApp.LIVE).name

    companion object {
        fun welcome(proto: String = Protocol.NAME, app: String = "1.9.0") = buildJsonObject {
            put("t", "welcome")
            put("proto", proto)
            put("app", app)
        }.toString()

        fun state(running: Boolean, re: Int? = null, note: String? = null) = buildJsonObject {
            put("t", "state")
            put("running", running)
            if (re != null) put("re", re)
            if (note != null) put("note", note)
        }.toString()

        fun utt(id: Long, src: String, text: String, final: Boolean = false, from: String? = null) = buildJsonObject {
            put("t", "utt")
            put("id", id)
            put("src", src)
            put("text", text)
            put("final", final)
            if (from != null) put("from", from)
        }.toString()

        fun gone(id: Long) = buildJsonObject {
            put("t", "utt")
            put("id", id)
            put("gone", true)
        }.toString()

        fun error(message: String, code: String = "no_model") = buildJsonObject {
            put("t", "error")
            put("code", code)
            put("message", message)
        }.toString()
    }
}

/** The caption of the live page. */
fun Page.heading(): String = (block(FluencyApp.NOW) as? Block.Heading)?.text ?: throw AssertionError("no caption on $id")

/** The original line of the live page; fails when it has none. */
fun Page.originalLine(): String = (block(FluencyApp.ORIGINAL) as? Block.Text)?.text ?: throw AssertionError("no original line on $id")

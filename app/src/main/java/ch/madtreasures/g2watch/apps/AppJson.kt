package ch.madtreasures.g2watch.apps

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The JSON forms of 02 §4 and §6, shared by watch apps, Baukasten assets and (with M5) the
 * `g2-remote@1` protocol. Written by hand on kotlinx.serialization's JSON tree rather than with
 * generated serializers: several classes share `kind: "sensor"`, and incoming pages are completed
 * as tolerantly as the Baukasten's own import ([BaukastenProject.normalize]).
 */
object AppJson {

    // --- Pages and blocks ----------------------------------------------------------------------

    fun encodePage(page: Page): JsonObject = buildJsonObject {
        put("id", page.id)
        put("name", page.name)
        put("statusBar", page.statusBar)
        put("notes", page.notes)
        putJsonArray("blocks") { page.blocks.forEach { add(encodeBlock(it)) } }
    }

    fun encodeBlock(block: Block): JsonObject = buildJsonObject {
        put("id", block.id)
        put("type", block.type)
        when (block) {
            is Block.Heading -> {
                put("text", block.text)
                put("align", block.align.json)
                put("size", block.size.json)
            }
            is Block.Text -> {
                put("text", block.text)
                put("align", block.align.json)
            }
            is Block.Button -> {
                put("text", block.text)
                put("target", block.target?.let { JsonPrimitive(it) } ?: JsonNull)
                put("action", block.action)
            }
            is Block.List -> {
                put("style", block.style.json)
                putJsonArray("items") {
                    block.items.forEach { add(buildJsonObject { put("text", it.text); put("done", it.done) }) }
                }
            }
            is Block.Toggle -> {
                put("text", block.text)
                put("on", block.on)
            }
            is Block.Value -> {
                put("text", block.text)
                put("value", block.value)
            }
            is Block.Progress -> {
                put("text", block.text)
                put("value", block.value)
            }
            is Block.Divider -> Unit
            is Block.Image -> {
                put("src", block.src?.let { JsonPrimitive(it) } ?: JsonNull)
                put("w", block.w)
                put("h", block.h)
                put("align", block.align.json)
                put("bleed", block.bleed)
            }
        }
    }

    /**
     * Pages of a `definePages` command or a whole Baukasten project, completed like the
     * Baukasten does it; [warn] hears about every id that had to change.
     */
    fun decodePages(element: JsonElement, warn: (String) -> Unit = {}): List<Page> {
        val project = when (element) {
            is JsonArray -> buildJsonObject { put("pages", element) }
            else -> element
        }
        return BaukastenProject.normalize(project, warn).pages
    }

    fun decodeBlocks(element: JsonElement, warn: (String) -> Unit = {}): List<Block> {
        val array = element as? JsonArray ?: throw CommandException(CommandError.BAD_VALUE, "„blocks“ muss eine Liste sein")
        val ids = IdAllocator(warn)
        return array.mapNotNull { BaukastenProject.normalizeBlock(it, ids) }
    }

    // --- Events --------------------------------------------------------------------------------

    /** The JSON form of [event]; audio never goes as JSON and gives null. */
    fun encodeEvent(event: AppEvent): JsonObject? = when (event) {
        AppEvent.Start -> kind("start")
        AppEvent.Visible -> kind("visible")
        AppEvent.Hidden -> kind("hidden")
        AppEvent.Stop -> kind("stop")
        is AppEvent.Click -> kind("click") { put("page", event.page); put("block", event.block) }
        is AppEvent.Toggle -> kind("toggle") { put("page", event.page); put("block", event.block); put("on", event.on) }
        is AppEvent.Check -> kind("check") {
            put("page", event.page)
            put("block", event.block)
            put("index", event.index)
            put("done", event.done)
        }
        is AppEvent.Navigate -> kind("navigate") { put("from", event.from); put("to", event.to); put("block", event.block) }
        is AppEvent.Back -> kind("back") { put("page", event.page) }
        is AppEvent.Menu -> kind("menu") { put("item", event.item) }
        is AppEvent.Gesture -> kind("gesture") { put("gesture", event.gesture.json); put("source", event.source.json) }
        is AppEvent.Timer -> kind("timer") { put("tag", event.tag) }
        is AppEvent.Imu -> kind("sensor") {
            put("sensor", "imu")
            put("x", event.x)
            put("y", event.y)
            put("z", event.z)
            put("t", event.t)
        }
        is AppEvent.Compass -> kind("sensor") { put("sensor", "compass"); put("heading", event.heading); put("t", event.t) }
        is AppEvent.Location -> kind("sensor") {
            put("sensor", "location")
            put("lat", event.lat)
            put("lon", event.lon)
            put("acc", event.acc)
            put("t", event.t)
        }
        is AppEvent.Audio -> null
        is AppEvent.Error -> kind("error") { put("command", event.command); put("code", event.code); put("message", event.message) }
    }

    /** The event in [json], or null for kinds this version does not know (apps ignore those). */
    fun decodeEvent(json: JsonObject): AppEvent? {
        fun s(key: String) = json[key].stringOrNull() ?: throw SerializationException("„$key“ fehlt")
        fun b(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull ?: throw SerializationException("„$key“ fehlt")
        fun i(key: String) = json[key].intOrNull() ?: throw SerializationException("„$key“ fehlt")
        fun d(key: String) = (json[key] as? JsonPrimitive)?.doubleOrNull ?: throw SerializationException("„$key“ fehlt")
        fun l(key: String) = (json[key] as? JsonPrimitive)?.longOrNull ?: throw SerializationException("„$key“ fehlt")
        return when (json["kind"].stringOrNull()) {
            "start" -> AppEvent.Start
            "visible" -> AppEvent.Visible
            "hidden" -> AppEvent.Hidden
            "stop" -> AppEvent.Stop
            "click" -> AppEvent.Click(s("page"), s("block"))
            "toggle" -> AppEvent.Toggle(s("page"), s("block"), b("on"))
            "check" -> AppEvent.Check(s("page"), s("block"), i("index"), b("done"))
            "navigate" -> AppEvent.Navigate(s("from"), s("to"), s("block"))
            "back" -> AppEvent.Back(s("page"))
            "menu" -> AppEvent.Menu(s("item"))
            "gesture" -> AppEvent.Gesture(
                GestureKind.of(s("gesture")) ?: return null,
                InputSource.of(s("source")) ?: InputSource.UNKNOWN,
            )
            "timer" -> AppEvent.Timer(s("tag"))
            "sensor" -> when (s("sensor")) {
                "imu" -> AppEvent.Imu(d("x").toFloat(), d("y").toFloat(), d("z").toFloat(), l("t"))
                "compass" -> AppEvent.Compass(d("heading").toFloat(), l("t"))
                "location" -> AppEvent.Location(d("lat"), d("lon"), d("acc").toFloat(), l("t"))
                else -> null
            }
            "error" -> AppEvent.Error(s("command"), s("code"), s("message"))
            else -> null
        }
    }

    private inline fun kind(kind: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("kind", kind)
            fields()
        }

    // --- Commands ------------------------------------------------------------------------------

    fun encodeCommand(command: AppCommand): JsonObject = buildJsonObject {
        put("c", command.name)
        when (command) {
            is AppCommand.DefinePages -> putJsonArray("pages") { command.pages.forEach { add(encodePage(it)) } }
            is AppCommand.Show -> put("page", command.page)
            is AppCommand.Replace -> put("page", command.page)
            is AppCommand.Patch -> {
                put("page", command.page)
                put("changes", JsonObject(command.changes))
            }
            is AppCommand.SetBlocks -> {
                put("page", command.page)
                putJsonArray("blocks") { command.blocks.forEach { add(encodeBlock(it)) } }
            }
            is AppCommand.Toast -> {
                put("text", command.text)
                put("ms", command.ms)
            }
            is AppCommand.Vibrate -> put("pattern", command.pattern.json)
            is AppCommand.Menu -> putJsonArray("items") {
                command.items.forEach { add(buildJsonObject { put("id", it.id); put("text", it.text) }) }
            }
            is AppCommand.Buzz -> putJsonArray("notes") {
                command.notes.forEach { add(buildJsonArray { add(JsonPrimitive(it.freqHz)); add(JsonPrimitive(it.dutyPercent)); add(JsonPrimitive(it.ms)) }) }
            }
            is AppCommand.Timer -> {
                put("tag", command.tag)
                put("ms", command.ms)
                put("repeat", command.repeat)
            }
            is AppCommand.CancelTimer -> put("tag", command.tag)
            is AppCommand.Subscribe -> {
                put("sensor", command.sensor.json)
                put("rate", command.rate)
            }
            is AppCommand.Unsubscribe -> put("sensor", command.sensor.json)
            is AppCommand.Audio -> put("on", command.on)
            AppCommand.Close -> Unit
        }
    }

    /** The command in [json]; throws [CommandException] (`bad_value`) for anything malformed or unknown. */
    fun decodeCommand(json: JsonObject, warn: (String) -> Unit = {}): AppCommand {
        fun bad(message: String): Nothing = throw CommandException(CommandError.BAD_VALUE, message)
        fun s(key: String) = json[key].stringOrNull() ?: bad("„$key“ fehlt")
        return when (val c = json["c"].stringOrNull()) {
            "definePages" -> AppCommand.DefinePages(
                try {
                    decodePages(json["pages"] ?: bad("„pages“ fehlt"), warn)
                } catch (e: IllegalArgumentException) {
                    bad(e.message ?: "ungültige Seiten")
                },
            )
            "show" -> AppCommand.Show(s("page"))
            "replace" -> AppCommand.Replace(s("page"))
            "patch" -> AppCommand.Patch(
                s("page"),
                (json["changes"] as? JsonObject ?: bad("„changes“ fehlt")).mapValues { (id, v) ->
                    v as? JsonObject ?: bad("Änderungen für „$id“ müssen ein Objekt sein")
                },
            )
            "setBlocks" -> AppCommand.SetBlocks(s("page"), decodeBlocks(json["blocks"] ?: bad("„blocks“ fehlt"), warn))
            "toast" -> AppCommand.Toast(s("text"), json["ms"].intOrNull() ?: 2000)
            "vibrate" -> AppCommand.Vibrate(Vibration.of(json["pattern"].stringOrNull() ?: "tick") ?: bad("unbekanntes Muster"))
            "menu" -> AppCommand.Menu(
                (json["items"] as? JsonArray ?: bad("„items“ fehlt")).map { e ->
                    val o = e as? JsonObject ?: bad("Menüeintrag muss ein Objekt sein")
                    MenuItem(o["id"].stringOrNull() ?: bad("Menüeintrag ohne „id“"), o["text"].stringOrNull().orEmpty())
                },
            )
            "buzz" -> AppCommand.Buzz(
                (json["notes"] as? JsonArray ?: bad("„notes“ fehlt")).map { e ->
                    val step = (e as? JsonArray)?.map { it.intOrNull() ?: bad("Ton muss aus Zahlen bestehen") }
                    if (step == null || step.size != 3) bad("Ton: [Frequenz, Tastgrad, ms]")
                    BuzzNote(step[0], step[1], step[2])
                },
            )
            "timer" -> AppCommand.Timer(
                s("tag"),
                (json["ms"] as? JsonPrimitive)?.longOrNull ?: bad("„ms“ fehlt"),
                (json["repeat"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            "cancelTimer" -> AppCommand.CancelTimer(s("tag"))
            "subscribe" -> AppCommand.Subscribe(Sensor.of(s("sensor")) ?: bad("unbekannter Sensor"), json["rate"].intOrNull() ?: 0)
            "unsubscribe" -> AppCommand.Unsubscribe(Sensor.of(s("sensor")) ?: bad("unbekannter Sensor"))
            "audio" -> AppCommand.Audio((json["on"] as? JsonPrimitive)?.booleanOrNull ?: bad("„on“ fehlt"))
            "close" -> AppCommand.Close
            else -> bad("unbekannter Befehl „$c“")
        }
    }
}

/** kotlinx.serialization view of [AppJson]'s event form, for JSON encoders and decoders. */
object AppEventSerializer : KSerializer<AppEvent> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AppEvent) {
        val json = AppJson.encodeEvent(value) ?: throw SerializationException("Audio wird nicht als JSON gesendet")
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): AppEvent {
        val json = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: throw SerializationException("Ereignis ist kein Objekt")
        return AppJson.decodeEvent(json) ?: throw SerializationException("unbekanntes Ereignis")
    }
}

/** kotlinx.serialization view of [AppJson]'s command form. */
object AppCommandSerializer : KSerializer<AppCommand> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AppCommand) {
        (encoder as JsonEncoder).encodeJsonElement(AppJson.encodeCommand(value))
    }

    override fun deserialize(decoder: Decoder): AppCommand {
        val json = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: throw SerializationException("Befehl ist kein Objekt")
        return AppJson.decodeCommand(json)
    }
}

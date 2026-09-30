package ch.madtreasures.g2watch.apps

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON forms of events, commands, pages and blocks (02 §4 and §6, 04 §5.3). Watch apps never see
 * JSON; the codec is for page assets now and for the protocol to remote apps (M5). Several Kotlin classes
 * share `kind: "sensor"`, so events are told apart by `kind` and, for sensors, by `sensor`.
 *
 * Decoding is strict: a malformed event or command throws [CommandException] with code
 * [CommandException.BAD_VALUE] (never silently dropped); only an unknown event kind yields null, because
 * apps ignore kinds they do not know. Unknown fields are ignored.
 */
object AppJson {
    val json = Json { ignoreUnknownKeys = true }

    // --- Events ---------------------------------------------------------------------------------

    fun encodeEvent(event: AppEvent): JsonObject = buildJsonObject {
        when (event) {
            AppEvent.Start -> put("kind", "start")
            AppEvent.Visible -> put("kind", "visible")
            AppEvent.Hidden -> put("kind", "hidden")
            AppEvent.Stop -> put("kind", "stop")
            is AppEvent.Click -> {
                put("kind", "click"); put("page", event.page); put("block", event.block)
            }
            is AppEvent.Toggle -> {
                put("kind", "toggle"); put("page", event.page); put("block", event.block); put("on", event.on)
            }
            is AppEvent.Check -> {
                put("kind", "check"); put("page", event.page); put("block", event.block)
                put("index", event.index); put("done", event.done)
            }
            is AppEvent.Navigate -> {
                put("kind", "navigate"); put("from", event.from); put("to", event.to); put("block", event.block)
            }
            is AppEvent.Back -> {
                put("kind", "back"); put("page", event.page)
            }
            is AppEvent.Menu -> {
                put("kind", "menu"); put("item", event.item)
            }
            is AppEvent.Gesture -> {
                put("kind", "gesture"); put("gesture", event.gesture.json); put("source", event.source.json)
            }
            is AppEvent.Timer -> {
                put("kind", "timer"); put("tag", event.tag)
            }
            is AppEvent.Imu -> {
                put("kind", "sensor"); put("sensor", "imu")
                put("x", event.x); put("y", event.y); put("z", event.z); put("t", event.t)
            }
            is AppEvent.Compass -> {
                put("kind", "sensor"); put("sensor", "compass"); put("heading", event.heading); put("t", event.t)
            }
            is AppEvent.Location -> {
                put("kind", "sensor"); put("sensor", "location")
                put("lat", event.lat); put("lon", event.lon); put("acc", event.acc); put("t", event.t)
            }
            is AppEvent.TextInput -> {
                put("kind", "text"); put("tag", event.tag); put("text", event.text)
            }
            is AppEvent.Video -> {
                put("kind", "video"); put("block", event.block); put("state", event.state.json)
                put("position", event.positionMs); put("duration", event.durationMs); put("message", event.message)
            }
            is AppEvent.Audio -> throw SerializationException("audio events travel as binary frames, never as JSON")
            is AppEvent.Error -> {
                put("kind", "error"); put("command", event.command); put("code", event.code); put("message", event.message)
            }
        }
    }

    /** The event in [element], or null for a kind this version does not know. */
    fun decodeEvent(element: JsonElement): AppEvent? {
        val o = obj(element, "Ereignis")
        return when (val kind = string(o, "kind")) {
            "start" -> AppEvent.Start
            "visible" -> AppEvent.Visible
            "hidden" -> AppEvent.Hidden
            "stop" -> AppEvent.Stop
            "click" -> AppEvent.Click(string(o, "page"), string(o, "block"))
            "toggle" -> AppEvent.Toggle(string(o, "page"), string(o, "block"), bool(o, "on"))
            "check" -> AppEvent.Check(string(o, "page"), string(o, "block"), int(o, "index"), bool(o, "done"))
            "navigate" -> AppEvent.Navigate(string(o, "from"), string(o, "to"), string(o, "block"))
            "back" -> AppEvent.Back(string(o, "page"))
            "menu" -> AppEvent.Menu(string(o, "item"))
            "gesture" -> AppEvent.Gesture(
                GestureKind.of(string(o, "gesture")) ?: bad("unbekannte Geste „${o["gesture"]}“"),
                InputSource.of(optString(o, "source") ?: "unknown") ?: bad("unbekannte Quelle „${o["source"]}“"),
            )
            "timer" -> AppEvent.Timer(string(o, "tag"))
            "sensor" -> when (val sensor = string(o, "sensor")) {
                "imu" -> AppEvent.Imu(float(o, "x"), float(o, "y"), float(o, "z"), long(o, "t"))
                "compass" -> AppEvent.Compass(float(o, "heading"), long(o, "t"))
                "location" -> AppEvent.Location(double(o, "lat"), double(o, "lon"), float(o, "acc"), long(o, "t"))
                else -> bad("unbekannter Sensor „$sensor“")
            }
            "text" -> AppEvent.TextInput(string(o, "tag"), optString(o, "text"))
            "video" -> AppEvent.Video(
                string(o, "block"),
                VideoState.of(string(o, "state")) ?: bad("unbekannter Videozustand „${o["state"]}“"),
                optLong(o, "position") ?: 0,
                optLong(o, "duration") ?: 0,
                optString(o, "message"),
            )
            "audio" -> bad("Audio kommt nie als JSON, sondern als Binärrahmen")
            "error" -> AppEvent.Error(string(o, "command"), string(o, "code"), string(o, "message"))
            else -> null
        }
    }

    // --- Commands -------------------------------------------------------------------------------

    fun encodeCommand(command: AppCommand): JsonObject = buildJsonObject {
        put("c", command.name)
        when (command) {
            is AppCommand.DefinePages -> put("pages", JsonArray(command.pages.map(::encodePage)))
            is AppCommand.Show -> put("page", command.page)
            is AppCommand.Replace -> put("page", command.page)
            is AppCommand.Patch -> {
                put("page", command.page); put("changes", command.changes)
            }
            is AppCommand.SetBlocks -> {
                put("page", command.page); put("blocks", JsonArray(command.blocks.map(::encodeBlock)))
            }
            is AppCommand.Toast -> {
                put("text", command.text); put("ms", command.ms)
            }
            is AppCommand.Vibrate -> put("pattern", command.pattern.json)
            is AppCommand.Menu -> put(
                "items",
                buildJsonArray { command.items.forEach { add(buildJsonObject { put("id", it.id); put("text", it.text) }) } },
            )
            is AppCommand.Buzz -> put(
                "notes",
                buildJsonArray {
                    command.notes.forEach {
                        add(buildJsonArray { add(JsonPrimitive(it.freqHz)); add(JsonPrimitive(it.dutyPercent)); add(JsonPrimitive(it.ms)) })
                    }
                },
            )
            is AppCommand.Subscribe -> {
                put("sensor", command.sensor.json); put("rate", command.rate)
            }
            is AppCommand.Unsubscribe -> put("sensor", command.sensor.json)
            is AppCommand.Audio -> put("on", command.on)
            is AppCommand.AskText -> {
                put("tag", command.tag); put("prompt", command.prompt)
                put("suggestions", buildJsonArray { command.suggestions.forEach { add(JsonPrimitive(it)) } })
            }
            is AppCommand.Video -> {
                put("block", command.block)
                put("action", command.action.json)
                when (val a = command.action) {
                    is VideoAction.Play -> {
                        put("src", a.src); put("profile", a.profile.json); put("sound", a.sound); put("start", a.startMs)
                    }
                    is VideoAction.Seek -> put("position", a.positionMs)
                    is VideoAction.Profile -> put("profile", a.profile.json)
                    VideoAction.Pause, VideoAction.Resume, VideoAction.Stop -> Unit
                }
            }
            AppCommand.Close -> Unit
        }
    }

    fun decodeCommand(element: JsonElement): AppCommand {
        val o = obj(element, "Befehl")
        return when (val c = string(o, "c")) {
            "definePages" -> {
                val project = o["project"]
                if (project != null && project !is JsonNull) {
                    val pages = try {
                        BaukastenProject.normalize(project).pages
                    } catch (e: BaukastenFormatException) {
                        bad(e.message.orEmpty())
                    }
                    AppCommand.DefinePages(pages)
                } else {
                    AppCommand.DefinePages(array(o, "pages").map(::decodePage))
                }
            }
            "show" -> AppCommand.Show(string(o, "page"))
            "replace" -> AppCommand.Replace(string(o, "page"))
            "patch" -> AppCommand.Patch(string(o, "page"), o["changes"] as? JsonObject ?: bad("„changes“ muss ein Objekt sein"))
            "setBlocks" -> AppCommand.SetBlocks(string(o, "page"), array(o, "blocks").map(::decodeBlock))
            "toast" -> AppCommand.Toast(string(o, "text"), optInt(o, "ms") ?: 2000)
            "vibrate" -> AppCommand.Vibrate(Vibration.of(optString(o, "pattern") ?: "tick") ?: bad("unbekanntes Muster „${o["pattern"]}“"))
            "menu" -> AppCommand.Menu(
                array(o, "items").map {
                    val item = obj(it, "Menüeintrag")
                    MenuItem(string(item, "id"), string(item, "text"))
                },
            )
            "buzz" -> AppCommand.Buzz(
                array(o, "notes").map { note ->
                    val a = note as? JsonArray ?: bad("Ton muss [Hz, Prozent, ms] sein")
                    if (a.size != 3) bad("Ton muss [Hz, Prozent, ms] sein")
                    BuzzNote(intOf(a[0], "Hz"), intOf(a[1], "Prozent"), intOf(a[2], "ms"))
                },
            )
            "subscribe" -> AppCommand.Subscribe(sensor(o), optInt(o, "rate") ?: 0)
            "unsubscribe" -> AppCommand.Unsubscribe(sensor(o))
            "audio" -> AppCommand.Audio(bool(o, "on"))
            "askText" -> AppCommand.AskText(
                string(o, "tag"),
                optString(o, "prompt") ?: "",
                optArray(o, "suggestions").map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: bad("Vorschläge müssen Texte sein") },
            )
            "video" -> AppCommand.Video(string(o, "block"), videoAction(o))
            "close" -> AppCommand.Close
            else -> bad("unbekannter Befehl „$c“")
        }
    }

    // --- Pages and blocks -----------------------------------------------------------------------

    fun encodePage(page: Page): JsonObject = buildJsonObject {
        put("id", page.id)
        put("name", page.name)
        put("statusBar", page.statusBar)
        put("notes", page.notes)
        page.input?.let { put("input", it.json) }
        put("blocks", JsonArray(page.blocks.map(::encodeBlock)))
    }

    fun decodePage(element: JsonElement): Page {
        val o = obj(element, "Seite")
        return Page(
            id = id(o),
            name = optString(o, "name") ?: "",
            blocks = optArray(o, "blocks").map(::decodeBlock),
            statusBar = optBool(o, "statusBar") ?: true,
            notes = optString(o, "notes") ?: "",
            input = optString(o, "input")?.let { InputMode.of(it) ?: bad("„input“ muss pointer oder gestures sein") },
        )
    }

    fun encodeBlock(block: Block): JsonObject = buildJsonObject {
        put("id", block.id)
        when (block) {
            is Block.Heading -> {
                put("type", "heading"); put("text", block.text); put("align", block.align.json); put("size", block.size.json)
            }
            is Block.Text -> {
                put("type", "text"); put("text", block.text); put("align", block.align.json)
            }
            is Block.Button -> {
                put("type", "button"); put("text", block.text); put("target", block.target); put("action", block.action)
            }
            is Block.List -> {
                put("type", "list"); put("style", block.style.json); put("items", JsonArray(block.items.map(::encodeItem)))
            }
            is Block.Toggle -> {
                put("type", "toggle"); put("text", block.text); put("on", block.on)
            }
            is Block.Value -> {
                put("type", "value"); put("text", block.text); put("value", block.value)
            }
            is Block.Progress -> {
                put("type", "progress"); put("text", block.text); put("value", block.value)
            }
            is Block.Divider -> put("type", "divider")
            is Block.Image -> {
                put("type", "image"); put("src", block.src); put("w", block.w); put("h", block.h)
                put("align", block.align.json); put("bleed", block.bleed)
            }
        }
    }

    /** A block in its JSON form; missing optional fields get their defaults, wrong ones are an error. */
    fun decodeBlock(element: JsonElement): Block {
        val o = obj(element, "Baustein")
        val id = id(o)
        fun text() = optString(o, "text") ?: ""
        fun align(default: Align) = optString(o, "align")?.let { Align.of(it) ?: bad("„align“ muss left oder center sein") } ?: default
        return when (val type = string(o, "type")) {
            "heading" -> Block.Heading(
                id, text(), align(Align.LEFT),
                optString(o, "size")?.let { HeadingSize.of(it) ?: bad("„size“ muss normal oder gross sein") } ?: HeadingSize.NORMAL,
            )
            "text" -> Block.Text(id, text(), align(Align.LEFT))
            "button" -> Block.Button(id, text(), optString(o, "target"), optString(o, "action") ?: "")
            "list" -> Block.List(
                id,
                optArray(o, "items").map(::decodeItem),
                optString(o, "style")?.let { ListStyle.of(it) ?: bad("„style“ muss bullets, checks oder numbers sein") } ?: ListStyle.BULLETS,
            )
            "toggle" -> Block.Toggle(id, text(), optBool(o, "on") ?: false)
            "value" -> Block.Value(id, text(), optString(o, "value") ?: "")
            "progress" -> Block.Progress(id, text(), optInt(o, "value") ?: 0)
            "divider" -> Block.Divider(id)
            "image" -> Block.Image(
                id, optString(o, "src"), int(o, "w"), int(o, "h"), align(Align.CENTER), optBool(o, "bleed") ?: false,
            )
            else -> bad("unbekannte Bausteinart „$type“")
        }
    }

    fun encodeItem(item: ListItem): JsonObject = buildJsonObject {
        put("text", item.text)
        put("done", item.done)
    }

    /** A list row as `{ "text", "done" }` or as a plain string. */
    fun decodeItem(element: JsonElement): ListItem = when {
        element is JsonPrimitive && element.isString -> ListItem(element.content)
        element is JsonObject -> ListItem(optString(element, "text") ?: "", optBool(element, "done") ?: false)
        else -> bad("Listeneintrag muss Text oder { text, done } sein")
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun bad(message: String): Nothing = throw CommandException.badValue(message)

    private fun obj(e: JsonElement, what: String): JsonObject = e as? JsonObject ?: bad("$what muss ein JSON-Objekt sein")

    private fun id(o: JsonObject): String {
        val id = string(o, "id")
        if (!Block.ID.matches(id)) bad("Kennung „$id“ ist ungültig (erlaubt: A–Z, a–z, 0–9, _ . -, höchstens 40 Zeichen)")
        return id
    }

    private fun string(o: JsonObject, key: String): String =
        optString(o, key) ?: bad("„$key“ fehlt oder ist kein Text")

    private fun optString(o: JsonObject, key: String): String? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("„$key“ muss Text sein")
    }

    private fun bool(o: JsonObject, key: String): Boolean = optBool(o, key) ?: bad("„$key“ fehlt")

    private fun optBool(o: JsonObject, key: String): Boolean? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: bad("„$key“ muss true oder false sein")
    }

    private fun int(o: JsonObject, key: String): Int = optInt(o, key) ?: bad("„$key“ fehlt")

    private fun optInt(o: JsonObject, key: String): Int? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        return intOf(v, key)
    }

    private fun intOf(v: JsonElement, what: String): Int =
        (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: bad("„$what“ muss eine ganze Zahl sein")

    private fun long(o: JsonObject, key: String): Long =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: bad("„$key“ muss eine ganze Zahl sein")

    private fun double(o: JsonObject, key: String): Double =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: bad("„$key“ muss eine Zahl sein")

    private fun float(o: JsonObject, key: String): Float = double(o, key).toFloat()

    private fun array(o: JsonObject, key: String): JsonArray = o[key] as? JsonArray ?: bad("„$key“ muss eine Liste sein")

    private fun optArray(o: JsonObject, key: String): JsonArray {
        val v = o[key]
        if (v == null || v is JsonNull) return JsonArray(emptyList())
        return v as? JsonArray ?: bad("„$key“ muss eine Liste sein")
    }

    private fun optLong(o: JsonObject, key: String): Long? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: bad("„$key“ muss eine ganze Zahl sein")
    }

    private fun profile(o: JsonObject): VideoProfile =
        optString(o, "profile")?.let { VideoProfile.of(it) ?: bad("unbekanntes Videoprofil „$it“") } ?: VideoProfile.BALANCED

    private fun videoAction(o: JsonObject): VideoAction = when (val action = string(o, "action")) {
        "play" -> VideoAction.Play(string(o, "src"), profile(o), optBool(o, "sound") ?: false, optLong(o, "start") ?: 0)
        "pause" -> VideoAction.Pause
        "resume" -> VideoAction.Resume
        "seek" -> VideoAction.Seek(optLong(o, "position") ?: bad("„position“ fehlt"))
        "profile" -> VideoAction.Profile(profile(o))
        "stop" -> VideoAction.Stop
        else -> bad("unbekannte Videoaktion „$action“")
    }

    private fun sensor(o: JsonObject): Sensor =
        Sensor.of(string(o, "sensor")) ?: bad("unbekannter Sensor „${o["sensor"]}“")
}

/** kotlinx.serialization entry for [AppEvent]; JSON only. */
object AppEventSerializer : KSerializer<AppEvent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ch.madtreasures.g2watch.apps.AppEvent")

    override fun serialize(encoder: Encoder, value: AppEvent) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("AppEvent is JSON only")
        json.encodeJsonElement(AppJson.encodeEvent(value))
    }

    override fun deserialize(decoder: Decoder): AppEvent {
        val json = decoder as? JsonDecoder ?: throw SerializationException("AppEvent is JSON only")
        return AppJson.decodeEvent(json.decodeJsonElement()) ?: throw SerializationException("unknown event kind")
    }
}

/** kotlinx.serialization entry for [AppCommand]; JSON only. */
object AppCommandSerializer : KSerializer<AppCommand> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ch.madtreasures.g2watch.apps.AppCommand")

    override fun serialize(encoder: Encoder, value: AppCommand) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("AppCommand is JSON only")
        json.encodeJsonElement(AppJson.encodeCommand(value))
    }

    override fun deserialize(decoder: Decoder): AppCommand {
        val json = decoder as? JsonDecoder ?: throw SerializationException("AppCommand is JSON only")
        return AppJson.decodeCommand(json.decodeJsonElement())
    }
}

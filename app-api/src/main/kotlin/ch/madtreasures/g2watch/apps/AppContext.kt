package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What an app can do (02 §6.2). Call it only from [G2App.onEvent] or from callbacks the host delivers
 * (timers, [fetch] results), which all run on the app thread.
 *
 * A command the host cannot carry out (unknown page or block, missing permission, invalid value) is not
 * executed; the host writes it to the watch log (Settings → Protokoll). In tests `FakeAppContext`
 * throws an [IllegalArgumentException] instead, so mistakes surface at once.
 */
interface AppContext {
    /** Makes [pages] known; a page with the same id replaces the old one. */
    fun definePages(pages: List<Page>)

    /** The pages of a Baukasten project. */
    fun definePages(project: BaukastenProject)

    /** Shows [pageId] and puts it on the history, so back returns to the current page. */
    fun show(pageId: String)

    /** Shows [pageId] in place of the current page, without history. */
    fun replace(pageId: String)

    /** Changes fields of blocks on [pageId]; only the fields named change. */
    fun patch(pageId: String, changes: PatchBuilder.() -> Unit)

    /** Replaces all blocks of [pageId], e.g. for a list that grows. */
    fun setBlocks(pageId: String, blocks: List<Block>)

    /** A short note at the bottom of the app area. */
    fun toast(text: String, ms: Int = 2000)

    /** The watch vibrates. */
    fun vibrate(pattern: Vibration = Vibration.TICK)

    /** The app's own entries in the app menu, at most 10; an empty list removes them. */
    fun menu(items: List<MenuItem>)

    /** Plays [notes] on the glasses' buzzer, at most 48; needs [Permission.BUZZER]. Wired up in M6. */
    fun buzz(notes: List<BuzzNote>)

    /** An [AppEvent.Timer] with [tag] after [ms] (again and again with [repeat]); replaces a timer with the same tag. */
    fun timer(tag: String, ms: Long, repeat: Boolean = false)

    fun cancelTimer(tag: String)

    /** Sensor events on; needs the sensor's permission. [rate]: 0 = default. Sensors are wired up in M6. */
    fun subscribe(sensor: Sensor, rate: Int = 0)

    fun unsubscribe(sensor: Sensor)

    /** The glasses' microphone on or off; needs [Permission.MIC]. Wired up in M6. */
    fun audio(on: Boolean)

    /**
     * An HTTPS request on a background thread; [onResult] runs on the app thread. At most 10 s and
     * 1 MB of answer. Needs [Permission.NETWORK]. Works over LTE without a phone.
     */
    fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit)

    /**
     * Asks the wearer for a line of text on the watch: keyboard, voice or one of at most 5 [suggestions].
     * The answer comes as [AppEvent.TextInput] with [tag] (text null when the wearer cancelled). One
     * question at a time: a new one cancels the open one. The glasses show a hint to look at the watch.
     */
    fun askText(tag: String, prompt: String, suggestions: List<String> = emptyList())

    /**
     * Plays, pauses, moves or stops a video in image block [block] (03 §10). The watch decodes it on its
     * own threads and writes its pictures into the block (at most as often as the page is drawn);
     * [AppEvent.Video] reports the state. Addresses on the internet need [Permission.NETWORK]. While the
     * app is hidden the video pauses, and it ends with the app.
     */
    fun video(block: String, action: VideoAction)

    /**
     * Searches videos on YouTube on a background thread; [onResult] runs on the app thread. Needs
     * [Permission.NETWORK]. Takes a few seconds; the answer holds about 20 hits.
     */
    fun videoSearch(query: String, onResult: (VideoSearchResult) -> Unit)

    /**
     * Opens, works or ends a web page in image block [block] (05 §10, since interface version 2). The
     * watch loads it in its browser engine, paints it at the block's size and turns it into the glasses'
     * picture, which it writes into the block whenever the page changed; [AppEvent.Web] reports state,
     * address and title. While a page is open, clicks and scrolling over the block reach the app as
     * [AppEvent.ImageClick] and [AppEvent.ImageScroll], and back on its page first goes back in the
     * page's own history. Addresses need [Permission.NETWORK]. While the app is hidden the page rests,
     * and it ends with the app.
     */
    fun web(block: String, action: WebAction)

    /** A small store of this app, at most 256 KiB, kept across restarts. */
    val storage: AppStorage

    /** A line in the watch log (Settings → Protokoll). */
    fun log(message: String)

    /** Ends the app (it gets [AppEvent.Stop]). */
    fun close()
}

/** Key-value store of one app; values are JSON. */
interface AppStorage {
    fun get(key: String): JsonElement?

    /** Stores [value] under [key]; [JsonNull] removes the key. Fails beyond 256 KiB for the app. */
    fun set(key: String, value: JsonElement)

    companion object {
        const val MAX_BYTES = 256 * 1024
    }
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** [status] is 0 when there was no answer; then [error] says why. */
data class HttpResult(val status: Int, val body: String, val error: String? = null) {
    val ok: Boolean get() = error == null && status in 200..299
}

/**
 * Builds the changes of a patch (02 §6.2 lists the fields per block type):
 *
 *     ui.patch("p_main") {
 *         text("zeit", "1:05,3")
 *         on("leise", true)
 *         item("items", 2, done = true)
 *     }
 */
class PatchBuilder {
    private val changes = LinkedHashMap<String, LinkedHashMap<String, JsonElement>>()

    private fun field(block: String, name: String, value: JsonElement) {
        changes.getOrPut(block) { LinkedHashMap() }[name] = value
    }

    /** Text of a heading, text or button, or the label of a toggle, value or progress. */
    fun text(block: String, text: String) = field(block, "text", JsonPrimitive(text))

    fun align(block: String, align: Align) = field(block, "align", JsonPrimitive(align.json))

    /** Target of a button: a page id, [Block.BACK] or null. */
    fun target(block: String, target: String?) = field(block, "target", JsonPrimitive(target))

    /** State of a toggle. */
    fun on(block: String, on: Boolean) = field(block, "on", JsonPrimitive(on))

    /** Shown value of a value block. */
    fun value(block: String, value: String) = field(block, "value", JsonPrimitive(value))

    /** Value of a progress block, 0–100. */
    fun progress(block: String, value: Int) = field(block, "value", JsonPrimitive(value))

    /** All rows of a list. */
    fun items(block: String, items: List<ListItem>) =
        field(block, "items", JsonArray(items.map { AppJson.encodeItem(it) }))

    /** One row of a list; only the given parts change. */
    fun item(block: String, index: Int, text: String? = null, done: Boolean? = null) =
        field(
            block,
            "item",
            buildJsonObject {
                put("index", index)
                if (text != null) put("text", text)
                if (done != null) put("done", done)
            },
        )

    /** Picture of an image block; size only if given. */
    fun image(block: String, src: String?, w: Int? = null, h: Int? = null) {
        field(block, "src", if (src == null) JsonNull else JsonPrimitive(src))
        if (w != null) field(block, "w", JsonPrimitive(w))
        if (h != null) field(block, "h", JsonPrimitive(h))
    }

    fun build(): JsonObject = JsonObject(changes.mapValues { JsonObject(it.value) })
}

package ch.madtreasures.g2watch.apps.builtin.youtube

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.VideoAction
import ch.madtreasures.g2watch.apps.VideoItem
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.BreakIterator

/**
 * YouTube on the glasses, entirely on the watch (03 §10): search by keyboard or voice on the watch,
 * pick a hit with pointer or temple, and the watch decodes the video and shows it as a grey raster. On
 * the video page the temple works like a remote: tap = pause/play, swipe = 10 s back or on, double tap =
 * back to the list; a long press switches the profile (Stabil 1, Ausgewogen 2, Schnell 4 pictures a
 * second). "Testbild" plays the watch's own test video, without internet.
 */
class YouTubeApp : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.youtube",
        name = "YouTube",
        version = "1.0.0",
        permissions = setOf(Permission.NETWORK),
        description = "YouTube-Videos suchen und als Graustufen-Raster auf der Brille ansehen.",
    )

    private var profile = VideoProfile.BALANCED
    private var sound = false
    private var history: List<VideoItem> = emptyList()
    private var queries: List<String> = emptyList()
    private var hits: List<VideoItem> = emptyList()

    /** The video on [VIDEO] and what it last reported. */
    private var current: VideoItem? = null
    private var state = VideoState.LOADING
    private var position = 0L
    private var duration = 0L

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> start(ui)
            is AppEvent.Click -> click(event.block, ui)
            is AppEvent.Navigate -> if (event.to == HISTORY) showHistory(ui)
            is AppEvent.Toggle -> if (event.block == SOUND) setSound(event.on, ui)
            is AppEvent.TextInput -> if (event.tag == ASK_SEARCH) search(event.text, ui)
            is AppEvent.Video -> if (event.block == PICTURE) videoChanged(event, ui)
            // Only the video page takes gestures.
            is AppEvent.Gesture -> gesture(event.gesture, ui)
            is AppEvent.Back -> if (event.page == VIDEO) stopVideo(ui)
            is AppEvent.Menu -> menu(event.item, ui)
            else -> Unit
        }
    }

    // --- Start page -----------------------------------------------------------------------------

    private fun start(ui: AppContext) {
        load(ui)
        ui.definePages(listOf(startPage(), resultsPage(emptyList()), historyPage(), videoPage()))
        ui.menu(menuItems())
        ui.show(START)
    }

    private fun startPage() = Page(
        START, "YouTube",
        listOf(
            Block.Button(SEARCH, "Suchen"),
            Block.Button(HISTORY_BUTTON, "Verlauf", target = HISTORY),
            Block.Button(PROFILE, profileText()),
            Block.Toggle(SOUND, "Ton auf der Uhr", on = sound),
            Block.Button(TEST, "Testbild"),
        ),
    )

    private fun profileText() = "Profil: ${profile.label}"

    /** In the video there is no new search: back to the list first (the history stays simple). */
    private fun menuItems() = buildList {
        if (current == null) add(MenuItem(MENU_SEARCH, "Neue Suche"))
        add(MenuItem(MENU_PROFILE, "Profil wechseln"))
        add(MenuItem(MENU_SOUND, if (sound) "Ton aus" else "Ton an"))
    }

    private fun click(block: String, ui: AppContext) {
        when {
            block == SEARCH || block == AGAIN -> askSearch(ui)
            block == PROFILE -> nextProfile(ui)
            block == TEST -> play(TEST_VIDEO, ui)
            block == CLEAR_HISTORY -> {
                history = emptyList()
                save(ui)
                showHistory(ui)
                ui.toast("Verlauf gelöscht")
            }
            block.startsWith(HIT) -> block.removePrefix(HIT).toIntOrNull()?.let { hits.getOrNull(it) }?.let { play(it, ui) }
            block.startsWith(SEEN) -> block.removePrefix(SEEN).toIntOrNull()?.let { history.getOrNull(it) }?.let { play(it, ui) }
        }
    }

    private fun menu(item: String, ui: AppContext) {
        when (item) {
            MENU_SEARCH -> askSearch(ui)
            MENU_PROFILE -> nextProfile(ui)
            MENU_SOUND -> setSound(!sound, ui)
        }
    }

    private fun nextProfile(ui: AppContext) {
        profile = profile.next
        save(ui)
        ui.patch(START) { text(PROFILE, profileText()) }
        if (current != null && state != VideoState.ERROR) ui.video(PICTURE, VideoAction.Profile(profile))
        ui.toast("${profile.label}: ${PER_SECOND[profile]}")
    }

    private fun setSound(on: Boolean, ui: AppContext) {
        sound = on
        save(ui)
        ui.patch(START) { on(SOUND, on) }
        ui.menu(menuItems())
        // Sound needs other streams: start again where the video is.
        val playing = current
        if (playing != null && state != VideoState.ERROR && state != VideoState.ENDED) {
            ui.video(PICTURE, VideoAction.Play(playing.url, profile, sound, position))
        }
        ui.toast(if (on) "Ton auf der Uhr an" else "Ton aus")
    }

    // --- Search ---------------------------------------------------------------------------------

    private fun askSearch(ui: AppContext) = ui.askText(ASK_SEARCH, "YouTube durchsuchen", queries.take(5))

    private fun search(text: String?, ui: AppContext) {
        val query = text?.trim().orEmpty()
        if (query.isEmpty()) {
            ui.toast("Keine Suche")
            return
        }
        queries = (listOf(query) + queries.filter { !it.equals(query, ignoreCase = true) }).take(MAX_QUERIES)
        save(ui)
        hits = emptyList()
        ui.definePages(listOf(resultsPage(listOf(Block.Text(RESULTS_STATUS, "Suche läuft …")), query)))
        ui.show(RESULTS)
        ui.videoSearch(query) { result -> showResults(query, result, ui) }
    }

    private fun showResults(query: String, result: VideoSearchResult, ui: AppContext) {
        hits = result.items
        val blocks = when {
            !result.ok -> listOf(Block.Text(RESULTS_STATUS, "Suche ging nicht: ${result.error}"), Block.Button(AGAIN, "Nochmal suchen"))
            hits.isEmpty() -> listOf(Block.Text(RESULTS_STATUS, "Nichts gefunden."), Block.Button(AGAIN, "Anders suchen"))
            else -> hits.flatMapIndexed { i, v -> listOf(Block.Button("$HIT$i", title(v)), Block.Text("$HIT_INFO$i", info(v))) }
        }
        ui.definePages(listOf(resultsPage(blocks, query)))
    }

    private fun resultsPage(blocks: List<Block>, query: String = "") =
        Page(RESULTS, "Treffer", listOf(Block.Heading(RESULTS_TITLE, if (query.isEmpty()) "Treffer" else "„${shorten(query, 30)}“")) + blocks)

    // --- History --------------------------------------------------------------------------------

    private fun historyPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(HISTORY_TITLE, "Zuletzt angesehen")
        if (history.isEmpty()) {
            blocks += Block.Text(HISTORY_EMPTY, "Noch nichts angesehen.")
        } else {
            history.forEachIndexed { i, v ->
                blocks += Block.Button("$SEEN$i", title(v))
                blocks += Block.Text("$SEEN_INFO$i", info(v))
            }
            blocks += Block.Button(CLEAR_HISTORY, "Verlauf löschen")
        }
        return Page(HISTORY, "Verlauf", blocks)
    }

    private fun showHistory(ui: AppContext) = ui.definePages(listOf(historyPage()))

    // --- Video ----------------------------------------------------------------------------------

    private fun videoPage() = Page(
        VIDEO, "Video",
        listOf(
            Block.Image(PICTURE, null, VIDEO_W, VIDEO_H),
            Block.Text(STATUS, "", Align.CENTER),
        ),
        statusBar = false,
        input = InputMode.GESTURES,
    )

    private fun play(item: VideoItem, ui: AppContext) {
        current = item
        state = VideoState.LOADING
        position = 0
        duration = item.durationS * 1000
        if (item.url != TEST_VIDEO.url) {
            history = (listOf(item) + history.filter { it.url != item.url }).take(MAX_HISTORY)
            save(ui)
        }
        // A fresh page: black picture, loading line.
        ui.definePages(listOf(videoPage()))
        ui.patch(VIDEO) { text(STATUS, "Lädt … Tippen = Pause, Wischen = ±10 s") }
        ui.show(VIDEO)
        ui.video(PICTURE, VideoAction.Play(item.url, profile, sound))
        ui.menu(menuItems())
    }

    private fun stopVideo(ui: AppContext) {
        if (current == null) return
        ui.video(PICTURE, VideoAction.Stop)
        current = null
        ui.menu(menuItems())
        showHistory(ui)
    }

    private fun videoChanged(e: AppEvent.Video, ui: AppContext) {
        val item = current ?: return
        position = e.positionMs
        if (e.durationMs > 0) duration = e.durationMs
        if (e.state == state && e.state == VideoState.PLAYING) return
        state = e.state
        val line = when (e.state) {
            VideoState.LOADING -> "Lädt … Tippen = Pause, Wischen = ±10 s"
            VideoState.PLAYING -> shorten(title(item), 42)
            VideoState.PAUSED -> "Pause ${time()} – tippen: weiter"
            VideoState.BUFFERING -> "Lädt nach … ${time()}"
            VideoState.ENDED -> "Ende – tippen: nochmal"
            VideoState.ERROR -> shorten(e.message ?: "Fehler", 60)
        }
        ui.patch(VIDEO) { text(STATUS, line) }
        if (e.state == VideoState.ERROR) ui.toast("Video geht nicht")
    }

    private fun gesture(kind: GestureKind, ui: AppContext) {
        if (current == null) return
        when (kind) {
            GestureKind.CLICK -> when (state) {
                VideoState.PLAYING, VideoState.BUFFERING, VideoState.LOADING -> ui.video(PICTURE, VideoAction.Pause)
                VideoState.PAUSED, VideoState.ENDED -> ui.video(PICTURE, VideoAction.Resume)
                VideoState.ERROR -> current?.let { play(it, ui) }
            }
            GestureKind.SCROLL_DOWN -> seek(SEEK_MS, ui)
            GestureKind.SCROLL_UP -> seek(-SEEK_MS, ui)
            GestureKind.LONG_PRESS, GestureKind.SWIPE_LEFT -> nextProfile(ui)
            else -> Unit
        }
    }

    private fun seek(delta: Long, ui: AppContext) {
        if (state == VideoState.ERROR || current?.live == true) return
        val max = if (duration > 0) duration - 1000 else Long.MAX_VALUE
        position = (position + delta).coerceIn(0, max.coerceAtLeast(0))
        ui.video(PICTURE, VideoAction.Seek(position))
        ui.toast("${if (delta > 0) "+" else "−"}${kotlin.math.abs(delta) / 1000} s · ${time()}", 1500)
    }

    private fun time(): String = if (duration > 0) "${clock(position)} / ${clock(duration)}" else clock(position)

    // --- Store ----------------------------------------------------------------------------------

    private fun load(ui: AppContext) {
        profile = (ui.storage.get(KEY_PROFILE) as? JsonPrimitive)?.contentOrNull?.let { VideoProfile.of(it) } ?: VideoProfile.BALANCED
        sound = (ui.storage.get(KEY_SOUND) as? JsonPrimitive)?.booleanOrNull ?: false
        queries = (ui.storage.get(KEY_QUERIES) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        history = (ui.storage.get(KEY_HISTORY) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            VideoItem(
                url = url,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                channel = o["channel"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationS = o["duration"]?.jsonPrimitive?.longOrNull ?: 0,
                live = o["live"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.orEmpty()
    }

    private fun save(ui: AppContext) {
        ui.storage.set(KEY_PROFILE, JsonPrimitive(profile.json))
        ui.storage.set(KEY_SOUND, JsonPrimitive(sound))
        ui.storage.set(KEY_QUERIES, JsonArray(queries.map { JsonPrimitive(it) }))
        ui.storage.set(
            KEY_HISTORY,
            JsonArray(
                history.map {
                    buildJsonObject {
                        put("url", it.url)
                        put("title", it.title)
                        put("channel", it.channel)
                        put("duration", it.durationS)
                        put("live", it.live)
                    }
                },
            ),
        )
    }

    companion object {
        const val START = "p_start"
        const val RESULTS = "p_treffer"
        const val HISTORY = "p_verlauf"
        const val VIDEO = "p_video"

        const val SEARCH = "suchen"
        const val HISTORY_BUTTON = "verlauf"
        const val PROFILE = "profil"
        const val SOUND = "ton"
        const val TEST = "testbild"
        const val RESULTS_TITLE = "treffer_titel"
        const val RESULTS_STATUS = "treffer_status"
        const val AGAIN = "nochmal"
        const val HISTORY_TITLE = "verlauf_titel"
        const val HISTORY_EMPTY = "verlauf_leer"
        const val CLEAR_HISTORY = "verlauf_loeschen"
        const val PICTURE = "bild"
        const val STATUS = "status"

        /** Buttons and info lines of hits and history entries: prefix + index. */
        const val HIT = "v"
        const val HIT_INFO = "vi"
        const val SEEN = "h"
        const val SEEN_INFO = "hi"

        const val ASK_SEARCH = "suche"
        const val MENU_SEARCH = "neu"
        const val MENU_PROFILE = "profil"
        const val MENU_SOUND = "ton"

        /** 16:9, with the status line below it exactly the height of the full screen (288). */
        const val VIDEO_W = 416
        const val VIDEO_H = 234
        const val SEEK_MS = 10_000L
        const val MAX_HISTORY = 20
        const val MAX_QUERIES = 5

        /** The watch's own test video (`TestPattern`), for trying the glasses without internet. */
        val TEST_VIDEO = VideoItem("test:muster", "Testbild", "G2 Watch", 60)

        private const val KEY_PROFILE = "profil"
        private const val KEY_SOUND = "ton"
        private const val KEY_QUERIES = "suchen"
        private const val KEY_HISTORY = "verlauf"

        private val PER_SECOND = mapOf(
            VideoProfile.STABLE to "1 Bild pro Sekunde",
            VideoProfile.BALANCED to "2 Bilder pro Sekunde",
            VideoProfile.FAST to "4 Bilder pro Sekunde",
        )

        /** "Kanal · 4:56 · 1,2 Mio. Aufrufe", short enough for one line. */
        fun info(v: VideoItem): String {
            val parts = ArrayList<String>()
            if (v.channel.isNotBlank()) parts += shorten(v.channel, 24)
            parts += if (v.live) "live" else if (v.durationS > 0) clock(v.durationS * 1000) else ""
            if (v.views >= 0) parts += views(v.views)
            return parts.filter { it.isNotEmpty() }.joinToString(" · ")
        }

        /** 0:07, 4:56, 1:02:03. */
        fun clock(ms: Long): String {
            val s = ms.coerceAtLeast(0) / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
            else "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
        }

        /** 950 Aufrufe, 12.345 Aufrufe, 1,2 Mio. Aufrufe. */
        fun views(n: Long): String = when {
            n >= 1_000_000_000 -> "%s Mrd. Aufrufe".format(java.util.Locale.ROOT, decimal(n / 100_000_000))
            n >= 1_000_000 -> "%s Mio. Aufrufe".format(java.util.Locale.ROOT, decimal(n / 100_000))
            n >= 1_000 -> "%d.%03d Aufrufe".format(java.util.Locale.ROOT, n / 1000, n % 1000)
            n == 1L -> "1 Aufruf"
            else -> "$n Aufrufe"
        }

        /** Tenths as German decimal: 12 → "1,2", 30 → "3". */
        private fun decimal(tenths: Long): String = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

        /** [s] cut to at most [max] chars with "…", between user-perceived characters (an emoji stays whole). */
        fun shorten(s: String, max: Int): String {
            if (s.length <= max) return s
            val clusters = BreakIterator.getCharacterInstance()
            clusters.setText(s)
            val end = clusters.preceding(max).coerceAtLeast(0)
            return s.substring(0, end).trimEnd() + "…"
        }

        /** The title to show; "Ohne Titel" when YouTube has none. */
        fun title(v: VideoItem): String = v.title.ifBlank { "Ohne Titel" }
    }
}

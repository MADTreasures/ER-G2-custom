package ch.madtreasures.musik

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.PlayerAction
import ch.madtreasures.g2watch.apps.PlayerState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * Music on the glasses (README.md of the package): Spotify – or whatever plays on the watch – shown and steered
 * from the glasses, and radio: the Swiss stations of DAB+ as internet streams ([SwissStations]) and any other
 * station of radio-browser.info ([RadioBrowser]). The sound comes from the watch, through the headphones paired
 * with it or its speaker; the glasses have none.
 *
 * The players on the watch come through the watch app's media controls ([MediaAction], interface 6), followed
 * while the app is visible; the radio is the app's own stream ([PlayerAction]), which plays on while the app is
 * hidden and ends with it. The two give way to each other: a station that starts pauses Spotify, and Spotify
 * that plays pauses the station.
 *
 * Pages: the start ([P_START]: the player, the station, the station list, volume and where the sound goes), the
 * player ([P_PLAYER]) and the station playing ([P_SENDER]), which take gestures – tap = play or pause, swipe =
 * next or previous song or station, long press = volume, then swiping louder or quieter – the station list
 * ([P_RADIO]) and the search results ([P_TREFFER]).
 */
class MusikApp(private val clock: () -> Long = System::currentTimeMillis) : G2App {
    override val manifest = AppManifest(
        id = ID,
        name = "Musik",
        version = VERSION,
        permissions = setOf(Permission.MEDIA, Permission.NETWORK),
        description = "Spotify und die anderen Player der Uhr von der Brille aus steuern, dazu Radio: die Schweizer Sender von DAB+ und weltweit über radio-browser.info.",
    )

    private lateinit var ui: AppContext
    private val shown = Shown()

    /** The pages as the host keeps them, oldest first: show puts one on top, replace swaps it, back takes it off. */
    private val stack = ArrayList<String>()
    private var visible = false
    private var shownMenu: List<MenuItem>? = null
    private var ticking = false

    // The players on the watch, as the last AppEvent.Media told.
    private var heard = false
    private var players: List<MediaInfo> = emptyList()
    private var volume = -1
    private var maxVolume = 0
    private var output: String? = null
    private var mediaNote: String? = null

    /** Long press on the player or the station: swiping changes the volume until a tap or a pause. */
    private var volumeMode = false

    // The radio: the station chosen, its stream as the watch reports it, the list swiping goes through.
    private var station: Station? = null
    private var streamUrl: String? = null
    private var streamState: PlayerState? = null
    private var streamTitle: String? = null
    private var streamMessage: String? = null
    private var healed = false
    private var zapList: List<Station> = emptyList()

    // Kept in the app's store.
    private var favorites: List<Station> = emptyList()
    private var last: Station? = null
    private var radioSearches: List<String> = emptyList()
    private var playerSearches: List<String> = emptyList()

    /** Stations of the list whose address failed, with the one radio-browser.info knew instead: uuid → address. */
    private val fixed = LinkedHashMap<String, String>()

    // Searching radio-browser.info.
    private var query = ""
    private var results: List<Station> = emptyList()
    private var searching = false
    private var searchError: String? = null
    private var searchNo = 0

    /** The server of radio-browser.info that answered last ([RadioBrowser.SERVERS]). */
    private var server = 0

    override fun onEvent(event: AppEvent, ui: AppContext) {
        this.ui = ui
        when (event) {
            AppEvent.Start -> start()
            AppEvent.Visible -> {
                visible = true
                ui.media(MediaAction.Follow)
                ticking()
            }
            AppEvent.Hidden -> {
                visible = false
                // The players are followed only while the glasses show the app; the station plays on.
                ui.media(MediaAction.Unfollow)
                ticking()
            }
            AppEvent.Stop -> Unit
            is AppEvent.Media -> media(event)
            is AppEvent.Player -> stream(event)
            is AppEvent.Gesture -> gesture(event.gesture)
            is AppEvent.Click -> click(event.block)
            is AppEvent.Navigate -> {
                if (stack.lastOrNull() != event.to) stack += event.to
                pageChanged()
            }
            is AppEvent.Back -> {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                pageChanged()
            }
            is AppEvent.Menu -> menu(event.item)
            is AppEvent.Timer -> when (event.tag) {
                // Only the song's time moves.
                TICK -> shown.sync(ui, listOf(playerPage()))
                VOLUME_TIMER -> if (volumeMode) leaveVolume()
            }
            is AppEvent.TextInput -> answer(event.tag, event.text)
            else -> Unit
        }
    }

    private fun start() {
        load()
        render()
        show(P_START)
    }

    // --- Pages --------------------------------------------------------------------------------------------

    private fun render() {
        shown.sync(ui, listOf(startPage(), playerPage(), radioPage(), senderPage(), resultsPage()))
    }

    private fun show(page: String) {
        ui.show(page)
        if (stack.lastOrNull() != page) stack += page
        pageChanged()
    }

    /** [page] in place of the one on top; if it lies right below, back to it (as the host does). */
    private fun replace(page: String) {
        ui.replace(page)
        when {
            stack.isEmpty() -> stack += page
            stack.size >= 2 && stack[stack.lastIndex - 1] == page -> stack.removeAt(stack.lastIndex)
            else -> stack[stack.lastIndex] = page
        }
        pageChanged()
    }

    private fun top(): String? = stack.lastOrNull()

    private fun pageChanged() {
        if (volumeMode) {
            volumeMode = false
            ui.cancelTimer(VOLUME_TIMER)
            render()
        }
        updateMenu()
        ticking()
    }

    /** The song's time moves on every second while the player page shows a song that plays. */
    private fun ticking() {
        val p = player()
        val want = visible && top() == P_PLAYER && p != null && p.state == MediaState.PLAYING && p.durationMs > 0
        if (want == ticking) return
        ticking = want
        if (want) ui.timer(TICK, TICK_MS, repeat = true) else ui.cancelTimer(TICK)
    }

    private fun startPage(): Page {
        val blocks = ArrayList<Block>()
        mediaNote?.let { blocks += Block.Text(ST_NOTE, "$it. Steuern geht trotzdem; Titel und Cover zeigt die App erst mit Zugriff (Anleitung im README der App).") }
        blocks += Block.Button(ST_PLAYER, playerButton(), target = P_PLAYER)
        val s = station
        if (s != null && streamState != null) {
            blocks += Block.Button(ST_STATION, "${s.name} – ${streamWord()}", target = P_SENDER)
        } else {
            last?.let { blocks += Block.Button(ST_LAST, "${it.name} hören") }
        }
        blocks += Block.Button(ST_RADIO, "Radiosender", target = P_RADIO)
        blocks += Block.Value(ST_VOLUME, "Lautstärke", if (volume >= 0 && maxVolume > 0) "$volume von $maxVolume" else "–")
        blocks += Block.Value(ST_OUTPUT, "Ton", output ?: if (heard) "kein Ausgang" else "–")
        return Page(P_START, "Musik", blocks)
    }

    private fun playerButton(): String {
        val p = player() ?: return "Spotify"
        return when (p.state) {
            MediaState.PLAYING -> "${p.appName} – spielt"
            MediaState.PAUSED -> "${p.appName} – Pause"
            else -> p.appName
        }
    }

    private fun streamWord(): String = when (streamState) {
        PlayerState.PLAYING -> "läuft"
        PlayerState.LOADING, PlayerState.BUFFERING -> "verbindet"
        PlayerState.PAUSED -> "Pause"
        PlayerState.ENDED -> "zu Ende"
        PlayerState.ERROR -> "Fehler"
        null -> "aus"
    }

    /** The player shown: the one that plays, else the one that played last (the watch reports them so). */
    private fun player(): MediaInfo? = players.firstOrNull()

    private fun playerPage(): Page {
        val p = player()
        val blocks = ArrayList<Block>()
        blocks += cover(p?.artwork)
        when {
            p != null -> {
                blocks += Block.Heading(PL_TITLE, TextFit.fit(p.title?.takeIf { it.isNotBlank() } ?: p.appName, HEADING, true, 1), Align.CENTER)
                val about = listOfNotNull(p.artist, p.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { p.appName }
                blocks += Block.Text(PL_LINE, if (volumeMode) VOLUME_HINT else TextFit.fit(about, BODY, false, 1), Align.CENTER)
                val at = p.positionNow(clock())
                blocks += when {
                    volumeMode -> volumeBar(PL_VOLUME)
                    p.durationMs > 0 -> Block.Progress(PL_TIME, "${stateWord(p.state)} · ${time(at)} / ${time(p.durationMs)}", (at * 100 / p.durationMs).toInt().coerceIn(0, 100))
                    else -> Block.Text(PL_STATE, stateWord(p.state), Align.CENTER)
                }
            }
            else -> {
                val note = mediaNote
                blocks += Block.Heading(PL_TITLE, if (note != null) "Spotify" else "Es spielt nichts", Align.CENTER)
                val line = if (note != null) "$note. Tippen und Wischen steuern trotzdem." else "Spotify auf der Uhr starten – oder hier tippen: Es spielt weiter, was zuletzt lief."
                blocks += Block.Text(PL_LINE, if (volumeMode) VOLUME_HINT else TextFit.fit(line, BODY, false, 2), Align.CENTER)
                blocks += if (volumeMode) volumeBar(PL_VOLUME) else Block.Text(PL_STATE, "Tippen: Play/Pause · Wischen: Titel", Align.CENTER)
            }
        }
        return Page(P_PLAYER, p?.appName ?: "Spotify", blocks, input = InputMode.GESTURES)
    }

    /** The cover, kept in its proportions within [COVER] × [COVER]; without one a note. */
    private fun cover(artwork: String?): Block.Image {
        val size = artwork?.takeIf { it.length <= Block.DATA_URL_MAX }?.let { pngSize(it) }
            ?: return Block.Image(PL_COVER, NOTE_PICTURE, COVER, COVER)
        val (w, h) = size
        return if (w >= h) {
            Block.Image(PL_COVER, artwork, COVER, maxOf(1, COVER * h / w))
        } else {
            Block.Image(PL_COVER, artwork, maxOf(1, COVER * w / h), COVER)
        }
    }

    private fun stateWord(state: MediaState) = when (state) {
        MediaState.PLAYING -> "Spielt"
        MediaState.PAUSED -> "Pause"
        MediaState.BUFFERING -> "Lädt"
        MediaState.STOPPED -> "Gestoppt"
        MediaState.ERROR -> "Fehler"
    }

    private fun volumeBar(id: String): Block.Progress =
        Block.Progress(id, volumeText(), if (volume >= 0 && maxVolume > 0) (volume * 100 / maxVolume).coerceIn(0, 100) else 0)

    private fun volumeText() = if (volume >= 0 && maxVolume > 0) "Lautstärke $volume von $maxVolume" else "Lautstärke"

    private fun radioPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Button(RA_SEARCH, "Sender suchen")
        if (favorites.isNotEmpty()) {
            blocks += Block.Text(RA_FAVORITES, "Favoriten")
            favorites.forEachIndexed { i, s -> blocks += Block.Button("$RA_FAVORITE$i", label(s)) }
        }
        var n = 0
        SwissStations.groups.forEachIndexed { g, group ->
            blocks += Block.Text("$RA_GROUP$g", group.label)
            for (s in group.stations) blocks += Block.Button("$RA_STATION${n++}", label(s))
        }
        blocks += Block.Text(RA_SOURCE, "Weitere Sender aus aller Welt: „Sender suchen“, in der offenen Senderliste radio-browser.info.")
        return Page(P_RADIO, "Radio", blocks)
    }

    /** A station's button: the one chosen says how it goes. */
    private fun label(s: Station): String =
        if (s.key == station?.key && streamState != null) "${s.name} – ${streamWord()}" else s.name

    private fun senderPage(): Page {
        val s = station
        val blocks = ArrayList<Block>()
        blocks += Block.Image(SE_PICTURE, RADIO_PICTURE, COVER, COVER)
        blocks += Block.Heading(SE_NAME, TextFit.fit(s?.name ?: "Radio", HEADING, true, 1), Align.CENTER)
        val song = when {
            volumeMode -> VOLUME_HINT
            streamState == PlayerState.ERROR -> streamMessage ?: "Der Sender spielt nicht"
            else -> streamTitle?.takeIf { it.isNotBlank() } ?: ""
        }
        blocks += Block.Text(SE_SONG, TextFit.fit(song, BODY, false, 2), Align.CENTER)
        blocks += if (volumeMode) volumeBar(SE_VOLUME) else Block.Text(SE_STATE, TextFit.fit(streamLine(s), BODY, false, 1), Align.CENTER)
        return Page(P_SENDER, "Radio", blocks, input = InputMode.GESTURES)
    }

    private fun streamLine(s: Station?): String = when (streamState) {
        null -> "Aus – Tippen spielt"
        PlayerState.LOADING -> "Verbindet …"
        PlayerState.PLAYING -> listOf("Live", s?.format.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        PlayerState.BUFFERING -> "Puffert …"
        PlayerState.PAUSED -> streamMessage?.let { "Pause – $it" } ?: "Pause"
        PlayerState.ENDED -> "Zu Ende – Tippen spielt"
        PlayerState.ERROR -> "Tippen: nochmals versuchen"
    }

    private fun resultsPage(): Page {
        val blocks = ArrayList<Block>()
        val error = searchError
        when {
            searching -> blocks += Block.Text(TR_INFO, "Suche „$query“ …")
            error != null -> {
                blocks += Block.Text(TR_INFO, error)
                blocks += Block.Button(TR_AGAIN, "Nochmals versuchen")
            }
            results.isEmpty() && query.isNotEmpty() -> blocks += Block.Text(TR_INFO, "Kein Sender mit „$query“ im Namen.")
            query.isNotEmpty() -> {
                blocks += Block.Text(TR_INFO, if (results.size == 1) "Ein Sender für „$query“" else "${results.size} Sender für „$query“")
                results.forEachIndexed { i, s ->
                    blocks += Block.Button("$TR_STATION$i", if (s.country.isEmpty()) label(s) else "${label(s)} · ${s.country}")
                }
            }
            else -> blocks += Block.Text(TR_INFO, "Noch nichts gesucht.")
        }
        if (!searching) blocks += Block.Button(TR_NEW, "Anders suchen")
        return Page(P_TREFFER, "Suche", blocks)
    }

    // --- The players on the watch -------------------------------------------------------------------------

    private fun media(e: AppEvent.Media) {
        heard = true
        players = e.players
        volume = e.volume
        maxVolume = e.maxVolume
        output = e.output
        mediaNote = e.message?.takeIf { e.players.isEmpty() }
        render()
        ticking()
    }

    private fun gesture(g: GestureKind) {
        val page = top()
        if (page != P_PLAYER && page != P_SENDER) return
        val onPlayer = page == P_PLAYER
        when (g) {
            GestureKind.CLICK -> when {
                volumeMode -> leaveVolume()
                onPlayer -> playPause()
                else -> pauseOrResume()
            }
            GestureKind.SCROLL_DOWN -> when {
                volumeMode -> changeVolume(-1)
                onPlayer -> skip(next = true)
                else -> zap(+1)
            }
            GestureKind.SCROLL_UP -> when {
                volumeMode -> changeVolume(+1)
                onPlayer -> skip(next = false)
                else -> zap(-1)
            }
            GestureKind.LONG_PRESS, GestureKind.SWIPE_LEFT -> if (volumeMode) leaveVolume() else enterVolume()
            else -> Unit
        }
    }

    private fun playPause() {
        val p = player()
        when {
            // Nothing to see (no access yet): the headphones' button, for whatever played last.
            p == null -> {
                // First the note, so that one of the watch (why it did not work) replaces it.
                if (mediaNote != null) ui.toast("Play/Pause", SHORT_NOTE_MS)
                ui.media(MediaAction.PlayPause())
            }
            p.state == MediaState.PLAYING || p.state == MediaState.BUFFERING -> ui.media(MediaAction.Pause(p.app))
            else -> ui.media(MediaAction.Play(p.app))
        }
    }

    private fun skip(next: Boolean) {
        val p = player()
        if (p != null && !(if (next) p.canNext else p.canPrevious)) {
            ui.toast(if (next) "${p.appName} kann hier nicht weiter" else "${p.appName} kann hier nicht zurück", SHORT_NOTE_MS)
            return
        }
        if (p == null && mediaNote != null) ui.toast(if (next) "Nächster Titel" else "Voriger Titel", SHORT_NOTE_MS)
        ui.media(if (next) MediaAction.Next(p?.app) else MediaAction.Previous(p?.app))
    }

    private fun enterVolume() {
        volumeMode = true
        ui.timer(VOLUME_TIMER, VOLUME_MS)
        render()
    }

    private fun leaveVolume() {
        volumeMode = false
        ui.cancelTimer(VOLUME_TIMER)
        render()
    }

    private fun changeVolume(steps: Int) {
        ui.media(MediaAction.Volume(steps))
        // Shown at once; the watch's next report says where it really is.
        if (volume >= 0 && maxVolume > 0) volume = (volume + steps).coerceIn(0, maxVolume)
        if (volumeMode) ui.timer(VOLUME_TIMER, VOLUME_MS)
        render()
    }

    private fun searchPlayer() {
        val p = player()
        val name = p?.takeIf { it.app != SPOTIFY && it.canSearch }?.appName ?: "Spotify"
        ui.askText(ASK_PLAYER, "Was soll $name spielen?", playerSearches.map { it.take(MAX_SUGGESTION) })
    }

    // --- The radio --------------------------------------------------------------------------------------

    private fun click(block: String) {
        when {
            block == RA_SEARCH || block == TR_NEW -> askStation()
            block == TR_AGAIN -> search(query)
            block == ST_LAST -> last?.let { s -> pick(s, listFor(s)) }
            block.startsWith(RA_FAVORITE) -> index(block, RA_FAVORITE)?.let { i -> favorites.getOrNull(i)?.let { pick(it, favorites) } }
            block.startsWith(RA_STATION) -> index(block, RA_STATION)?.let { i -> SwissStations.all.getOrNull(i)?.let { pick(it, SwissStations.all) } }
            block.startsWith(TR_STATION) -> index(block, TR_STATION)?.let { i -> results.getOrNull(i)?.let { pick(it, results) } }
        }
    }

    private fun index(block: String, prefix: String): Int? = block.removePrefix(prefix).toIntOrNull()

    /** The list a station is swiped through: the favorites if it is one, else the Swiss list if it is on it. */
    private fun listFor(s: Station): List<Station> = when {
        favorites.any { it.key == s.key } -> favorites
        SwissStations.all.any { it.key == s.key } -> SwissStations.all
        else -> listOf(s)
    }

    /** A station chosen from a list: the one chosen already only shows (and goes on if it was paused). */
    private fun pick(s: Station, list: List<Station>) {
        zapList = list
        if (s.key != station?.key) {
            play(s)
        } else {
            when (streamState) {
                PlayerState.PLAYING, PlayerState.LOADING, PlayerState.BUFFERING -> Unit
                PlayerState.PAUSED -> pauseOrResume()
                else -> play(s)
            }
        }
        if (top() != P_SENDER) show(P_SENDER)
    }

    private fun play(s: Station) {
        station = s
        healed = false
        val url = s.uuid?.let { fixed[it] } ?: s.url
        streamUrl = url
        streamState = PlayerState.LOADING
        streamTitle = null
        streamMessage = null
        ui.player(PlayerAction.Play(url, s.name))
        last = s
        save()
        // radio-browser.info counts what is listened to; it asks its clients to tell it.
        s.uuid?.let { uuid -> radioBrowser(RadioBrowser.clickPath(uuid), retry = false) {} }
        render()
        updateMenu()
    }

    private fun pauseOrResume() {
        val s = station ?: return
        when (streamState) {
            PlayerState.PLAYING, PlayerState.BUFFERING, PlayerState.LOADING -> {
                ui.player(PlayerAction.Pause)
                streamState = PlayerState.PAUSED
                streamMessage = null
                render()
            }
            PlayerState.PAUSED -> {
                ui.player(PlayerAction.Resume)
                streamState = PlayerState.LOADING
                streamMessage = null
                render()
            }
            // Off, at its end or failed: connect again.
            else -> play(s)
        }
    }

    /** The next or previous station of the list it was chosen from, as a radio's dial turns. */
    private fun zap(direction: Int) {
        val s = station ?: return
        val list = zapList.ifEmpty { listFor(s) }
        if (list.size < 2 && list.firstOrNull()?.key == s.key) {
            ui.toast("Keine weiteren Sender in dieser Liste", SHORT_NOTE_MS)
            return
        }
        val i = list.indexOfFirst { it.key == s.key }
        val next = if (i < 0) list.first() else list[Math.floorMod(i + direction, list.size)]
        play(next)
    }

    private fun stream(e: AppEvent.Player) {
        if (e.url != streamUrl) return
        streamState = e.state
        streamTitle = e.title
        streamMessage = e.message
        if (e.state == PlayerState.ERROR && heal()) return
        render()
    }

    /**
     * A station of the list failed: radio-browser.info may know where it streams now. Asked once per play; the
     * address it gives is kept for the next time, a kept one that fails too is forgotten.
     */
    private fun heal(): Boolean {
        val s = station ?: return false
        val uuid = s.uuid ?: return false
        if (healed) return false
        healed = true
        val failed = streamUrl
        val message = streamMessage
        streamState = PlayerState.LOADING
        streamMessage = null
        radioBrowser(RadioBrowser.stationPath(uuid)) { result ->
            // The wearer chose another station or turned the radio off in the meantime.
            if (station?.key != s.key || streamUrl != failed) return@radioBrowser
            val found = result?.let { RadioBrowser.stations(it.body).firstOrNull() }?.url
            // Still waiting, not paused meanwhile: it plays from there at once, else next time.
            val waiting = streamState == PlayerState.LOADING
            if (found != null && found != failed) {
                fixed[uuid] = found
                save()
                if (waiting) {
                    streamUrl = found
                    ui.player(PlayerAction.Play(found, s.name))
                }
            } else {
                if (fixed.remove(uuid) != null) save()
                if (waiting) {
                    streamState = PlayerState.ERROR
                    streamMessage = message
                }
            }
            render()
        }
        render()
        return true
    }

    private fun stop() {
        ui.player(PlayerAction.Stop)
        streamUrl = null
        streamState = null
        streamTitle = null
        streamMessage = null
        render()
        updateMenu()
    }

    private fun toggleFavorite() {
        val s = station ?: return
        val known = favorites.any { it.key == s.key }
        favorites = if (known) favorites.filter { it.key != s.key } else (favorites + s).takeLast(MAX_FAVORITES)
        save()
        render()
        updateMenu()
        ui.toast(if (known) "${s.name}: kein Favorit mehr" else "${s.name}: bei den Favoriten", NOTE_MS)
    }

    private fun askStation() = ui.askText(ASK_STATION, "Welcher Sender?", radioSearches.map { it.take(MAX_SUGGESTION) })

    private fun answer(tag: String, text: String?) {
        val q = text?.trim()?.replace(SPACES, " ").orEmpty()
        if (q.isEmpty()) return
        when (tag) {
            ASK_STATION -> {
                radioSearches = remember(q, radioSearches)
                save()
                search(q)
            }
            ASK_PLAYER -> {
                val p = player()
                val target = p?.takeIf { it.app != SPOTIFY && it.canSearch }
                val query = q.take(MediaAction.MAX_QUERY)
                playerSearches = remember(q, playerSearches)
                save()
                ui.toast("${target?.appName ?: "Spotify"} sucht „$query“", NOTE_MS)
                ui.media(MediaAction.Search(query, target?.app ?: SPOTIFY))
            }
        }
    }

    private fun remember(q: String, list: List<String>) = (listOf(q) + list.filter { !it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES)

    private fun search(q: String) {
        query = q.take(MAX_QUERY)
        results = emptyList()
        searchError = null
        searching = true
        val no = ++searchNo
        radioBrowser(RadioBrowser.searchPath(query)) { result ->
            if (no != searchNo) return@radioBrowser
            searching = false
            if (result == null) {
                searchError = "Die Senderliste ist gerade nicht erreichbar. Ist die Uhr im Netz?"
            } else {
                // Swiss stations first, then as listened to most.
                results = RadioBrowser.stations(result.body).sortedBy { it.country != HOME }
            }
            render()
        }
        render()
        if (top() != P_TREFFER) show(P_TREFFER)
    }

    /**
     * A request to radio-browser.info: to the server that answered last, and while [retry] to the next ones
     * when it does not answer. [done] gets the answer, or null when none came.
     */
    private fun radioBrowser(path: String, retry: Boolean = true, attempt: Int = 0, done: (HttpResult?) -> Unit) {
        val servers = RadioBrowser.SERVERS
        val at = (server + attempt) % servers.size
        ui.fetch(HttpRequest(servers[at] + path, headers = mapOf("User-Agent" to RadioBrowser.USER_AGENT))) { result ->
            when {
                result.ok -> {
                    server = at
                    done(result)
                }
                retry && attempt + 1 < servers.size -> radioBrowser(path, retry, attempt + 1, done)
                else -> {
                    ui.log("radio-browser.info: ${result.error ?: "HTTP ${result.status}"}")
                    done(null)
                }
            }
        }
    }

    // --- The app menu -----------------------------------------------------------------------------------

    private fun updateMenu() {
        val items = when (top()) {
            P_PLAYER -> listOf(LOUDER, QUIETER, MenuItem(M_SEARCH_PLAYER, "Suchen und spielen"), MenuItem(M_OPEN, "Spotify auf der Uhr öffnen"), MenuItem(M_RADIO, "Radiosender"))
            P_SENDER -> listOfNotNull(
                LOUDER,
                QUIETER,
                MenuItem(M_FAVORITE, if (favorites.any { it.key == station?.key }) "Favorit entfernen" else "Als Favorit merken"),
                MenuItem(M_OFF, "Radio aus").takeIf { streamState != null },
                MenuItem(M_LIST, "Senderliste"),
            )
            else -> listOfNotNull(
                MenuItem(M_SEARCH_STATION, "Sender suchen"),
                LOUDER,
                QUIETER,
                MenuItem(M_OFF, "Radio aus").takeIf { streamState != null },
            )
        }
        if (items == shownMenu) return
        shownMenu = items
        ui.menu(items)
    }

    private fun menu(item: String) {
        when (item) {
            M_LOUDER, M_QUIETER -> {
                changeVolume(if (item == M_LOUDER) 1 else -1)
                if (!volumeMode) ui.toast(volumeText(), SHORT_NOTE_MS)
            }
            M_SEARCH_PLAYER -> searchPlayer()
            M_OPEN -> {
                ui.toast("Spotify öffnet sich auf der Uhr", NOTE_MS)
                ui.media(MediaAction.Open(SPOTIFY))
            }
            M_RADIO, M_LIST -> replace(P_RADIO)
            M_FAVORITE -> toggleFavorite()
            M_OFF -> stop()
            M_SEARCH_STATION -> askStation()
        }
    }

    // --- The store --------------------------------------------------------------------------------------

    private fun load() {
        val store = ui.storage
        favorites = (store.get(K_FAVORITES) as? JsonArray).orEmpty().mapNotNull { Station.fromJson(it) }.take(MAX_FAVORITES)
        last = Station.fromJson(store.get(K_LAST))
        radioSearches = strings(store.get(K_RADIO_SEARCHES))
        playerSearches = strings(store.get(K_PLAYER_SEARCHES))
        fixed.clear()
        (store.get(K_FIXED) as? JsonObject)?.forEach { (uuid, url) ->
            (url as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { Station.isStream(it) }?.let { fixed[uuid] = it }
        }
    }

    private fun strings(e: JsonElement?): List<String> =
        (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.take(MAX_SEARCHES)

    private fun save() {
        val store = ui.storage
        store.set(K_FAVORITES, JsonArray(favorites.map { it.toJson() }))
        store.set(K_LAST, last?.toJson() ?: JsonNull)
        store.set(K_RADIO_SEARCHES, JsonArray(radioSearches.map { JsonPrimitive(it) }))
        store.set(K_PLAYER_SEARCHES, JsonArray(playerSearches.map { JsonPrimitive(it) }))
        store.set(K_FIXED, JsonObject(fixed.mapValues { JsonPrimitive(it.value) }))
    }

    companion object {
        const val ID = "ch.madtreasures.musik"
        const val VERSION = "1.0.0"

        /** Spotify on the watch (Wear OS), as Android knows it. */
        const val SPOTIFY = "com.spotify.music"

        /** Stations of this country come first among the search results. */
        const val HOME = "CH"

        const val P_START = "start"
        const val P_PLAYER = "player"
        const val P_RADIO = "radio"
        const val P_SENDER = "sender"
        const val P_TREFFER = "treffer"

        const val ST_NOTE = "st_hinweis"
        const val ST_PLAYER = "st_player"
        const val ST_STATION = "st_sender"
        const val ST_LAST = "st_zuletzt"
        const val ST_RADIO = "st_radio"
        const val ST_VOLUME = "st_laut"
        const val ST_OUTPUT = "st_ton"

        const val PL_COVER = "pl_cover"
        const val PL_TITLE = "pl_titel"
        const val PL_LINE = "pl_zeile"
        const val PL_TIME = "pl_zeit"
        const val PL_STATE = "pl_status"
        const val PL_VOLUME = "pl_laut"

        const val RA_SEARCH = "ra_suche"
        const val RA_FAVORITES = "ra_favoriten"
        const val RA_FAVORITE = "ra_fav_"
        const val RA_GROUP = "ra_gruppe_"
        const val RA_STATION = "ra_ch_"
        const val RA_SOURCE = "ra_quelle"

        const val SE_PICTURE = "se_bild"
        const val SE_NAME = "se_name"
        const val SE_SONG = "se_titel"
        const val SE_STATE = "se_status"
        const val SE_VOLUME = "se_laut"

        const val TR_INFO = "tr_info"
        const val TR_AGAIN = "tr_nochmal"
        const val TR_NEW = "tr_neu"
        const val TR_STATION = "tr_sender_"

        const val M_LOUDER = "m_lauter"
        const val M_QUIETER = "m_leiser"
        const val M_SEARCH_PLAYER = "m_suchen"
        const val M_OPEN = "m_spotify"
        const val M_RADIO = "m_radio"
        const val M_FAVORITE = "m_favorit"
        const val M_OFF = "m_aus"
        const val M_LIST = "m_liste"
        const val M_SEARCH_STATION = "m_sender_suchen"

        const val ASK_STATION = "sender"
        const val ASK_PLAYER = "spotify"

        const val TICK = "zeit"
        const val VOLUME_TIMER = "lautstaerke"
        const val TICK_MS = 1_000L
        const val VOLUME_MS = 5_000L

        /** Cover and radio picture, square. */
        const val COVER = 96
        const val NOTE_PICTURE = "asset:note.png"
        const val RADIO_PICTURE = "asset:radio.png"

        const val VOLUME_HINT = "Wischen: lauter oder leiser · Tippen: fertig"

        private const val HEADING = 28
        private const val BODY = 22
        private const val NOTE_MS = 2_500
        private const val SHORT_NOTE_MS = 1_200
        private const val MAX_FAVORITES = 30
        private const val MAX_SEARCHES = 5
        private const val MAX_SUGGESTION = 40
        private const val MAX_QUERY = 100

        private const val K_FAVORITES = "favoriten"
        private const val K_LAST = "zuletzt"
        private const val K_RADIO_SEARCHES = "sendersuchen"
        private const val K_PLAYER_SEARCHES = "spotifysuchen"
        private const val K_FIXED = "korrekturen"

        private val LOUDER = MenuItem(M_LOUDER, "Lauter")
        private val QUIETER = MenuItem(M_QUIETER, "Leiser")
        private val SPACES = Regex("\\s+")

        /** 3:05, or 1:02:03 from an hour on. */
        fun time(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
        }
    }
}

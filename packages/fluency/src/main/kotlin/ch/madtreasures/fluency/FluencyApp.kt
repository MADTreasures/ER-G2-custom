package ch.madtreasures.fluency

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Sensor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale

/**
 * Fluency on the glasses. The Fluency app on the phone recognises what people say – or what a video,
 * a radio or an announcement says – and translates it, all offline; this app shows the translation as a
 * rolling caption on the glasses and steers Fluency: start, pause, languages, and where the sound comes
 * from, the watch's microphone (streamed to the phone) or the phone's own.
 *
 * The live page takes gestures: tap = pause or go on, swipe = text large or normal, long press (watch:
 * swipe left) = original on or off, double tap = back. A Bluetooth keyboard (interface version 3) works
 * it too: Space, Enter or K = pause or go on, + and − or ↑ and ↓ = text size, O = original, T = swap the
 * languages, V = history; Escape is back. The app menu holds the same, so watch and temples always do.
 *
 * The watch app cannot reach the phone yet (interface version 3, PLATTFORM.md): [linkFor] gives
 * [NoPhoneLink] and the start page says so. "Probelauf" plays a made-up conversation through the same
 * captions ([Demo]), to try them on the glasses already.
 */
class FluencyApp(
    private val clock: () -> Long = System::currentTimeMillis,
    /** The connection to Fluency for a session; there is none in interface version 3. */
    private val linkFor: (AppContext) -> PhoneLink = { NoPhoneLink },
) : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.fluency",
        name = "Fluency",
        version = "1.0.1",
        description = "Live-Übersetzung von Fluency (Handy) als Untertitel auf der Brille, Ton vom Uhr- oder Handy-Mikrofon.",
    )

    // --- Settings, kept in the app's store ---------------------------------------------------------
    private var source = Source.WATCH
    private var from = Languages.AUTO
    private var to = DEFAULT_TO
    private var showOriginal = true
    private var big = false
    private var partials = true
    private var speak = false

    // --- The phone ---------------------------------------------------------------------------------
    private enum class Link { MISSING, CONNECTING, HELLO, READY, LOST, MISMATCH }

    private var link: PhoneLink = NoPhoneLink
    private var linkState = Link.MISSING
    private var fluencyVersion = ""
    private var lostReason: String? = null
    private var shownPhone = ""

    // --- The live page -----------------------------------------------------------------------------
    private enum class Mode { NONE, LIVE, DEMO }

    private var mode = Mode.NONE

    /** The wearer wants Fluency to listen (not paused). */
    private var wanted = false

    /** Fluency says it listens. */
    private var running = false

    /** Hidden while listening: Fluency was stopped and listens again once the app is visible. */
    private var suspended = false
    private var visible = false

    /** Fluency's note on what it does (loading models …) or why it stopped. */
    private var note: String? = null

    /** Number of the last start, config or stop sent ([Protocol.start]). */
    private var requestNo = 0
    private val captions = Captions()
    private var demo: Demo? = null
    private var demoPaused = false

    /** The history page lies over the live page; keys go to the host there. */
    private var historyOverLive = false

    /** What the live page shows, so that only changes are sent. */
    private var shownName = ""
    private var shownLayout = ""
    private var shownNow = ""
    private var shownOriginal = ""
    private var lastRender = Long.MIN_VALUE / 2
    private var renderPending = false

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> start(ui)
            AppEvent.Visible -> visible(ui)
            AppEvent.Hidden -> hidden()
            AppEvent.Stop -> stop()
            is AppEvent.Click -> click(event.page, event.block, ui)
            is AppEvent.Toggle -> toggle(event.block, event.on, ui)
            is AppEvent.Back -> back(event.page, ui)
            is AppEvent.Gesture -> gesture(event.gesture, ui)
            is AppEvent.Key -> key(event, ui)
            is AppEvent.Menu -> menu(event.item, ui)
            is AppEvent.Timer -> timer(event.tag, ui)
            else -> Unit
        }
    }

    /**
     * What the connection to Fluency reports. With interface version 3 nothing does; a later version of
     * this package passes the watch app's phone events on to here (PLATTFORM.md).
     */
    fun onLink(event: LinkEvent, ui: AppContext) {
        when (event) {
            LinkEvent.Connected -> {
                linkState = Link.HELLO
                lostReason = null
                link.send(Protocol.hello(manifest.version))
                statusChanged(ui)
            }
            is LinkEvent.Message -> received(event.text, ui)
            is LinkEvent.Disconnected -> {
                val wasReady = linkState == Link.READY
                linkState = Link.LOST
                lostReason = event.reason?.takeIf { it.isNotBlank() }
                running = false
                if (mode == Mode.LIVE && wasReady) ui.toast("Verbindung zum Handy unterbrochen", LONG_NOTE_MS)
                statusChanged(ui)
            }
        }
    }

    // --- Start -----------------------------------------------------------------------------------

    private fun start(ui: AppContext) {
        load(ui)
        link = linkFor(ui)
        linkState = if (link.available) Link.CONNECTING else Link.MISSING
        ui.definePages(
            listOf(startPage(), languagesPage(), fromPage(), fromMorePage(), toPage(), toMorePage(), displayPage(), historyPage(), livePage()),
        )
        ui.menu(menuItems())
        ui.show(START)
        if (link.available) link.open()
    }

    private fun startPage() = Page(
        START, "Fluency",
        buildList {
            add(Block.Value(PHONE, "Handy", phoneText().also { shownPhone = it }))
            if (!link.available) add(Block.Text(MISSING_NOTE, MISSING_TEXT))
            add(Block.Button(GO, "Übersetzen"))
            add(Block.Button(DEMO_BUTTON, "Probelauf"))
            add(Block.Button(SOURCE, sourceText()))
            add(Block.Button(LANGUAGES, languagesText(), target = LANGUAGE_PAGE))
            add(Block.Button(DISPLAY, "Anzeige", target = DISPLAY_PAGE))
            add(Block.Button(HISTORY_BUTTON, "Verlauf", target = HISTORY_PAGE))
        },
    )

    private fun phoneText() = when (linkState) {
        Link.MISSING -> "fehlt noch"
        Link.CONNECTING, Link.HELLO -> "verbindet …"
        Link.READY -> if (fluencyVersion.isBlank()) "verbunden" else "Fluency $fluencyVersion"
        Link.LOST -> "getrennt"
        Link.MISMATCH -> "passt nicht"
    }

    private fun sourceText() = "Ton: ${source.label}"

    private fun languagesText() = "${Languages.name(from)} → ${Languages.name(to)}"

    private fun click(page: String, block: String, ui: AppContext) {
        when {
            block == GO -> goLive(ui)
            block == DEMO_BUTTON -> startDemo(ui)
            block == SOURCE -> switchSource(ui)
            block == SWAP -> swap(ui)
            block == CLEAR_HISTORY -> {
                captions.clearHistory()
                ui.definePages(listOf(historyPage()))
                ui.toast("Verlauf gelöscht")
            }
            block.startsWith(FROM_PREFIX) && block != FROM_MORE -> pickFrom(block.removePrefix(FROM_PREFIX), page, ui)
            block.startsWith(TO_PREFIX) && block != TO_MORE -> pickTo(block.removePrefix(TO_PREFIX), page, ui)
        }
    }

    private fun switchSource(ui: AppContext) {
        source = other(source)
        save(ui)
        ui.patch(START) { text(SOURCE, sourceText()) }
        ui.menu(menuItems())
        ui.toast(
            if (source == Source.WATCH) "Fluency hört über die Uhr" else "Fluency hört über das Handy",
            SHORT_NOTE_MS,
        )
    }

    private fun other(s: Source) = if (s == Source.WATCH) Source.PHONE else Source.WATCH

    // --- Languages -------------------------------------------------------------------------------

    private fun languagesPage() = Page(
        LANGUAGE_PAGE, "Sprachen",
        listOf(
            Block.Button(FROM_BUTTON, "Von: ${Languages.name(from)}", target = FROM_PAGE),
            Block.Button(TO_BUTTON, "Nach: ${Languages.name(to)}", target = TO_PAGE),
            Block.Button(SWAP, "Tauschen"),
            Block.Text(LANGUAGE_NOTE, "„Automatisch“: Fluency erkennt die gesprochene Sprache selbst."),
        ),
    )

    private fun fromPage() = Page(
        FROM_PAGE, "Von",
        buildList {
            add(languageButton(FROM_PREFIX, Languages.AUTO, from))
            Languages.core.forEach { add(languageButton(FROM_PREFIX, it.code, from)) }
            add(Block.Button(FROM_MORE, moreText(from), target = FROM_MORE_PAGE))
        },
    )

    private fun fromMorePage() = Page(FROM_MORE_PAGE, "Von", Languages.more.map { languageButton(FROM_PREFIX, it.code, from) })

    private fun toPage() = Page(
        TO_PAGE, "Nach",
        Languages.core.map { languageButton(TO_PREFIX, it.code, to) } + Block.Button(TO_MORE, moreText(to), target = TO_MORE_PAGE),
    )

    private fun toMorePage() = Page(TO_MORE_PAGE, "Nach", Languages.more.map { languageButton(TO_PREFIX, it.code, to) })

    private fun languageButton(prefix: String, code: String, chosen: String) =
        Block.Button("$prefix$code", Languages.name(code) + if (code == chosen) "  ✓" else "")

    private fun moreText(chosen: String) =
        if (Languages.more.any { it.code == chosen }) "Weitere: ${Languages.name(chosen)}  ✓" else "Weitere Sprachen"

    private fun pickFrom(code: String, page: String, ui: AppContext) {
        if (code != Languages.AUTO && Languages.of(code) == null) return
        from = code
        languagesChanged(ui)
        backToLanguages(page, FROM_MORE_PAGE, FROM_PAGE, ui)
    }

    private fun pickTo(code: String, page: String, ui: AppContext) {
        if (Languages.of(code) == null) return
        to = code
        languagesChanged(ui)
        backToLanguages(page, TO_MORE_PAGE, TO_PAGE, ui)
    }

    /** From a list back to the languages page; from the long list by way of the short one, so the history stays as it was. */
    private fun backToLanguages(page: String, more: String, short: String, ui: AppContext) {
        if (page == more) ui.replace(short)
        ui.replace(LANGUAGE_PAGE)
    }

    private fun swap(ui: AppContext) {
        if (from == Languages.AUTO) {
            ui.toast("Bei „Automatisch“ gibt es nichts zu tauschen", LONG_NOTE_MS)
            return
        }
        val spoken = from
        from = to
        to = spoken
        languagesChanged(ui)
        ui.toast(languagesText(), LONG_NOTE_MS)
    }

    private fun languagesChanged(ui: AppContext) {
        save(ui)
        ui.definePages(listOf(languagesPage(), fromPage(), fromMorePage(), toPage(), toMorePage()))
        ui.patch(START) { text(LANGUAGES, languagesText()) }
        ui.menu(menuItems())
        reconfigure()
    }

    /** Settings changed while Fluency listens: it takes them over at once. */
    private fun reconfigure() {
        if (mode == Mode.LIVE && wanted && !suspended && linkState == Link.READY) link.send(Protocol.config(request(), ++requestNo))
    }

    private fun request() = Protocol.Request(source, from, to, speak, partials)

    // --- Display ---------------------------------------------------------------------------------

    private fun displayPage() = Page(
        DISPLAY_PAGE, "Anzeige",
        listOf(
            Block.Toggle(ORIGINAL_TOGGLE, "Original zeigen", showOriginal),
            Block.Toggle(BIG_TOGGLE, "Große Schrift", big),
            Block.Toggle(PARTIALS_TOGGLE, "Schon beim Sprechen zeigen", partials),
            Block.Toggle(SPEAK_TOGGLE, "Am Handy vorlesen", speak),
        ),
    )

    private fun toggle(block: String, on: Boolean, ui: AppContext) {
        when (block) {
            ORIGINAL_TOGGLE -> setOriginal(on, ui, note = false)
            BIG_TOGGLE -> setBig(on, ui, note = false)
            PARTIALS_TOGGLE -> {
                partials = on
                save(ui)
                reconfigure()
                if (mode != Mode.NONE) renderLive(ui)
            }
            SPEAK_TOGGLE -> {
                speak = on
                save(ui)
                reconfigure()
            }
        }
    }

    /** Original line on or off; [note]: say so on the live page (gesture, key, menu). */
    private fun setOriginal(on: Boolean, ui: AppContext, note: Boolean) {
        if (showOriginal == on) return
        showOriginal = on
        displayChanged(ui)
        if (note) ui.toast(if (on) "Original an" else "Original aus", SHORT_NOTE_MS)
    }

    private fun setBig(on: Boolean, ui: AppContext, note: Boolean) {
        if (big == on) return
        big = on
        displayChanged(ui)
        if (note) ui.toast(if (on) "Schrift groß" else "Schrift normal", SHORT_NOTE_MS)
    }

    private fun displayChanged(ui: AppContext) {
        save(ui)
        ui.patch(DISPLAY_PAGE) {
            on(ORIGINAL_TOGGLE, showOriginal)
            on(BIG_TOGGLE, big)
        }
        if (mode != Mode.NONE) {
            renderLive(ui)
            ui.menu(menuItems())
        }
    }

    // --- History ---------------------------------------------------------------------------------

    private fun historyPage(): Page {
        val items = captions.history
        val blocks = ArrayList<Block>()
        if (items.isEmpty()) blocks += Block.Text(HISTORY_EMPTY, "Noch nichts übersetzt.")
        items.forEachIndexed { i, u ->
            if (i > 0) blocks += Block.Divider("$HISTORY_LINE$i")
            blocks += Block.Text("$HISTORY_TEXT$i", u.text.trim())
            if (u.source.isNotBlank()) blocks += Block.Text("$HISTORY_SOURCE$i", quoted(u))
        }
        if (items.isNotEmpty()) blocks += Block.Button(CLEAR_HISTORY, "Verlauf löschen")
        return Page(HISTORY_PAGE, "Verlauf", blocks)
    }

    /** The original under its translation: „Where is the station?“, with "EN" in front when the language is known. */
    private fun quoted(u: Utterance): String {
        val from = u.from?.takeIf { it.isNotBlank() && it != Languages.AUTO }?.uppercase(Locale.ROOT)
        val text = "„${u.source.trim()}“"
        return if (from == null) text else "$from $text"
    }

    private fun openHistory(ui: AppContext) {
        ui.definePages(listOf(historyPage()))
        ui.show(HISTORY_PAGE)
        if (mode != Mode.NONE && !historyOverLive) {
            historyOverLive = true
            // On the history page the arrows and Enter work the page as usual.
            ui.unsubscribe(Sensor.KEYS)
        }
    }

    private fun back(page: String, ui: AppContext) {
        when (page) {
            LIVE -> leaveLive(ui)
            HISTORY_PAGE -> if (historyOverLive) {
                historyOverLive = false
                ui.subscribe(Sensor.KEYS)
            }
        }
    }

    // --- Live ------------------------------------------------------------------------------------

    private fun goLive(ui: AppContext) {
        if (!link.available) {
            ui.toast(NO_LINK_NOTE, LONG_NOTE_MS)
            return
        }
        mode = Mode.LIVE
        wanted = true
        suspended = false
        running = false
        note = null
        captions.startSession()
        enterLive(ui)
        listen(ui)
    }

    private fun enterLive(ui: AppContext) {
        ui.definePages(listOf(livePage()))
        ui.show(LIVE)
        ui.subscribe(Sensor.KEYS)
        ui.menu(menuItems())
        lastRender = clock()
    }

    /** Asks Fluency to listen, once connected; the watch's microphone goes along if it is the source. */
    private fun listen(ui: AppContext) {
        when (linkState) {
            Link.READY -> {
                link.send(Protocol.start(request(), ++requestNo))
                if (source == Source.WATCH) link.microphone(true)
            }
            Link.LOST -> {
                linkState = Link.CONNECTING
                link.open()
            }
            // The welcome starts it, or there is nothing to start.
            Link.CONNECTING, Link.HELLO, Link.MISSING, Link.MISMATCH -> Unit
        }
        statusChanged(ui)
        ui.menu(menuItems())
    }

    /** Fluency stops listening and the watch's microphone stops. */
    private fun quiet() {
        if (linkState == Link.READY) {
            link.send(Protocol.stop(++requestNo))
            link.microphone(false)
        }
        running = false
    }

    private fun playPause(ui: AppContext) {
        when (mode) {
            Mode.DEMO -> demoPlayPause(ui)
            Mode.LIVE -> when {
                linkState == Link.MISMATCH -> ui.toast("Fluency auf dem Handy passt nicht zu dieser App", LONG_NOTE_MS)
                wanted && linkState != Link.LOST -> {
                    wanted = false
                    note = null
                    quiet()
                    renderLive(ui)
                    ui.menu(menuItems())
                }
                else -> {
                    wanted = true
                    listen(ui)
                }
            }
            Mode.NONE -> Unit
        }
    }

    private fun leaveLive(ui: AppContext) {
        when (mode) {
            Mode.LIVE -> quiet()
            Mode.DEMO -> ui.cancelTimer(DEMO_TIMER)
            Mode.NONE -> return
        }
        mode = Mode.NONE
        wanted = false
        suspended = false
        demo = null
        demoPaused = false
        if (renderPending) {
            renderPending = false
            ui.cancelTimer(RENDER_TIMER)
        }
        ui.unsubscribe(Sensor.KEYS)
        ui.menu(menuItems())
    }

    private fun visible(ui: AppContext) {
        visible = true
        if (mode == Mode.LIVE && suspended) {
            suspended = false
            listen(ui)
        }
    }

    /** Hidden, nobody reads the captions: Fluency and the microphone stop until the app is visible again (the demo's timers rest by themselves). */
    private fun hidden() {
        visible = false
        if (mode == Mode.LIVE && wanted) {
            quiet()
            suspended = true
        }
    }

    private fun stop() {
        if (mode == Mode.LIVE) quiet()
        link.close()
    }

    private fun received(text: String, ui: AppContext) {
        when (val m = Protocol.parse(text)) {
            null -> ui.log("Fluency: unbekannte Nachricht ${text.take(80)}")
            is Protocol.Incoming.Welcome -> welcome(m, ui)
            is Protocol.Incoming.State -> {
                val noted = m.note?.takeIf { it.isNotBlank() }
                // An answer to an older request: a newer one is on its way, and its answer counts.
                val stale = m.re != null && m.re < requestNo
                if (noted == note && (stale || m.running == running)) return
                note = noted
                if (!stale) {
                    running = m.running
                    // Fluency stopped by itself (an error, or someone stopped it on the phone): paused here too.
                    if (!m.running && wanted && mode == Mode.LIVE && !suspended) {
                        wanted = false
                        link.microphone(false)
                        ui.menu(menuItems())
                    }
                }
                statusChanged(ui)
            }
            is Protocol.Incoming.Utterance -> utterance(m, ui)
            is Protocol.Incoming.Error -> {
                ui.log("Fluency: ${m.code}: ${m.message}")
                ui.toast(m.message.take(60), LONG_NOTE_MS)
            }
        }
    }

    private fun welcome(m: Protocol.Incoming.Welcome, ui: AppContext) {
        if (m.proto != Protocol.NAME) {
            linkState = Link.MISMATCH
            ui.log("Fluency spricht ${m.proto}, diese App ${Protocol.NAME}")
            ui.toast("Fluency auf dem Handy passt nicht zu dieser App", LONG_NOTE_MS)
            statusChanged(ui)
            return
        }
        linkState = Link.READY
        fluencyVersion = m.app
        if (mode == Mode.LIVE && wanted && !suspended && visible) listen(ui) else statusChanged(ui)
    }

    private fun utterance(u: Utterance, ui: AppContext) {
        if (!captions.update(u)) return
        // The history page stays current, also while it is open.
        if (u.final) ui.definePages(listOf(historyPage()))
        if (mode == Mode.NONE) return
        if (u.final || u.gone) renderLive(ui) else if (partials) scheduleRender(ui)
    }

    // --- Probelauf -------------------------------------------------------------------------------

    private fun startDemo(ui: AppContext) {
        mode = Mode.DEMO
        demo = Demo()
        demoPaused = false
        captions.startSession()
        enterLive(ui)
        ui.toast("Probelauf ohne Handy · Tippen = Pause", LONG_NOTE_MS)
        ui.timer(DEMO_TIMER, Demo.FIRST_MS)
    }

    private fun demoStep(ui: AppContext) {
        val d = demo ?: return
        val step = d.next()
        if (step == null) {
            renderLive(ui)
            ui.menu(menuItems())
            ui.toast("Ende des Probelaufs – tippen: nochmal", LONG_NOTE_MS)
            return
        }
        utterance(step.utterance, ui)
        ui.timer(DEMO_TIMER, step.delayMs)
    }

    private fun demoPlayPause(ui: AppContext) {
        val d = demo ?: return
        when {
            d.finished -> {
                demo = Demo()
                demoPaused = false
                captions.startSession()
                ui.timer(DEMO_TIMER, Demo.FIRST_MS)
            }
            demoPaused -> {
                demoPaused = false
                ui.timer(DEMO_TIMER, Demo.PARTIAL_MS)
            }
            else -> {
                demoPaused = true
                ui.cancelTimer(DEMO_TIMER)
            }
        }
        renderLive(ui)
        ui.menu(menuItems())
    }

    // --- The live page ---------------------------------------------------------------------------

    /** Captions in the header-less part of the app area; the page name in the header says what is going on. */
    private fun livePage(): Page {
        val (now, original) = liveTexts()
        shownName = liveName()
        shownLayout = layout()
        shownNow = now
        shownOriginal = original
        val blocks = buildList {
            add(Block.Heading(NOW, now, size = if (big) HeadingSize.GROSS else HeadingSize.NORMAL))
            if (showOriginal) add(Block.Text(ORIGINAL, original))
        }
        return Page(LIVE, shownName, blocks, input = InputMode.GESTURES)
    }

    private fun layout() = "$showOriginal/$big"

    private fun liveName(): String = when (mode) {
        Mode.DEMO -> when {
            demo?.finished == true -> "Ende"
            demoPaused -> "Pause"
            else -> "Probelauf"
        }
        else -> when (linkState) {
            Link.MISSING -> "Kein Handy"
            Link.MISMATCH -> "Passt nicht"
            Link.LOST -> "Getrennt"
            Link.CONNECTING, Link.HELLO -> "Verbindet …"
            Link.READY -> when {
                !wanted -> "Pause"
                running -> "Live"
                else -> "Startet …"
            }
        }
    }

    /** Text for an empty caption: what is going on, or what the wearer can do. */
    private fun hint(): String = when (mode) {
        Mode.DEMO -> when {
            demo?.finished == true -> "Ende des Probelaufs – tippen: nochmal"
            demoPaused -> "Pause – tippen: weiter"
            else -> "Probelauf: gleich spricht jemand …"
        }
        Mode.LIVE -> when (linkState) {
            Link.MISSING -> MISSING_TEXT
            Link.MISMATCH -> "Fluency auf dem Handy passt nicht zu dieser App. Beide aktualisieren."
            Link.LOST -> (lostReason ?: "Keine Verbindung zu Fluency auf dem Handy.") + " Tippen: neu versuchen"
            Link.CONNECTING, Link.HELLO -> "Verbindet mit Fluency auf dem Handy …"
            // Fluency's own note first: what it does (loading models) or why it stopped.
            Link.READY -> note ?: when {
                !wanted -> "Pause – tippen: weiter"
                running -> "Fluency hört zu (${source.label}) …"
                else -> "Fluency startet …"
            }
        }
        Mode.NONE -> ""
    }

    /** The caption (heading) and the original line, fitted into the lines the layout gives them. */
    private fun liveTexts(): Pair<String, String> {
        val size = if (big) HEADING_BIG else HEADING
        val caption = TextBudget.tail(captions.translation(partials), size, bold = true, maxLines = captionLines())
        if (!showOriginal) return caption.ifEmpty { TextBudget.tail(hint(), size, bold = true, maxLines = captionLines()) } to ""
        val original = captions.original(partials).ifEmpty { if (caption.isEmpty()) hint() else "" }
        return caption to TextBudget.tail(original, BODY, bold = false, maxLines = originalLines())
    }

    /**
     * Lines for the caption: the app area is 260 px high, a caption line 35 px (large: 45), an original
     * line 27, and at most 5–6 lines of text on the page (02 §9).
     */
    private fun captionLines() = when {
        big && showOriginal -> 4
        big -> 5
        showOriginal -> 4
        else -> 5
    }

    private fun originalLines() = if (big) 1 else 2

    /**
     * Shows the current state on the live page at once: a new name or layout defines the page anew,
     * otherwise changed texts go as a patch. Partial results come by way of [scheduleRender].
     */
    private fun renderLive(ui: AppContext) {
        if (renderPending) {
            renderPending = false
            ui.cancelTimer(RENDER_TIMER)
        }
        if (liveName() != shownName || layout() != shownLayout) {
            lastRender = clock()
            ui.definePages(listOf(livePage()))
            return
        }
        val (now, original) = liveTexts()
        if (now == shownNow && original == shownOriginal) return
        lastRender = clock()
        ui.patch(LIVE) {
            if (now != shownNow) text(NOW, now)
            if (original != shownOriginal) text(ORIGINAL, original)
        }
        shownNow = now
        shownOriginal = original
    }

    /** Partial results come several times a second; the page changes at most every [RENDER_MS] (02 §9: calm). */
    private fun scheduleRender(ui: AppContext) {
        if (renderPending) return
        val wait = lastRender + RENDER_MS - clock()
        if (wait <= 0) {
            renderLive(ui)
        } else {
            renderPending = true
            ui.timer(RENDER_TIMER, wait)
        }
    }

    /** The phone's state changed: the start page and the live page say so. */
    private fun statusChanged(ui: AppContext) {
        val phone = phoneText()
        if (phone != shownPhone) {
            shownPhone = phone
            ui.patch(START) { value(PHONE, phone) }
        }
        if (mode != Mode.NONE) renderLive(ui)
    }

    private fun timer(tag: String, ui: AppContext) {
        when (tag) {
            RENDER_TIMER -> if (renderPending) renderLive(ui)
            DEMO_TIMER -> demoStep(ui)
        }
    }

    // --- Input -----------------------------------------------------------------------------------

    private fun gesture(kind: GestureKind, ui: AppContext) {
        if (mode == Mode.NONE || historyOverLive) return
        when (kind) {
            GestureKind.CLICK -> playPause(ui)
            GestureKind.SCROLL_DOWN -> setBig(true, ui, note = true)
            GestureKind.SCROLL_UP -> setBig(false, ui, note = true)
            GestureKind.LONG_PRESS, GestureKind.SWIPE_LEFT -> setOriginal(!showOriginal, ui, note = true)
            else -> Unit
        }
    }

    /** A key of a Bluetooth keyboard on the live page; none of them repeats while held. */
    private fun key(k: AppEvent.Key, ui: AppContext) {
        if (mode == Mode.NONE || historyOverLive || k.ctrl || k.alt || k.meta || k.repeat) return
        when (k.key) {
            Keys.SPACE, Keys.ENTER, "k", "K", Keys.MEDIA_PLAY_PAUSE -> playPause(ui)
            Keys.MEDIA_PLAY -> if (paused()) playPause(ui)
            Keys.MEDIA_PAUSE, Keys.MEDIA_STOP -> if (!paused()) playPause(ui)
            "+", "=", Keys.ARROW_UP -> setBig(true, ui, note = true)
            "-", Keys.ARROW_DOWN -> setBig(false, ui, note = true)
            "o", "O" -> setOriginal(!showOriginal, ui, note = true)
            "t", "T" -> if (mode == Mode.LIVE) swap(ui)
            "v", "V" -> openHistory(ui)
        }
    }

    private fun paused(): Boolean = when (mode) {
        Mode.DEMO -> demoPaused || demo?.finished == true
        Mode.LIVE -> !wanted
        Mode.NONE -> false
    }

    /** The app's own entries in the app menu: on the live page what tap, swipe and long press do. */
    private fun menuItems(): List<MenuItem> = if (mode == Mode.NONE) {
        listOf(MenuItem(MENU_DEMO, "Probelauf"), MenuItem(MENU_SOURCE, "Ton: ${other(source).label}"))
    } else {
        buildList {
            add(MenuItem(MENU_PAUSE, if (mode == Mode.DEMO && demo?.finished == true) "Nochmal" else if (paused()) "Weiter" else "Pause"))
            add(MenuItem(MENU_ORIGINAL, if (showOriginal) "Original aus" else "Original an"))
            add(MenuItem(MENU_SIZE, if (big) "Schrift normal" else "Schrift groß"))
            if (mode == Mode.LIVE && from != Languages.AUTO) add(MenuItem(MENU_SWAP, "Sprachen tauschen"))
            add(MenuItem(MENU_HISTORY, "Verlauf"))
        }
    }

    private fun menu(item: String, ui: AppContext) {
        when (item) {
            MENU_DEMO -> if (mode == Mode.NONE) startDemo(ui)
            MENU_SOURCE -> if (mode == Mode.NONE) switchSource(ui)
            MENU_PAUSE -> playPause(ui)
            MENU_ORIGINAL -> setOriginal(!showOriginal, ui, note = true)
            MENU_SIZE -> setBig(!big, ui, note = true)
            MENU_SWAP -> swap(ui)
            MENU_HISTORY -> openHistory(ui)
        }
    }

    // --- Store -----------------------------------------------------------------------------------

    private fun load(ui: AppContext) {
        fun text(key: String) = (ui.storage.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String, default: Boolean) = (ui.storage.get(key) as? JsonPrimitive)?.booleanOrNull ?: default
        source = Source.of(text(KEY_SOURCE)) ?: Source.WATCH
        from = text(KEY_FROM)?.takeIf { it == Languages.AUTO || Languages.of(it) != null } ?: Languages.AUTO
        to = text(KEY_TO)?.takeIf { Languages.of(it) != null } ?: DEFAULT_TO
        showOriginal = flag(KEY_ORIGINAL, true)
        big = flag(KEY_BIG, false)
        partials = flag(KEY_PARTIALS, true)
        speak = flag(KEY_SPEAK, false)
    }

    private fun save(ui: AppContext) {
        ui.storage.set(KEY_SOURCE, JsonPrimitive(source.json))
        ui.storage.set(KEY_FROM, JsonPrimitive(from))
        ui.storage.set(KEY_TO, JsonPrimitive(to))
        ui.storage.set(KEY_ORIGINAL, JsonPrimitive(showOriginal))
        ui.storage.set(KEY_BIG, JsonPrimitive(big))
        ui.storage.set(KEY_PARTIALS, JsonPrimitive(partials))
        ui.storage.set(KEY_SPEAK, JsonPrimitive(speak))
    }

    companion object {
        const val START = "p_start"
        const val LIVE = "p_live"
        const val LANGUAGE_PAGE = "p_sprachen"
        const val FROM_PAGE = "p_von"
        const val FROM_MORE_PAGE = "p_von_mehr"
        const val TO_PAGE = "p_nach"
        const val TO_MORE_PAGE = "p_nach_mehr"
        const val DISPLAY_PAGE = "p_anzeige"
        const val HISTORY_PAGE = "p_verlauf"

        // Start page
        const val PHONE = "handy"
        const val MISSING_NOTE = "hinweis"
        const val GO = "uebersetzen"
        const val DEMO_BUTTON = "probelauf"
        const val SOURCE = "ton"
        const val LANGUAGES = "sprachen"
        const val DISPLAY = "anzeige"
        const val HISTORY_BUTTON = "verlauf"

        // Live page
        const val NOW = "jetzt"
        const val ORIGINAL = "original"

        // Languages: buttons "von_<code>" and "nach_<code>"
        const val FROM_BUTTON = "von"
        const val TO_BUTTON = "nach"
        const val SWAP = "tauschen"
        const val LANGUAGE_NOTE = "sprachen_hinweis"
        const val FROM_PREFIX = "von_"
        const val TO_PREFIX = "nach_"
        const val FROM_MORE = "von_mehr"
        const val TO_MORE = "nach_mehr"

        // Display
        const val ORIGINAL_TOGGLE = "t_original"
        const val BIG_TOGGLE = "t_gross"
        const val PARTIALS_TOGGLE = "t_teil"
        const val SPEAK_TOGGLE = "t_vorlesen"

        // History: per entry a line between, the translation and the original, plus the index
        const val HISTORY_EMPTY = "verlauf_leer"
        const val HISTORY_LINE = "h_linie_"
        const val HISTORY_TEXT = "h_text_"
        const val HISTORY_SOURCE = "h_original_"
        const val CLEAR_HISTORY = "verlauf_loeschen"

        const val MENU_DEMO = "probelauf"
        const val MENU_SOURCE = "ton"
        const val MENU_PAUSE = "pause"
        const val MENU_ORIGINAL = "original"
        const val MENU_SIZE = "schrift"
        const val MENU_SWAP = "tauschen"
        const val MENU_HISTORY = "verlauf"

        const val RENDER_TIMER = "zeichnen"
        const val DEMO_TIMER = "probe"

        /** At most this often the live page changes for partial results (finished sentences at once). */
        const val RENDER_MS = 400L
        const val SHORT_NOTE_MS = 1_500
        const val LONG_NOTE_MS = 3_000

        /** Text sizes of the host (02 §4.2): heading, large heading, text. */
        const val HEADING = 28
        const val HEADING_BIG = 36
        const val BODY = 22

        /** Swiss Standard German ("ss" instead of "ß"), as Fluency writes it. */
        const val DEFAULT_TO = "de-CH"

        const val MISSING_TEXT =
            "Die Uhr-App kann noch nicht mit Fluency auf dem Handy sprechen. Der Probelauf zeigt schon, wie es auf der Brille aussieht."
        const val NO_LINK_NOTE = "Uhr-App kann Fluency noch nicht erreichen"

        private const val KEY_SOURCE = "quelle"
        private const val KEY_FROM = "von"
        private const val KEY_TO = "nach"
        private const val KEY_ORIGINAL = "original"
        private const val KEY_BIG = "gross"
        private const val KEY_PARTIALS = "teil"
        private const val KEY_SPEAK = "vorlesen"
    }
}

package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The Swiss timetable on the glasses, laid out and worked like the SBB app (README.md of the package): Planen with
 * from and to, the connections as cards with the journey bar, "Ihre Reise" from stop to stop with the stops on the
 * way, the departures of a stop and the trip of a departure, the stops nearby. The data comes from
 * transport.opendata.ch ([Timetable]); no tickets, no login, no SwissPass.
 *
 * Every page is one picture ([Painter]) that the app draws itself; the temples, the watch and the keys of a
 * keyboard move the frame from line to line (swiping), a tap opens what it frames, a long press does the page's
 * second thing (swap, refresh, connection from here). Swiping past the first or last connection or departure loads
 * earlier or later ones, as scrolling does in the SBB app. A stop is typed on the watch, or with a Bluetooth keyboard
 * right on the glasses, where the stops that fit come while typing.
 *
 * While connections, a journey or departures show, they are asked for again every minute and their delays and
 * platforms follow; a chosen journey stays on its page after it left, so that it can be followed on the way.
 *
 * With a key of opentransportdata.swiss ([Formation]) a train's formation shows too, as on the SBB app's page
 * "Zugformation": in Ihre Reise and on a trip the sectors where it stands, on its own page the cars under their
 * sectors with class and what they offer.
 */
class FahrplanApp(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : G2App {
    override val manifest = AppManifest(
        id = ID,
        name = "Fahrplan",
        version = VERSION,
        input = InputMode.GESTURES,
        permissions = setOf(Permission.NETWORK, Permission.LOCATION),
        description = "Verbindungen, Abfahrten und Zugformation im ganzen Schweizer ÖV wie in der SBB-App, mit Gleis, Sektor, Verspätung und Umsteigen; eingegeben auf der Uhr oder mit einer Tastatur.",
    )

    private lateinit var ui: AppContext
    private var painter: Painter? = null

    /** The pages as the host keeps them, oldest first: show puts one on top, replace swaps it, back takes it off. */
    private val stack = ArrayList<String>()
    private var visible = false
    private var shownMenu: List<MenuItem>? = null
    private var refreshing = false
    private var locating = false
    private var listening = false

    /** A page waiting for its picture before it shows; with [pendingReplace] in place of the one on top. */
    private var pendingShow: String? = null
    private var pendingReplace = false
    private val scrolls = HashMap<String, Int>()
    private val paints = HashMap<String, Int>()

    // What to search: from null is the watch's position ("Aktueller Standort"), at null is now.
    private var from: Place? = null
    private var to: Place? = null
    private var at: Long? = null
    private var arrival = false

    // Kept in the app's store.
    private var routes: List<Route> = emptyList()
    private var places: List<Place> = emptyList()

    // The watch's position, and when it came (by the app's clock, whatever the GPS says of its time).
    private var fix: AppEvent.Location? = null
    private var fixAt = 0L
    private var waiting: Wait? = null

    // Planen.
    private var planFocus = 0

    // Typing a stop: for which field, what is typed, what fits.
    private var field: Field? = null
    private var query = ""
    private var typing = false
    private var found: List<Place> = emptyList()
    private var searchSelected = 0
    private var lookupNo = 0
    private var lookupLoading = false
    private var lookupError: String? = null

    /** The connections are searched as soon as the destination is chosen. */
    private var searchAfter = false

    // Connections.
    private var search: Search? = null
    private var searchedAt = 0L
    private var connections: List<Connection> = emptyList()
    private var connSelected = 0
    private var connLoading = false
    private var loadingEarlier = false
    private var loadingLater = false
    private var connError: String? = null
    private var connNo = 0
    private var moreNo = 0

    // Ihre Reise.
    private var chosen: Connection? = null
    private var chosenNo = 0
    private var journeySelected = 0
    private val openRides = HashSet<Int>()

    // Departures and the trip of one.
    private var boardPlace: Place? = null
    private var boardAt: Long? = null
    private var departures: List<Departure> = emptyList()
    private var boardSelected = 0
    private var boardLoading = false
    private var boardMore = false
    private var boardError: String? = null
    private var boardNo = 0
    private var trip: Departure? = null
    private var tripSelected = 0

    // Stops nearby.
    private var nearby: List<Place> = emptyList()
    private var nearSelected = 0
    private var nearLoading = false
    private var nearError: String? = null
    private var nearNo = 0

    // Train formations by train and day, the one on the formation page and its car in the frame.
    private val formations = HashMap<String, FormationEntry>()
    private var formationOf: FormationTarget? = null
    private var formationSelected = 0

    /** The key for the Train Formation Service ([loadKey]); without one no formations show. */
    private var key: String? = null

    override fun onEvent(event: AppEvent, ui: AppContext) {
        this.ui = ui
        when (event) {
            AppEvent.Start -> start()
            AppEvent.Visible -> {
                visible = true
                pageChanged()
            }
            AppEvent.Hidden -> {
                visible = false
                pageChanged()
            }
            AppEvent.Stop -> Unit
            is AppEvent.Gesture -> gesture(event.gesture)
            is AppEvent.Key -> key(event)
            is AppEvent.Navigate -> {
                if (stack.lastOrNull() != event.to) stack += event.to
                pageChanged()
            }
            is AppEvent.Back -> {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                if (top() != P_SEARCH) {
                    typing = false
                    field = null
                }
                pageChanged()
            }
            is AppEvent.Menu -> menu(event.item)
            is AppEvent.TextInput -> answer(event.tag, event.text)
            is AppEvent.Location -> onFix(event)
            is AppEvent.Timer -> when (event.tag) {
                T_REFRESH -> refresh()
                T_FIX -> noFix()
                T_TYPE -> lookupTyped()
            }
            else -> Unit
        }
    }

    private fun start() {
        val files = listOf(Font.SMALL, Font.LABEL, Font.TEXT).associate { fontPath(it) to ui.asset(fontPath(it)) }
        if (files.values.any { it == null }) ui.log("Schriften fehlen") else painter = Painter(Fonts { files[it] })
        load()
        loadKey()
        planFocus = if (to == null) 1 else 0
        ui.definePages(PAGES.map { (id, name) -> Page(id, name, listOf(Block.Image(image(id), null, Painter.W, Painter.H)), input = InputMode.GESTURES) })
        go(P_PLAN)
    }

    private fun now(): ZonedDateTime = Instant.ofEpochMilli(clock()).atZone(zone)

    // --- Pages: pictures and navigation ---------------------------------------------------------------------

    /** Shows [page] once its picture is drawn, on top or with [replace] in place of the one on top. */
    private fun go(page: String, replace: Boolean = false) {
        pendingShow = page
        pendingReplace = replace
        paint(page)
    }

    /** Draws the page on top again (and the one waiting to show). */
    private fun render() {
        pendingShow?.let { paint(it) }
        top()?.takeIf { it != pendingShow }?.let { paint(it) }
        pageChanged()
    }

    private fun paint(page: String) {
        val p = painter ?: return showPending(page)
        val screen = screen(page)
        val scroll = scrolls[page] ?: 0
        val no = (paints[page] ?: 0) + 1
        paints[page] = no
        ui.work("bild_$page", { p.paint(screen, scroll) }) { result ->
            if (paints[page] != no) return@work
            result.onSuccess { painted ->
                scrolls[page] = painted.scroll
                ui.patch(page) { image(image(page), painted.dataUrl, Painter.W, Painter.H) }
            }.onFailure { ui.log("Bild $page: ${it.message}") }
            showPending(page)
        }
    }

    private fun showPending(page: String) {
        if (pendingShow != page) return
        pendingShow = null
        if (top() == page) return pageChanged()
        if (pendingReplace) {
            ui.replace(page)
            when {
                stack.isEmpty() -> stack += page
                stack.size >= 2 && stack[stack.lastIndex - 1] == page -> stack.removeAt(stack.lastIndex)
                else -> stack[stack.lastIndex] = page
            }
        } else {
            ui.show(page)
            stack += page
        }
        pageChanged()
    }

    private fun top(): String? = stack.lastOrNull()

    private fun pageChanged() {
        updateMenu()
        updateRefresh()
        updateLocating()
        updateKeys()
    }

    /** What [page] shows now, as the painter draws it (and the tests read it). */
    fun screen(page: String): Screen = when (page) {
        P_PLAN -> planScreen()
        P_SEARCH -> searchScreen()
        P_RESULTS -> connectionsScreen()
        P_JOURNEY -> journeyScreen()
        P_BOARD -> boardScreen()
        P_TRIP -> tripScreen()
        P_FORMATION -> formationScreen()
        else -> nearScreen()
    }

    // --- Planen -----------------------------------------------------------------------------------------------

    private fun planRows(): List<Pair<Row, () -> Unit>> {
        val rows = ArrayList<Pair<Row, () -> Unit>>()
        for (r in routes.take(SHOWN_ROUTES)) {
            rows += Row("${r.from?.name ?: HERE} → ${r.to.name}", Icon.HISTORY) to {
                from = r.from
                to = r.to
                at = null
                arrival = false
                save()
                searchNow()
            }
        }
        rows += Row("Abfahrten in der Nähe", Icon.NEAR) to { openNearby() }
        rows += Row("Abfahrten einer Haltestelle", Icon.BOARD) to { input(Field.BOARD) }
        return rows
    }

    private fun planScreen() = PlanScreen(
        from = from?.name ?: HERE,
        fromHere = from == null,
        to = to?.name,
        time = timeLabel(),
        rows = planRows().map { it.first },
        focus = planFocus,
    )

    private fun timeLabel(): String {
        val word = if (arrival) "Ankunft" else "Abfahrt"
        val t = at ?: return "Jetzt · $word"
        return "${Format.capital(When.label(t, now()))} · $word"
    }

    private fun planGesture(g: GestureKind) {
        val rows = planRows()
        val last = PLAN_FIXED + rows.size - 1
        when (g) {
            GestureKind.SCROLL_DOWN -> planFocus = (planFocus + 1).coerceAtMost(last)
            GestureKind.SCROLL_UP -> planFocus = (planFocus - 1).coerceAtLeast(0)
            GestureKind.LONG_PRESS -> return swap()
            GestureKind.CLICK -> return when (planFocus) {
                0 -> input(Field.FROM)
                1 -> input(Field.TO)
                2 -> swap()
                3 -> askWhen()
                else -> rows.getOrNull(planFocus - PLAN_FIXED)?.second?.invoke() ?: Unit
            }
            else -> return
        }
        paint(P_PLAN)
    }

    private fun swap() {
        val a = from
        val b = to ?: return
        if (a == null) {
            ui.toast("„$HERE“ kann nicht Ziel sein.", NOTE_MS)
            return
        }
        from = b
        to = a
        save()
        paint(P_PLAN)
    }

    private fun askWhen() = ui.askText(ASK_WHEN, "Wann? Etwa 17:30, an 8:15, morgen 7:00", WHEN_SUGGESTIONS)

    // --- Typing a stop ----------------------------------------------------------------------------------------

    /** The stop for [f]: typed on the glasses with a keyboard, the stops that fit coming while typing, else asked on the watch. */
    private fun input(f: Field) {
        field = f
        query = ""
        found = emptyList()
        lookupError = null
        lookupLoading = false
        searchSelected = 0
        typing = ui.keyboard
        if (typing) {
            scrolls[P_SEARCH] = 0
            go(P_SEARCH)
        } else {
            ask(f)
        }
    }

    private fun ask(f: Field) {
        val names = places.map { it.name.take(MAX_SUGGESTION) }
        when (f) {
            Field.FROM -> ui.askText(ASK_FROM, "Von wo?", (listOf(HERE) + names).distinct().take(MAX_SUGGESTIONS))
            Field.TO -> ui.askText(ASK_TO, "Wohin?", names.filter { it != from?.name }.take(MAX_SUGGESTIONS))
            Field.BOARD -> ui.askText(ASK_BOARD, "Abfahrten von wo?", names.take(MAX_SUGGESTIONS))
        }
    }

    /** The lines of the stop page with what each does. */
    private fun searchRows(): List<Pair<Row, () -> Unit>> {
        val f = field ?: return emptyList()
        val rows = ArrayList<Pair<Row, () -> Unit>>()
        if (query.isEmpty()) {
            if (f == Field.FROM) rows += Row(HERE, Icon.HERE) to { choseHere() }
            for (p in places) rows += Row(p.name, p.icon) to { chose(f, p) }
        } else {
            for (p in found) rows += Row(p.name, p.icon) to { chose(f, p) }
        }
        if (!typing) rows += Row("Anders eingeben", Icon.TYPE) to { ask(f) }
        return rows
    }

    private fun searchScreen(): ListScreen {
        val label = when (field) {
            Field.FROM -> "Von"
            Field.TO -> "Nach"
            Field.BOARD, null -> "Abfahrten von"
        }
        val note = when {
            lookupLoading && found.isEmpty() -> "Suche Haltestellen …"
            lookupError != null -> lookupError
            typing && query.isEmpty() -> "Tippe den Namen der Haltestelle."
            else -> null
        }
        return ListScreen(null, label, query, typing, searchRows().map { it.first }, searchSelected, note)
    }

    private fun searchGesture(g: GestureKind) {
        val rows = searchRows()
        when (g) {
            GestureKind.SCROLL_DOWN -> searchSelected = (searchSelected + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))
            GestureKind.SCROLL_UP -> searchSelected = (searchSelected - 1).coerceAtLeast(0)
            GestureKind.CLICK -> return rows.getOrNull(searchSelected)?.second?.invoke() ?: Unit
            GestureKind.LONG_PRESS -> return field?.let { ask(it) } ?: Unit
            else -> return
        }
        paint(P_SEARCH)
    }

    /** A key of the keyboard while a stop is typed on the glasses. */
    private fun key(k: AppEvent.Key) {
        if (top() != P_SEARCH || !typing) return
        when {
            k.key == Keys.ENTER -> return searchRows().getOrNull(searchSelected)?.second?.invoke() ?: Unit
            k.key == Keys.ARROW_DOWN -> return searchGesture(GestureKind.SCROLL_DOWN)
            k.key == Keys.ARROW_UP -> return searchGesture(GestureKind.SCROLL_UP)
            k.key == Keys.BACKSPACE -> query = query.dropLast(1)
            k.text.isNotEmpty() && !k.ctrl && !k.alt && !k.meta -> query = (query + k.text).take(MAX_QUERY)
            else -> return
        }
        searchSelected = 0
        if (query.length >= MIN_TYPED) ui.timer(T_TYPE, TYPE_MS) else {
            ui.cancelTimer(T_TYPE)
            found = emptyList()
            lookupLoading = false
            lookupError = null
        }
        paint(P_SEARCH)
    }

    /** The stops that fit what is typed so far, as the SBB app shows them while typing. */
    private fun lookupTyped() {
        val f = field ?: return
        if (query.length < MIN_TYPED) return
        fetchPlaces(f, query, pickExact = false)
    }

    /** The stop the wearer means by [text] (answered on the watch): one used before, the only fitting one, or a choice. */
    private fun lookup(f: Field, text: String) {
        places.firstOrNull { same(it.name, text) }?.let { return chose(f, it) }
        field = f
        query = text
        typing = false
        found = emptyList()
        searchSelected = 0
        fetchPlaces(f, text, pickExact = true)
    }

    private fun fetchPlaces(f: Field, text: String, pickExact: Boolean) {
        lookupLoading = true
        lookupError = null
        val no = ++lookupNo
        if (top() == P_SEARCH) paint(P_SEARCH)
        ui.fetch(HttpRequest(Timetable.placesUrl(text))) { r ->
            if (no != lookupNo || field != f) return@fetch
            lookupLoading = false
            val list = if (r.ok) Timetable.places(r.body) else null
            when {
                list == null -> {
                    ui.log("Haltestellen: ${problem(r)}")
                    found = emptyList()
                    lookupError = OFFLINE
                }
                list.isEmpty() -> {
                    found = emptyList()
                    lookupError = "Keine Haltestelle „$text“ gefunden."
                }
                pickExact && same(list.first().name, text) -> return@fetch chose(f, list.first())
                else -> {
                    found = list
                    lookupError = null
                }
            }
            searchSelected = 0
            if (top() == P_SEARCH) paint(P_SEARCH) else go(P_SEARCH)
        }
    }

    private fun choseHere() {
        from = null
        save()
        // With the destination there the connections follow at once, in place of the stop page.
        if (to != null) {
            endTyping()
            searchNow()
        } else {
            leaveSearch()
        }
    }

    private fun chose(f: Field, place: Place) {
        remember(place)
        ui.cancelTimer(T_TYPE)
        when (f) {
            Field.FROM -> {
                from = place
                save()
                if (to != null) {
                    endTyping()
                    searchNow()
                } else {
                    leaveSearch()
                }
            }
            Field.TO -> {
                // As in the SBB app: with the destination chosen the connections show.
                to = place
                save()
                planFocus = 0
                endTyping()
                searchNow()
            }
            Field.BOARD -> {
                save()
                typing = false
                field = null
                openBoard(place, replace = top() == P_SEARCH)
            }
        }
    }

    /** Back to Planen, which lies below the stop page. */
    private fun leaveSearch() {
        endTyping()
        if (top() == P_SEARCH) go(P_PLAN, replace = true) else paint(P_PLAN)
    }

    private fun endTyping() {
        typing = false
        field = null
        ui.cancelTimer(T_TYPE)
    }

    private fun remember(place: Place) {
        val p = place.copy(distance = null)
        places = (listOf(p) + places.filter { !same(it.name, p.name) }).take(MAX_PLACES)
    }

    private fun answer(tag: String, text: String?) {
        if (tag == ASK_KEY) return setKey(text)
        val t = text?.trim()?.replace(SPACES, " ").orEmpty().take(MAX_QUERY)
        if (t.isEmpty()) {
            if (tag == ASK_TO) searchAfter = false
            return
        }
        when (tag) {
            ASK_FROM -> if (same(t, HERE) || same(t, "Mein Standort")) choseHere() else lookup(Field.FROM, t)
            ASK_TO -> lookup(Field.TO, t)
            ASK_BOARD -> lookup(Field.BOARD, t)
            ASK_WHEN -> setWhen(t)
        }
    }

    /** "17:30", "an 8:15" (arrival), "ab 9" (departure), "morgen 7:00", "jetzt" … */
    private fun setWhen(text: String) {
        val words = text.lowercase(Locale.GERMAN).split(' ')
        val kind = when (words.first()) {
            "an", "ankunft" -> true
            "ab", "abfahrt" -> false
            else -> null
        }
        when (val a = When.parse(text, now())) {
            null -> return ui.toast("„$text“ verstehe ich nicht. Etwa 17:30, an 8:15 oder morgen 7:00.", NOTE_MS)
            When.Answer.Now -> {
                at = null
                arrival = kind ?: false
            }
            is When.Answer.At -> {
                at = a.ms
                kind?.let { arrival = it }
            }
        }
        if (to != null) searchNow() else paint(P_PLAN)
    }

    // --- Connections ------------------------------------------------------------------------------------------

    private fun searchNow() {
        val target = to ?: run {
            searchAfter = true
            return input(Field.TO)
        }
        searchAfter = false
        val start = from
        if (start != null && (start.id != null && start.id == target.id || same(start.name, target.name))) {
            ui.toast("Von und Nach sind dieselbe Haltestelle.", NOTE_MS)
            // Chosen on the stop page: back to Planen, where either can be changed.
            if (top() == P_SEARCH) go(P_PLAN, replace = true)
            return
        }
        val route = Route(start, target)
        routes = (listOf(route) + routes.filter { it.key != route.key }).take(MAX_ROUTES)
        save()
        chosen = null
        connections = emptyList()
        connSelected = 0
        scrolls[P_RESULTS] = 0
        searchedAt = at ?: clock()
        run(Search(start, target, at, arrival))
        when (top()) {
            P_RESULTS -> Unit
            P_SEARCH -> go(P_RESULTS, replace = true)
            else -> go(P_RESULTS)
        }
    }

    /** Searches [s] and shows its connections in place of the ones shown. */
    private fun run(s: Search) {
        val origin = s.origin ?: s.from?.let { it.id ?: it.name } ?: here()
        connections = emptyList()
        connError = null
        loadingEarlier = false
        loadingLater = false
        if (origin == null) {
            search = s
            connLoading = false
            ++connNo
            waitFor(Wait.SEARCH)
            return render()
        }
        val active = s.copy(origin = origin)
        search = active
        connLoading = true
        val no = ++connNo
        ++moreNo
        render()
        fetchConnections(active, W_RESULTS) { list, error ->
            if (no != connNo) return@fetchConnections
            connLoading = false
            if (list != null) {
                // An arrival search gives those arriving by then; the one closest to then comes last, and shows.
                connections = list
                connSelected = if (active.arrival) (list.size - 1).coerceAtLeast(0) else 0
                connError = if (list.isEmpty()) "Keine Verbindung gefunden." else null
            } else {
                connError = error
            }
            render()
        }
    }

    /** The connections of [s] (with its origin known), or null and why. */
    private fun fetchConnections(s: Search, work: String, done: (List<Connection>?, String?) -> Unit) {
        val time = Instant.ofEpochMilli(s.at ?: clock()).atZone(zone)
        val url = Timetable.connectionsUrl(s.origin!!, s.to.id ?: s.to.name, time, s.arrival)
        ui.fetch(HttpRequest(url)) { r ->
            if (!r.ok) {
                ui.log("Verbindungen: ${problem(r)}")
                done(null, OFFLINE)
                return@fetch
            }
            ui.work(work, { Timetable.connections(r.body) }) { result ->
                result.onSuccess { done(it, null) }.onFailure {
                    ui.log("Verbindungen unlesbar: ${it.message}")
                    done(null, UNREADABLE)
                }
            }
        }
    }

    /** Earlier ones come before the first (those arriving before it), later ones after the last, as scrolling does. */
    private fun more(earlier: Boolean) {
        val s = search?.takeIf { it.origin != null } ?: return
        val list = connections.takeIf { it.isNotEmpty() } ?: return
        if (loadingEarlier || loadingLater || connLoading) return
        if (earlier) loadingEarlier = true else loadingLater = true
        val probe = if (earlier) {
            s.copy(at = (list.first().to.arrival!! - 60) * 1000, arrival = true)
        } else {
            s.copy(at = (list.last().from.departure!! + 60) * 1000, arrival = false)
        }
        val no = ++moreNo
        render()
        fetchConnections(probe, if (earlier) W_EARLIER else W_LATER) { got, error ->
            if (no != moreNo) return@fetchConnections
            loadingEarlier = false
            loadingLater = false
            val fresh = got.orEmpty().filter { c -> connections.none { it.sameAs(c) } }
            when {
                got == null -> ui.toast(error ?: OFFLINE, NOTE_MS)
                fresh.isEmpty() -> ui.toast(if (earlier) "Keine früheren Verbindungen." else "Keine späteren Verbindungen.", NOTE_MS)
                earlier -> {
                    connections = (fresh + connections).take(MAX_CONNECTIONS)
                    connSelected = fresh.size - 1
                }
                else -> {
                    val keep = connections + fresh
                    val drop = (keep.size - MAX_CONNECTIONS).coerceAtLeast(0)
                    connections = keep.drop(drop)
                    connSelected = (connSelected - drop + 1).coerceIn(0, connections.size - 1)
                }
            }
            render()
        }
    }

    private fun connectionsScreen(): ConnectionsScreen {
        val s = search
        val title = if (s == null) "Verbindungen" else "${s.from?.name ?: HERE} → ${s.to.name}"
        val subtitle = if (s == null) "" else {
            val t = Instant.ofEpochMilli(searchedAt).atZone(zone)
            "${Format.capital(Format.day(t.toLocalDate(), now().toLocalDate()))} ${if (s.arrival && s.at != null) "an" else "ab"} ${Format.clock(searchedAt / 1000, zone)}"
        }
        val note = when {
            waiting == Wait.SEARCH -> "Suche den Standort …"
            connLoading && connections.isEmpty() -> "Suche Verbindungen …"
            connections.isEmpty() -> connError
            else -> null
        }
        val any = connections.isNotEmpty()
        return ConnectionsScreen(
            title = title,
            subtitle = subtitle,
            cards = connections.map { card(it) },
            selected = connSelected,
            note = note,
            earlier = if (!any) null else if (loadingEarlier) "Lade frühere Verbindungen …" else "Frühere Verbindungen",
            later = if (!any) null else if (loadingLater) "Lade spätere Verbindungen …" else "Spätere Verbindungen",
        )
    }

    private fun card(c: Connection): Card {
        val rides = c.rides
        val first = rides.firstOrNull()
        return Card(
            badges = rides.map { it.line!!.label },
            direction = first?.line?.let { "Richtung ${it.direction}" } ?: "zu Fuß",
            departure = Format.clock(c.from.departure!!, zone),
            departureDelay = delay(c.from.delay),
            arrival = Format.clock(c.to.arrival!!, zone),
            arrivalDelay = delay(c.to.delay),
            spans = spans(c),
            platform = first?.let { platformText(it.departure, it.line!!) },
            platformChanged = first?.departure?.let { it.newPlatform != null && it.platform != null } ?: false,
            info = if (c.transfers == 0) Format.duration(c.minutes) else "${Format.transfers(c.transfers)} · ${Format.duration(c.minutes)}",
        )
    }

    /** The parts of the journey bar, by time: the rides, and the walks dotted. */
    private fun spans(c: Connection): List<Span> {
        val start = c.from.departure!!
        val total = (c.to.arrival!! - start).coerceAtLeast(1).toDouble()
        return c.legs.map { leg ->
            val a = leg.departure.departure ?: leg.departure.arrival ?: start
            val b = leg.arrival.arrival ?: leg.arrival.departure ?: a
            Span(((a - start) / total).coerceIn(0.0, 1.0), ((b - start) / total).coerceIn(0.0, 1.0), leg.line == null)
        }
    }

    private fun resultsGesture(g: GestureKind) {
        val n = connections.size
        when (g) {
            GestureKind.SCROLL_DOWN -> if (connSelected < n - 1) connSelected++ else return more(earlier = false)
            GestureKind.SCROLL_UP -> if (connSelected > 0) connSelected-- else return more(earlier = true)
            GestureKind.CLICK -> return connections.getOrNull(connSelected)?.let { openJourney(it) } ?: retry()
            GestureKind.LONG_PRESS -> return refresh()
            else -> return
        }
        paint(P_RESULTS)
    }

    /** With no connections shown, a tap tries again. */
    private fun retry() {
        val s = search ?: return
        if (connLoading || waiting != null) return
        run(s.copy(origin = if (s.from == null) null else s.origin))
    }

    private fun returnTrip() {
        val s = search ?: return
        val back = s.from ?: return ui.toast("Eine Rückfahrt braucht eine Haltestelle als Start.", NOTE_MS)
        from = s.to
        to = back
        at = null
        arrival = false
        searchNow()
    }

    // --- Ihre Reise --------------------------------------------------------------------------------------------

    private fun openJourney(c: Connection) {
        chosen = c
        openRides.clear()
        loadFormations(c)
        journeySelected = journeyItems(c).indexOfFirst { it is JourneyItem.Ride }.coerceAtLeast(0)
        scrolls[P_JOURNEY] = 0
        go(P_JOURNEY)
    }

    /** The lines of a journey, and for each the index of its leg (rides open and close by it). */
    private fun journeyLines(c: Connection): List<Pair<JourneyItem, Int>> {
        val out = ArrayList<Pair<JourneyItem, Int>>()
        var previous: Leg? = null
        c.legs.forEachIndexed { i, leg ->
            val line = leg.line
            val isFirst = i == 0
            val isLast = i == c.legs.lastIndex
            if (line == null) {
                if (isFirst) out += stopItem(leg.departure, leg.departure.departure, null, end = true) to i
                out += JourneyItem.Walk(Format.walk(leg)) to i
                if (isLast) out += stopItem(leg.arrival, leg.arrival.arrival, null, end = true) to i
            } else {
                val before = previous
                if (before?.line != null) {
                    val gap = ((leg.departure.departure ?: 0) - (before.arrival.arrival ?: 0)) / 60
                    out += JourneyItem.Change("Umsteigen in ${leg.departure.name} · ${gap.coerceAtLeast(0)} Min.") to i
                }
                out += stopItem(leg.departure, leg.departure.departure, line, end = isFirst) to i
                rideFormation(leg)?.let { out += JourneyItem.Formation(formationText(it)) to i }
                val between = leg.stops.drop(1).dropLast(1)
                out += JourneyItem.Ride(line.label, "Richtung ${line.direction}", i in openRides, between.size) to i
                if (i in openRides) {
                    for (s in between) out += JourneyItem.Pass(Format.clock(s.arrival ?: s.departure ?: 0, zone), s.name, platformText(s, line), s.id) to i
                }
                out += stopItem(leg.arrival, leg.arrival.arrival, line, end = isLast) to i
            }
            previous = leg
        }
        return out
    }

    private fun journeyItems(c: Connection) = journeyLines(c).map { it.first }

    private fun stopItem(s: Stop, time: Long?, line: Line?, end: Boolean) = JourneyItem.Stop(
        time = time?.let { Format.clock(it, zone) } ?: "",
        delay = delay(s.delay),
        name = s.name,
        platform = line?.let { platformText(s, it) },
        changed = s.newPlatform != null && s.platform != null,
        end = end,
        id = s.id,
    )

    private fun journeyScreen(): Screen {
        val c = chosen ?: return ListScreen("Ihre Reise", null, null, false, emptyList(), -1, "Keine Verbindung gewählt.")
        return JourneyScreen(
            departure = Format.clock(c.from.departure!!, zone) + (delay(c.from.delay)?.let { " $it" } ?: ""),
            arrival = Format.clock(c.to.arrival!!, zone) + (delay(c.to.delay)?.let { " $it" } ?: ""),
            spans = spans(c),
            info = "${Format.capital(Format.day(c.from.departure, now().toLocalDate(), zone))} · ${Format.duration(c.minutes)} · ${Format.transfers(c.transfers)}",
            badges = c.rides.map { it.line!!.label },
            items = journeyItems(c),
            selected = journeySelected,
        )
    }

    private fun journeyGesture(g: GestureKind) {
        val c = chosen ?: return
        val lines = journeyLines(c)
        when (g) {
            GestureKind.SCROLL_DOWN -> journeySelected = (journeySelected + 1).coerceAtMost(lines.size - 1)
            GestureKind.SCROLL_UP -> journeySelected = (journeySelected - 1).coerceAtLeast(0)
            GestureKind.CLICK -> when (val item = lines.getOrNull(journeySelected)?.first) {
                is JourneyItem.Ride -> {
                    val leg = lines[journeySelected].second
                    if (!openRides.remove(leg)) openRides += leg
                }
                is JourneyItem.Stop -> return item.id?.let { openBoard(Place(it, item.name)) } ?: Unit
                is JourneyItem.Pass -> return item.id?.let { openBoard(Place(it, item.name)) } ?: Unit
                is JourneyItem.Formation -> return rideFormation(c.legs[lines[journeySelected].second])?.let { openFormation(it) } ?: Unit
                else -> return
            }
            GestureKind.LONG_PRESS -> return refreshChosen()
            else -> return
        }
        paint(P_JOURNEY)
    }

    /** The chosen connection again, for its delays: asked for from its own departure on, so it is found after it left. */
    private fun refreshChosen() {
        val c = chosen ?: return
        val s = search?.takeIf { it.origin != null } ?: return
        val no = ++chosenNo
        fetchConnections(s.copy(at = (c.from.departure!! - 60) * 1000, arrival = false), W_CHOSEN) { list, _ ->
            if (no != chosenNo || chosen !== c) return@fetchConnections
            list?.firstOrNull { it.sameAs(c) }?.let {
                chosen = it
                connections = connections.map { old -> if (old.sameAs(it)) it else old }
                render()
            }
        }
    }

    // --- Departures and trips ----------------------------------------------------------------------------------

    private fun openBoard(place: Place, replace: Boolean = false) {
        boardPlace = place
        boardAt = null
        departures = emptyList()
        boardSelected = 0
        scrolls[P_BOARD] = 0
        loadBoard()
        if (top() != P_BOARD) go(P_BOARD, replace)
    }

    /** The departures from [boardAt] (now when null); [append] adds later ones to those shown. */
    private fun loadBoard(append: Boolean = false, silent: Boolean = false) {
        val place = boardPlace ?: return
        if (!append && !silent) {
            departures = emptyList()
            boardError = null
        }
        if (append) boardMore = true else boardLoading = true
        val no = ++boardNo
        val time = Instant.ofEpochMilli(if (append) (departures.last().time + 60) * 1000 else boardAt ?: clock()).atZone(zone)
        if (!silent) render()
        ui.fetch(HttpRequest(Timetable.boardUrl(place, time))) { r ->
            if (no != boardNo) return@fetch
            if (!r.ok) {
                ui.log("Abfahrten: ${problem(r)}")
                boardLoading = false
                boardMore = false
                if (departures.isEmpty()) boardError = OFFLINE else if (append) ui.toast(OFFLINE, NOTE_MS)
                render()
                return@fetch
            }
            ui.work(W_BOARD, { Timetable.board(r.body) }) { result ->
                if (no != boardNo) return@work
                boardLoading = false
                boardMore = false
                result.onSuccess { b ->
                    when {
                        append -> {
                            val fresh = b.departures.filter { d -> departures.none { same(it, d) } }
                            if (fresh.isEmpty()) ui.toast("Keine späteren Abfahrten.", NOTE_MS)
                            departures = (departures + fresh).takeLast(MAX_DEPARTURES)
                            boardSelected = (boardSelected + 1).coerceAtMost(departures.size - 1)
                        }
                        silent -> departures = departures.map { old -> b.departures.firstOrNull { same(it, old) } ?: old }
                        else -> {
                            departures = b.departures
                            boardError = if (b.departures.isEmpty()) "Keine Abfahrten gefunden." else null
                        }
                    }
                }.onFailure {
                    ui.log("Abfahrten unlesbar: ${it.message}")
                    if (departures.isEmpty()) boardError = UNREADABLE
                }
                render()
            }
        }
    }

    private fun same(a: Departure, b: Departure) = a.time == b.time && a.line.label == b.line.label && a.line.direction == b.line.direction

    private fun boardScreen(): ListScreen {
        val place = boardPlace
        val first = departures.firstOrNull()
        val title = when {
            place == null -> "Abfahrten"
            first == null -> place.name
            else -> "${place.name} · ab ${Format.clock(first.time, zone)}"
        }
        val rows = departures.map { d ->
            Row(
                text = d.line.direction,
                icon = d.line.icon,
                time = Format.clock(d.time, zone),
                delay = delay(d.delay),
                badge = d.line.label,
                right = platformText(d.platform, d.newPlatform, d.line),
                changed = d.newPlatform != null && d.platform != null,
            )
        }
        val note = when {
            boardLoading && departures.isEmpty() -> "Lade die Abfahrten …"
            departures.isEmpty() -> boardError
            boardMore -> "Lade spätere Abfahrten …"
            else -> null
        }
        return ListScreen(title, null, null, false, rows, boardSelected, note)
    }

    private fun boardGesture(g: GestureKind) {
        val n = departures.size
        when (g) {
            GestureKind.SCROLL_DOWN -> if (boardSelected < n - 1) boardSelected++ else return if (n > 0 && !boardMore) loadBoard(append = true) else Unit
            GestureKind.SCROLL_UP -> boardSelected = (boardSelected - 1).coerceAtLeast(0)
            GestureKind.CLICK -> return departures.getOrNull(boardSelected)?.let { openTrip(it) } ?: if (n == 0 && !boardLoading) loadBoard() else Unit
            GestureKind.LONG_PRESS -> return routeFromBoard()
            else -> return
        }
        paint(P_BOARD)
    }

    private fun routeFromBoard() {
        val place = boardPlace ?: return
        from = place
        planFocus = 1
        save()
        go(P_PLAN)
    }

    private fun openTrip(d: Departure) {
        if (d.stops.isEmpty()) return ui.toast("Zu dieser Fahrt kennt der Fahrplan keine Halte.", NOTE_MS)
        trip = d
        tripSelected = 0
        scrolls[P_TRIP] = 0
        tripFormation(d)?.let { fetchFormation(it) }
        go(P_TRIP)
    }

    /** The lines of the trip page with what each does: its formation first (with a key), then its stops. */
    private fun tripRows(d: Departure): List<Pair<Row, () -> Unit>> {
        val rows = ArrayList<Pair<Row, () -> Unit>>()
        tripFormation(d)?.let { t -> rows += Row(formationText(t), Icon.FORMATION) to { openFormation(t) } }
        d.stops.forEachIndexed { i, s ->
            val time = if (i == 0) s.departure ?: s.arrival else s.arrival ?: s.departure
            val row = Row(
                text = s.name,
                time = time?.let { Format.clock(it, zone) },
                delay = delay(s.delay),
                right = platformText(s, d.line),
                changed = s.newPlatform != null && s.platform != null,
            )
            rows += row to { s.id?.let { openBoard(Place(it, s.name)) } ?: Unit }
        }
        return rows
    }

    private fun tripScreen(): ListScreen {
        val d = trip ?: return ListScreen("Fahrt", null, null, false, emptyList(), -1, "Keine Fahrt gewählt.")
        return ListScreen("${d.line.label} nach ${d.line.direction}", null, null, false, tripRows(d).map { it.first }, tripSelected, null)
    }

    private fun tripGesture(g: GestureKind) {
        val rows = tripRows(trip ?: return)
        when (g) {
            GestureKind.SCROLL_DOWN -> tripSelected = (tripSelected + 1).coerceAtMost(rows.size - 1)
            GestureKind.SCROLL_UP -> tripSelected = (tripSelected - 1).coerceAtLeast(0)
            GestureKind.CLICK -> return rows.getOrNull(tripSelected)?.second?.invoke() ?: Unit
            else -> return
        }
        paint(P_TRIP)
    }

    // --- Zugformation ------------------------------------------------------------------------------------------

    /** The train of [line] leaving [stop] at [at] (seconds), if it is one whose railway publishes its formations. */
    private fun formationTarget(line: Line?, at: Long?, stop: Stop): FormationTarget? {
        val l = line?.takeIf { it.isTrain } ?: return null
        val evu = Formation.evu(l.operator) ?: return null
        val train = l.train ?: return null
        val date = Instant.ofEpochSecond(at ?: return null).atZone(zone).toLocalDate()
        return FormationTarget("$evu/$train/$date", evu, train, date, l, stop.id, stop.name)
    }

    /** The formation of a ride where it is boarded, with a key. */
    private fun rideFormation(leg: Leg): FormationTarget? = if (key == null) null else formationTarget(leg.line, leg.departure.departure, leg.departure)

    /** The formation of the train of a departure at the board's stop, with a key. */
    private fun tripFormation(d: Departure): FormationTarget? {
        if (key == null) return null
        return formationTarget(d.line, d.time, d.stops.firstOrNull() ?: return null)
    }

    private fun loadFormations(c: Connection) {
        for (leg in c.legs) rideFormation(leg)?.let { fetchFormation(it) }
    }

    /** The line in Ihre Reise and on the trip page: where the train stands, as soon as that is known. */
    private fun formationText(t: FormationTarget): String {
        if (t.date != now().toLocalDate()) return "Zugformation am Reisetag"
        val e = formations[t.id]
        val sectors = e?.stops?.let { pick(it, t) }?.sectorText()
        return when {
            sectors != null -> "Zugformation · $sectors"
            e?.loading == true -> "Zugformation …"
            else -> "Zugformation"
        }
    }

    /** The formation at the stop of [t] among those of its train. */
    private fun pick(stops: List<StopFormation>, t: FormationTarget): StopFormation? =
        stops.firstOrNull { t.stopId != null && it.uic == t.stopId } ?: stops.firstOrNull { same(it.name, t.stopName) }

    /** The formation page for [t]; without a train (or a key) it says how to set the formation up. */
    private fun openFormation(t: FormationTarget?) {
        formationOf = t
        formationSelected = 0
        scrolls[P_FORMATION] = 0
        t?.let { fetchFormation(it) }
        go(P_FORMATION)
    }

    /** Asks the service for the formation of [t], unless a recent answer is there; it only knows today's trains. */
    private fun fetchFormation(t: FormationTarget, force: Boolean = false) {
        val k = key ?: return
        if (t.date != now().toLocalDate()) return
        val e = formations.getOrPut(t.id) { FormationEntry() }
        if (e.loading || !force && e.stops != null && clock() - e.at < FORMATION_FRESH_MS) return
        e.loading = true
        e.error = null
        val request = HttpRequest(Formation.url(t.evu, t.train, t.date), headers = mapOf("Authorization" to "Bearer $k", "Accept" to "application/json"))
        ui.fetch(request) { r ->
            if (!r.ok) {
                e.loading = false
                e.error = when (r.status) {
                    401, 403 -> BAD_KEY
                    404 -> NO_FORMATION
                    429 -> TOO_MANY
                    else -> OFFLINE
                }
                ui.log("Zugformation ${t.id}: ${problem(r)}")
                render()
                return@fetch
            }
            ui.work("zf_${t.train}", { Formation.stops(r.body) }) { result ->
                e.loading = false
                result.onSuccess {
                    e.stops = it
                    e.at = clock()
                }.onFailure {
                    ui.log("Zugformation unlesbar: ${it.message}")
                    e.error = UNREADABLE
                }
                render()
            }
        }
    }

    private fun formationScreen(): FormationScreen {
        val t = formationOf
        if (key == null || t == null) return FormationScreen(null, null, null, null, emptyList(), 0, null, null, if (key == null) SETUP else KEY_SAVED)
        val e = formations[t.id]
        val stop = e?.stops?.let { pick(it, t) }
        val cars = stop?.cars.orEmpty()
        val note = when {
            t.date != now().toLocalDate() -> "Die Zugformation gibt es erst am Reisetag."
            cars.isNotEmpty() -> null
            e == null || e.loading -> "Lade die Zugformation …"
            e.error != null -> e.error
            else -> "Zu diesem Halt kennt der Dienst keine Zugformation."
        }
        val selected = formationSelected.coerceIn(0, (cars.size - 1).coerceAtLeast(0))
        val car = cars.getOrNull(selected)
        return FormationScreen(
            badge = t.line.label,
            train = t.train,
            place = listOfNotNull(stop?.name ?: t.stopName, stop?.track?.let { "${t.line.platformWord} $it" }).joinToString(" · "),
            direction = "Richtung ${t.line.direction}",
            cars = cars.map { carBox(it) },
            selected = selected,
            detail = car?.let { c -> listOfNotNull(c.number?.let { "Wagen $it" }, c.kind.text, "geschlossen".takeIf { c.closed }).joinToString(" · ") },
            more = car?.let { carMore(it, stop?.destinationOf(selected)) },
            note = note,
        )
    }

    private fun carBox(c: Car) = CarBox(
        label = c.kind.label,
        number = c.number,
        icon = when (c.kind) {
            CarKind.DINING, CarKind.DINING_FIRST, CarKind.DINING_SECOND -> CarIcon.DINING
            CarKind.FAMILY -> CarIcon.FAMILY
            CarKind.SLEEPER -> CarIcon.SLEEPER
            CarKind.COUCHETTE -> CarIcon.COUCHETTE
            CarKind.BAGGAGE -> CarIcon.BAGGAGE
            CarKind.LOCO -> CarIcon.LOCO
            else -> null
        },
        services = c.services.sortedBy { it.ordinal },
        closed = c.closed,
        sector = c.sector,
    )

    /** "Velo · Kinderwagen · Niederflur · Sektor B": what the car in the frame offers, and where it stands. */
    private fun carMore(c: Car, destination: String?): String = (
        c.services.sortedBy { it.ordinal }.map { it.text } + listOfNotNull(
            "Speisewagen nicht bedient".takeIf { c.notServed },
            "für Gruppen reserviert".takeIf { c.groups },
            destination?.let { "nach $it" },
            c.sector.takeIf { it.isNotEmpty() }?.let { "Sektor $it" },
        )
        ).joinToString(" · ")

    private fun formationGesture(g: GestureKind) {
        val t = formationOf
        if (key == null || t == null) {
            if (g == GestureKind.CLICK && key == null) askKey()
            return
        }
        val n = formations[t.id]?.stops?.let { pick(it, t) }?.cars?.size ?: 0
        when (g) {
            GestureKind.SCROLL_DOWN -> formationSelected = (formationSelected + 1).coerceAtMost((n - 1).coerceAtLeast(0))
            GestureKind.SCROLL_UP -> formationSelected = (formationSelected - 1).coerceAtLeast(0)
            GestureKind.LONG_PRESS -> return refreshFormation()
            GestureKind.CLICK -> return if (n == 0) refreshFormation() else Unit
            else -> return
        }
        paint(P_FORMATION)
    }

    private fun refreshFormation() {
        fetchFormation(formationOf ?: return, force = true)
        render()
    }

    private fun askKey() = ui.askText(ASK_KEY, "Schlüssel für die Zugformation (opentransportdata.swiss)", emptyList())

    /** The key typed into the app, else the one in [KEY_FILE] in the app's folder (put there like a map for Navi). */
    private fun loadKey() {
        val typed = (ui.storage.get(K_KEY) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        key = typed ?: runCatching { ui.files.dir?.let { File(it, KEY_FILE) }?.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun setKey(text: String?) {
        val k = text?.filterNot { it.isWhitespace() }.orEmpty()
        if (k.isEmpty()) return
        ui.storage.set(K_KEY, JsonPrimitive(k))
        key = k
        formations.clear()
        ui.toast("Schlüssel gespeichert", NOTE_MS)
        formationOf?.let { fetchFormation(it) }
        chosen?.let { loadFormations(it) }
        trip?.let { d -> tripFormation(d)?.let { fetchFormation(it) } }
        render()
    }

    // --- Where the watch is -------------------------------------------------------------------------------------

    /** The position for a connection, if the watch knows a recent one. */
    private fun here(): String? = fresh()?.let { Timetable.position(it.lat, it.lon) }

    private fun fresh(): AppEvent.Location? = fix?.takeIf { clock() - fixAt <= FIX_FRESH_MS }

    private fun openNearby() {
        nearby = emptyList()
        nearError = null
        nearSelected = 0
        scrolls[P_NEAR] = 0
        locateNearby()
        go(P_NEAR)
    }

    private fun locateNearby() {
        val f = fresh()
        if (f == null) {
            nearLoading = false
            nearError = null
            waitFor(Wait.NEAR)
            return render()
        }
        nearLoading = true
        nearError = null
        val no = ++nearNo
        render()
        ui.fetch(HttpRequest(Timetable.nearbyUrl(f.lat, f.lon))) { r ->
            if (no != nearNo) return@fetch
            nearLoading = false
            if (r.ok) {
                nearby = Timetable.places(r.body)
                nearError = if (nearby.isEmpty()) "Keine Haltestelle in der Nähe gefunden." else null
            } else {
                ui.log("In der Nähe: ${problem(r)}")
                nearError = OFFLINE
            }
            render()
        }
    }

    private fun nearScreen(): ListScreen {
        val note = when {
            waiting == Wait.NEAR -> "Suche den Standort …"
            nearLoading -> "Suche Haltestellen in der Nähe …"
            else -> nearError
        }
        val rows = nearby.map { Row(it.name, it.icon, right = Format.distance(it.distance)) }
        return ListScreen("Haltestellen in der Nähe", null, null, false, rows, nearSelected, note)
    }

    private fun nearGesture(g: GestureKind) {
        when (g) {
            GestureKind.SCROLL_DOWN -> nearSelected = (nearSelected + 1).coerceAtMost((nearby.size - 1).coerceAtLeast(0))
            GestureKind.SCROLL_UP -> nearSelected = (nearSelected - 1).coerceAtLeast(0)
            GestureKind.CLICK -> return nearby.getOrNull(nearSelected)?.let { openBoard(it) } ?: if (waiting == null && !nearLoading) locateNearby() else Unit
            else -> return
        }
        paint(P_NEAR)
    }

    private fun waitFor(w: Wait) {
        waiting = w
        ui.timer(T_FIX, FIX_TIMEOUT_MS)
        updateLocating()
    }

    private fun onFix(f: AppEvent.Location) {
        fix = f
        fixAt = clock()
        val w = waiting
        if (w != null) {
            waiting = null
            ui.cancelTimer(T_FIX)
            when (w) {
                Wait.SEARCH -> search?.let { run(it) }
                Wait.NEAR -> locateNearby()
            }
        }
        updateLocating()
    }

    private fun noFix() {
        val w = waiting ?: return
        waiting = null
        when (w) {
            Wait.SEARCH -> connError = NO_FIX
            Wait.NEAR -> nearError = NO_FIX
        }
        render()
    }

    /**
     * The GPS runs only while a position is awaited, and on Planen with "Aktueller Standort" until one is there, so
     * that it is ready for the search.
     */
    private fun updateLocating() {
        val ahead = top() == P_PLAN && from == null && fresh() == null
        val want = visible && (waiting != null || ahead)
        if (want == locating) return
        locating = want
        if (want) ui.subscribe(Sensor.LOCATION, FIX_RATE_S) else ui.unsubscribe(Sensor.LOCATION)
    }

    /** The keys of a keyboard come to the app while a stop is typed on the glasses, else the host makes them gestures. */
    private fun updateKeys() {
        val want = visible && top() == P_SEARCH && typing
        if (want == listening) return
        listening = want
        if (want) ui.subscribe(Sensor.KEYS) else ui.unsubscribe(Sensor.KEYS)
    }

    // --- Gestures ---------------------------------------------------------------------------------------------

    private fun gesture(gesture: GestureKind) {
        // A double tap on the watch clicks, as it does with the pointer (the temples' double tap is the host's back).
        val g = if (gesture == GestureKind.DOUBLE_CLICK) GestureKind.CLICK else gesture
        when (top()) {
            P_PLAN -> planGesture(g)
            P_SEARCH -> searchGesture(g)
            P_RESULTS -> resultsGesture(g)
            P_JOURNEY -> journeyGesture(g)
            P_BOARD -> boardGesture(g)
            P_TRIP -> tripGesture(g)
            P_NEAR -> nearGesture(g)
            P_FORMATION -> formationGesture(g)
        }
    }

    // --- Every minute anew ----------------------------------------------------------------------------------------

    private fun updateRefresh() {
        val want = visible && when (top()) {
            P_RESULTS -> search != null
            P_JOURNEY -> chosen != null
            P_BOARD -> boardPlace != null
            else -> false
        }
        if (want == refreshing) return
        refreshing = want
        if (want) ui.timer(T_REFRESH, REFRESH_MS, repeat = true) else ui.cancelTimer(T_REFRESH)
    }

    private fun refresh() {
        when (top()) {
            P_RESULTS -> refreshList()
            P_JOURNEY -> refreshChosen()
            P_BOARD -> if (!boardLoading && !boardMore && departures.isNotEmpty()) loadBoard(silent = true)
            P_FORMATION -> refreshFormation()
        }
    }

    /** The delays and platforms of the connections shown, asked for from the first one on; the list stays as it is. */
    private fun refreshList() {
        val s = search?.takeIf { it.origin != null && !connLoading && !loadingEarlier && !loadingLater } ?: return
        val first = connections.firstOrNull() ?: return run(s)
        val no = ++moreNo
        fetchConnections(s.copy(at = (first.from.departure!! - 60) * 1000, arrival = false), W_REFRESH) { got, _ ->
            if (no != moreNo || got == null) return@fetchConnections
            connections = connections.map { old -> got.firstOrNull { it.sameAs(old) } ?: old }
            chosen = chosen?.let { c -> connections.firstOrNull { it.sameAs(c) } ?: c }
            render()
        }
    }

    // --- The app menu -------------------------------------------------------------------------------------------

    private fun updateMenu() {
        val items = when (top()) {
            P_RESULTS -> listOfNotNull(
                REFRESH,
                MenuItem(M_RETURN, "Rückfahrt").takeIf { search?.from != null },
                MenuItem(M_EARLIER, "Frühere Verbindungen"),
                MenuItem(M_LATER, "Spätere Verbindungen"),
            )
            P_JOURNEY -> listOfNotNull(
                REFRESH,
                MenuItem(M_STOPS, if (openRides.isEmpty()) "Alle Zwischenhalte zeigen" else "Zwischenhalte ausblenden"),
                SET_UP.takeIf { key == null },
            )
            P_TRIP -> listOfNotNull(SET_UP.takeIf { key == null })
            P_FORMATION -> listOfNotNull(
                REFRESH.takeIf { key != null && formationOf != null },
                MenuItem(M_KEY, if (key == null) "Schlüssel eingeben" else "Schlüssel ändern"),
            )
            P_BOARD -> listOfNotNull(REFRESH, MenuItem(M_ROUTE, "Verbindung ab hier").takeIf { boardPlace != null })
            P_NEAR -> listOf(MenuItem(M_LOCATE, "Standort neu suchen"))
            P_SEARCH -> listOf(MenuItem(M_WATCH, "Auf der Uhr eingeben"))
            P_PLAN -> listOfNotNull(
                MenuItem(M_SWAP, "Von und Nach tauschen").takeIf { from != null && to != null },
                MenuItem(M_HERE, "Von: $HERE").takeIf { from != null },
                MenuItem(M_KIND, if (arrival) "Zeit gilt für die Abfahrt" else "Zeit gilt für die Ankunft"),
                MenuItem(M_CLEAR, "Verlauf löschen").takeIf { routes.isNotEmpty() || places.isNotEmpty() },
            )
            else -> emptyList()
        }
        if (items == shownMenu) return
        shownMenu = items
        ui.menu(items)
    }

    private fun menu(item: String) {
        when (item) {
            M_REFRESH -> refresh()
            M_RETURN -> returnTrip()
            M_EARLIER -> more(earlier = true)
            M_LATER -> more(earlier = false)
            M_STOPS -> chosen?.let { c ->
                if (openRides.isEmpty()) c.legs.indices.filter { c.legs[it].line != null }.forEach { openRides += it } else openRides.clear()
                journeySelected = journeySelected.coerceAtMost(journeyLines(c).size - 1)
                render()
            }
            M_ROUTE -> routeFromBoard()
            M_LOCATE -> {
                fix = null
                locateNearby()
            }
            M_WATCH -> field?.let { ask(it) }
            M_SET_UP -> openFormation(null)
            M_KEY -> askKey()
            M_SWAP -> swap()
            M_HERE -> {
                from = null
                save()
                render()
            }
            M_KIND -> {
                arrival = !arrival
                render()
            }
            M_CLEAR -> {
                routes = emptyList()
                places = emptyList()
                save()
                planFocus = planFocus.coerceAtMost(PLAN_FIXED + planRows().size - 1)
                render()
                ui.toast("Verlauf gelöscht", NOTE_MS)
            }
        }
    }

    // --- The store ------------------------------------------------------------------------------------------------

    private fun load() {
        val store = ui.storage
        from = Place.fromJson(store.get(K_FROM))
        to = Place.fromJson(store.get(K_TO))
        routes = (store.get(K_ROUTES) as? JsonArray).orEmpty().mapNotNull { Route.fromJson(it) }.take(MAX_ROUTES)
        places = (store.get(K_PLACES) as? JsonArray).orEmpty().mapNotNull { Place.fromJson(it) }.take(MAX_PLACES)
    }

    private fun save() {
        val store = ui.storage
        store.set(K_FROM, from?.toJson() ?: JsonNull)
        store.set(K_TO, to?.toJson() ?: JsonNull)
        store.set(K_ROUTES, JsonArray(routes.map { it.toJson() }))
        store.set(K_PLACES, JsonArray(places.map { it.toJson() }))
    }

    private fun problem(r: HttpResult) = r.error ?: "HTTP ${r.status}"

    private fun delay(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { "+$it" }

    private fun platformText(s: Stop, line: Line): String? = platformText(s.platform, s.newPlatform, line)

    /** "Gl. 15", "Kante I": the platform as it is now (a changed one is framed on the picture). */
    private fun platformText(planned: String?, changed: String?, line: Line): String? = (changed ?: planned)?.let { "${line.platformWord} $it" }

    /** What a connection search is: [origin] is what goes to the timetable for [from], the position for "Aktueller Standort". */
    private data class Search(val from: Place?, val to: Place, val at: Long?, val arrival: Boolean, val origin: String? = null)

    private enum class Field { FROM, TO, BOARD }

    private enum class Wait { SEARCH, NEAR }

    /** A train whose formation is asked for, boarded at [stopName] ([stopId]); [id] is "SBBP/2162/2026-11-02". */
    private data class FormationTarget(
        val id: String,
        val evu: String,
        val train: String,
        val date: LocalDate,
        val line: Line,
        val stopId: String?,
        val stopName: String,
    )

    /** What the service said about a train, and when. */
    private class FormationEntry {
        var stops: List<StopFormation>? = null
        var error: String? = null
        var loading = false
        var at = 0L
    }

    /** A search kept for Planen: from null is "Aktueller Standort". */
    data class Route(val from: Place?, val to: Place) {
        val key: String get() = "${from?.let { it.id ?: it.name } ?: "@"}|${to.id ?: to.name}"

        fun toJson(): JsonObject = buildJsonObject {
            put("von", from?.toJson() ?: JsonNull)
            put("nach", to.toJson())
        }

        companion object {
            fun fromJson(e: JsonElement?): Route? {
                val o = e as? JsonObject ?: return null
                val to = Place.fromJson(o["nach"]) ?: return null
                return Route(Place.fromJson(o["von"]), to)
            }
        }
    }

    companion object {
        const val ID = "ch.madtreasures.fahrplan"
        const val VERSION = "1.2.0"

        /** What "from" says when it is the watch's position, as in the SBB app. */
        const val HERE = "Aktueller Standort"

        const val P_PLAN = "start"
        const val P_SEARCH = "suche"
        const val P_RESULTS = "verbindungen"
        const val P_JOURNEY = "reise"
        const val P_BOARD = "abfahrten"
        const val P_TRIP = "fahrt"
        const val P_NEAR = "naehe"
        const val P_FORMATION = "zugformation"

        /** The pages and the names the header shows beside "Fahrplan". */
        val PAGES = listOf(
            P_PLAN to "Planen",
            P_SEARCH to "Haltestelle",
            P_RESULTS to "Verbindungen",
            P_JOURNEY to "Ihre Reise",
            P_BOARD to "Abfahrten",
            P_TRIP to "Fahrt",
            P_NEAR to "In der Nähe",
            P_FORMATION to "Zugformation",
        )

        /** The image block of a page. */
        fun image(page: String) = "${page}_bild"

        /** On Planen: from, to, swap, when, then the rows. */
        const val PLAN_FIXED = 4

        const val M_REFRESH = "m_aktualisieren"
        const val M_RETURN = "m_rueckfahrt"
        const val M_EARLIER = "m_frueher"
        const val M_LATER = "m_spaeter"
        const val M_STOPS = "m_halte"
        const val M_ROUTE = "m_ab_hier"
        const val M_LOCATE = "m_standort"
        const val M_WATCH = "m_uhr"
        const val M_SWAP = "m_tauschen"
        const val M_HERE = "m_hier"
        const val M_KIND = "m_ankunft"
        const val M_CLEAR = "m_verlauf"
        const val M_SET_UP = "m_formation"
        const val M_KEY = "m_schluessel"

        const val ASK_FROM = "von"
        const val ASK_TO = "nach"
        const val ASK_WHEN = "wann"
        const val ASK_BOARD = "tafel"
        const val ASK_KEY = "schluessel"

        const val T_REFRESH = "aktualisieren"
        const val T_FIX = "standort"
        const val T_TYPE = "tippen"

        const val REFRESH_MS = 60_000L
        const val FIX_TIMEOUT_MS = 30_000L
        const val FIX_FRESH_MS = 120_000L
        const val FIX_RATE_S = 2

        /** After the last key, so that typing on is not slowed by a request per letter. */
        const val TYPE_MS = 350L
        const val MIN_TYPED = 2

        const val OFFLINE = "Der Fahrplan ist gerade nicht erreichbar. Ist die Uhr im Netz?"
        const val UNREADABLE = "Die Antwort des Fahrplans war unlesbar."
        const val NO_FIX = "Die Uhr findet ihren Standort nicht. Ist er für Fahrplan erlaubt? Sonst bei „Von“ eine Haltestelle wählen."

        val WHEN_SUGGESTIONS = listOf("jetzt", "in 15 Min.", "an 18:00", "morgen 7:00", "morgen 17:00")

        /** The file in the app's folder with the key for the Train Formation Service. */
        const val KEY_FILE = "zugformation.txt"

        /** A formation is asked for again after this long at the most (it rarely changes in a day). */
        const val FORMATION_FRESH_MS = 10 * 60_000L

        const val SETUP = "Für die Zugformation braucht die App einen kostenlosen Schlüssel von opentransportdata.swiss " +
            "(API-Manager, Train Formation Service). Lege ihn als zugformation.txt in den Ordner der App, oder tippe hier, um ihn einzugeben."
        const val KEY_SAVED = "Der Schlüssel ist gespeichert. Die Zugformation steht jetzt in Ihre Reise und auf der Fahrt eines Zuges."
        const val BAD_KEY = "Der Schlüssel für die Zugformation gilt nicht. Im App-Menü lässt er sich ändern."
        const val NO_FORMATION = "Für diesen Zug gibt es heute keine Zugformation."
        const val TOO_MANY = "Der Dienst für die Zugformation nimmt gerade keine Abfragen an. Bitte etwas später."

        private const val W_RESULTS = "verbindungen"
        private const val W_EARLIER = "frueher"
        private const val W_LATER = "spaeter"
        private const val W_REFRESH = "aktualisieren"
        private const val W_CHOSEN = "verbindung"
        private const val W_BOARD = "abfahrten"

        private const val K_FROM = "von"
        private const val K_TO = "nach"
        private const val K_ROUTES = "zuletzt"
        private const val K_PLACES = "orte"
        private const val K_KEY = "zugformation_schluessel"

        private const val SHOWN_ROUTES = 4
        private const val MAX_ROUTES = 8
        private const val MAX_PLACES = 12
        private const val MAX_CONNECTIONS = 20
        private const val MAX_DEPARTURES = 45
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_SUGGESTION = 40
        private const val MAX_QUERY = 80
        private const val NOTE_MS = 3_000

        private val REFRESH = MenuItem(M_REFRESH, "Aktualisieren")
        private val SET_UP = MenuItem(M_SET_UP, "Zugformation einrichten")
        private val SPACES = Regex("\\s+")

        private fun fontPath(name: String) = "apps/$ID/fonts/$name.font"

        /** Whether two names mean the same stop: case, accents and punctuation aside ("zuerich hb" is "Zürich HB"). */
        fun same(a: String, b: String): Boolean = key(a) == key(b)

        private fun key(s: String): String {
            val umlauts = s.lowercase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            val plain = Normalizer.normalize(umlauts, Normalizer.Form.NFD).replace(MARKS, "")
            return plain.filter { it.isLetterOrDigit() }
        }

        private val MARKS = Regex("\\p{M}+")
    }
}

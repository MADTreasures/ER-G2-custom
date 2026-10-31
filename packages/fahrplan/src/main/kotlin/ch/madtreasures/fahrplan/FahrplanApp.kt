package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The Swiss timetable on the glasses (README.md of the package): connections from a stop – or from where the watch
 * is – to another, now or at a time, with platforms, delays, changes and the stops on the way, and the departures
 * of a stop, also of the stops near the watch. The data comes from transport.opendata.ch ([Timetable]).
 *
 * Names and times are typed on the watch, or with a Bluetooth keyboard on the glasses ([AppContext.askText]); a
 * name becomes a stop through the timetable's search, and the stops used last come as suggestions. "Mein Standort"
 * is the watch's GPS, asked for only while it is needed.
 *
 * Pages: the start ([P_START]: from, to, when, the searches used last, departures), the choice among stops of a
 * similar name ([P_PICK]), the connections ([P_RESULTS]), one connection ([P_DETAIL]) and the stops of one of its
 * rides ([P_STOPS]), the departures of a stop ([P_BOARD]) and the stops nearby ([P_NEAR]). While connections or
 * departures show, they are asked for again every minute, for the delays; a connection that has been chosen stays
 * on its page even when it has left, so that it can be followed on the way.
 */
class FahrplanApp(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : G2App {
    override val manifest = AppManifest(
        id = ID,
        name = "Fahrplan",
        version = VERSION,
        permissions = setOf(Permission.NETWORK, Permission.LOCATION),
        description = "Verbindungen und Abfahrten im ganzen Schweizer ÖV, mit Gleis, Verspätung und Umsteigen; eingegeben auf der Uhr oder mit einer Tastatur.",
    )

    private lateinit var ui: AppContext
    private val shown = Shown()

    /** The pages as the host keeps them, oldest first: show puts one on top, replace swaps it, back takes it off. */
    private val stack = ArrayList<String>()
    private var visible = false
    private var shownMenu: List<MenuItem>? = null
    private var refreshing = false
    private var locating = false

    // What to search: from null is "Mein Standort", at null is now.
    private var from: Place? = null
    private var to: Place? = null
    private var at: Long? = null
    private var arrival = false

    /** The connections are searched as soon as the destination is chosen. */
    private var searchAfter = false

    // Kept in the app's store.
    private var routes: List<Route> = emptyList()
    private var places: List<Place> = emptyList()

    // The watch's position, and when it came (by the app's clock, whatever the GPS says of its time).
    private var fix: AppEvent.Location? = null
    private var fixAt = 0L
    private var waiting: Wait? = null

    // A name typed for a stop: being looked up ([lookingUp]), then chosen from [candidates] on the pick page.
    private var lookingUp: Field? = null
    private var picking: Field? = null
    private var typed = ""
    private var candidates: List<Place> = emptyList()
    private var lookupError: String? = null
    private var lookupNo = 0

    // Connections.
    private var search: Search? = null
    private var connections: List<Connection> = emptyList()
    private var connLoading = false
    private var connError: String? = null
    private var connNo = 0
    private var chosen: Connection? = null
    private var chosenNo = 0
    private var leg = 0

    // Departures.
    private var boardPlace: Place? = null
    private var boardAt: Long? = null
    private var departures: List<Departure> = emptyList()
    private var boardLoading = false
    private var boardError: String? = null
    private var boardNo = 0

    // Stops nearby.
    private var nearby: List<Place> = emptyList()
    private var nearLoading = false
    private var nearError: String? = null
    private var nearNo = 0

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
            is AppEvent.Click -> click(event.block)
            is AppEvent.Toggle -> if (event.block == ST_ARRIVAL) {
                arrival = event.on
                render()
            }
            is AppEvent.Navigate -> {
                if (stack.lastOrNull() != event.to) stack += event.to
                pageChanged()
            }
            is AppEvent.Back -> {
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull() != P_PICK) lookingUp = null
                pageChanged()
            }
            is AppEvent.Menu -> menu(event.item)
            is AppEvent.TextInput -> answer(event.tag, event.text)
            is AppEvent.Location -> onFix(event)
            is AppEvent.Timer -> when (event.tag) {
                T_REFRESH -> refresh()
                T_FIX -> noFix()
            }
            else -> Unit
        }
    }

    private fun start() {
        load()
        render()
        show(P_START)
    }

    private fun now(): ZonedDateTime = Instant.ofEpochMilli(clock()).atZone(zone)

    // --- Pages --------------------------------------------------------------------------------------------

    /** All pages from the state; what changed goes to the glasses, and the menu, the timer and the GPS follow. */
    private fun render() {
        shown.sync(ui, listOf(startPage(), pickPage(), resultsPage(), detailPage(), stopsPage(), boardPage(), nearPage()))
        pageChanged()
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
        updateMenu()
        updateRefresh()
        updateLocating()
    }

    private fun startPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Button(ST_FROM, "Von: " + fieldText(Field.FROM))
        blocks += Block.Button(ST_TO, "Nach: " + fieldText(Field.TO))
        blocks += Block.Button(ST_WHEN, "Wann: " + When.label(at, now()))
        blocks += Block.Toggle(ST_ARRIVAL, "Zeit gilt für die Ankunft", arrival)
        blocks += Block.Button(ST_SEARCH, "Verbindungen suchen")
        val recent = routes.take(SHOWN_ROUTES)
        if (recent.isNotEmpty()) {
            blocks += Block.Divider(ST_LINE)
            blocks += Block.Text(ST_RECENT, "Zuletzt gesucht")
            recent.forEachIndexed { i, r -> blocks += Block.Button("$ST_ROUTE$i", "${r.from?.name ?: HERE} → ${r.to.name}") }
        }
        blocks += Block.Divider(ST_LINE2)
        blocks += Block.Button(ST_NEAR, "Abfahrten in der Nähe")
        blocks += Block.Button(ST_BOARD, "Abfahrten einer Haltestelle")
        return Page(P_START, "Verbindung", blocks)
    }

    private fun fieldText(field: Field): String = when {
        lookingUp == field -> "suche „$typed“ …"
        field == Field.FROM -> from?.name ?: HERE
        else -> to?.name ?: "bitte wählen"
    }

    private fun pickPage(): Page {
        val blocks = ArrayList<Block>()
        val question = when (picking) {
            Field.FROM -> "Von welcher Haltestelle?"
            Field.TO -> "Zu welcher Haltestelle?"
            Field.BOARD, null -> "Abfahrten welcher Haltestelle?"
        }
        blocks += Block.Text(PI_INFO, lookupError ?: question)
        if (picking == Field.FROM) blocks += Block.Button(PI_HERE, HERE)
        candidates.forEachIndexed { i, p -> blocks += Block.Button("$PI_PLACE$i", p.name) }
        if (lookupError == OFFLINE) blocks += Block.Button(PI_RETRY, "Nochmals")
        blocks += Block.Button(PI_AGAIN, "Anders eingeben")
        return Page(P_PICK, "Haltestelle", blocks)
    }

    /**
     * While the connections load, the page has nothing to click: when they come, the host then puts the focus on
     * the first one in view, at the top of the page, instead of keeping it on a button that moved down.
     */
    private fun resultsPage(): Page {
        val s = search
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(VE_ROUTE, if (s == null) "Verbindungen" else "${s.from?.name ?: HERE} → ${s.to.name}")
        blocks += Block.Text(VE_INFO, resultsInfo())
        if (connections.isNotEmpty()) {
            blocks += Block.Button(VE_EARLIER, "Frühere")
            connections.forEachIndexed { i, c -> blocks += Block.Button("$VE_CONN$i", Format.row(c, zone)) }
            blocks += Block.Button(VE_LATER, "Spätere")
            if (s?.from != null) blocks += Block.Button(VE_RETURN, "Rückfahrt")
        } else if (connError != null && !connLoading && waiting != Wait.SEARCH) {
            blocks += Block.Button(VE_AGAIN, "Nochmals")
        }
        blocks += Block.Text(VE_SOURCE, Timetable.SOURCE)
        return Page(P_RESULTS, "Verbindungen", blocks)
    }

    private fun resultsInfo(): String {
        val list = connections
        val error = connError
        return when {
            waiting == Wait.SEARCH -> "Suche den Standort …"
            list.isEmpty() && connLoading -> "Suche Verbindungen …"
            list.isEmpty() -> error ?: ""
            else -> {
                val first = list.first().from.departure!!
                val last = list.last().from.departure!!
                val day = Format.capital(Format.day(first, now().toLocalDate(), zone))
                val window = if (list.size == 1) "Abfahrt ${Format.clock(first, zone)}" else "Abfahrten ${Format.clock(first, zone)} bis ${Format.clock(last, zone)}"
                "$day · $window" + if (error != null) " · nicht aktuell" else ""
            }
        }
    }

    private fun detailPage(): Page {
        val c = chosen ?: return Page(P_DETAIL, "Verbindung", listOf(Block.Text(DE_NONE, "Keine Verbindung gewählt.")))
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(DE_TIMES, Format.times(c, zone))
        blocks += Block.Text(DE_INFO, "${Format.duration(c.minutes)} · ${Format.transfers(c.transfers)}")
        var previous: Leg? = null
        c.legs.forEachIndexed { i, l ->
            val line = l.line
            if (line == null) {
                blocks += Block.Text("$DE_WALK$i", Format.walk(l))
            } else {
                val before = previous
                val arrived = before?.arrival?.arrival
                val leaves = l.departure.departure
                if (before?.line != null && arrived != null && leaves != null) {
                    blocks += Block.Text("$DE_CHANGE$i", "${((leaves - arrived) / 60).coerceAtLeast(0)} Min. zum Umsteigen")
                }
                blocks += Block.Value("$DE_DEP$i", Format.stop(l.departure.departure, l.departure, zone), Format.platform(l.departure, line))
                blocks += Block.Button("$DE_LEG$i", "${line.label} nach ${line.direction}")
                blocks += Block.Value("$DE_ARR$i", Format.stop(l.arrival.arrival, l.arrival, zone), Format.platform(l.arrival, line))
            }
            previous = l
        }
        blocks += Block.Text(DE_SOURCE, Timetable.SOURCE)
        return Page(P_DETAIL, "Verbindung", blocks)
    }

    private fun stopsPage(): Page {
        val l = chosen?.legs?.getOrNull(leg)
        val line = l?.line ?: return Page(P_STOPS, "Halte", listOf(Block.Text(HA_NONE, "Keine Fahrt gewählt.")))
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(HA_TITLE, "${line.label} nach ${line.direction}")
        l.stops.forEachIndexed { i, s ->
            val time = if (i == 0) s.departure ?: s.arrival else s.arrival ?: s.departure
            blocks += Block.Value("$HA_STOP$i", Format.stop(time, s, zone), Format.platform(s, line))
        }
        return Page(P_STOPS, "Halte", blocks)
    }

    private fun boardPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(AB_STATION, boardPlace?.name ?: "Abfahrten")
        blocks += Block.Text(AB_INFO, boardInfo())
        departures.forEachIndexed { i, d ->
            val label = "${Format.clock(d.time, zone)}${Format.delay(d.delay)} ${d.line.label} ${d.line.direction}"
            blocks += Block.Value("$AB_DEP$i", label, Format.platform(d.platform, d.newPlatform, d.line))
        }
        // As on the connections: nothing to click until the departures are there.
        if (departures.isNotEmpty()) {
            blocks += Block.Button(AB_LATER, "Spätere")
            if (boardPlace != null) blocks += Block.Button(AB_ROUTE, "Verbindung ab hier")
        } else if (boardError != null && !boardLoading) {
            blocks += Block.Button(AB_AGAIN, "Nochmals")
        }
        blocks += Block.Text(AB_SOURCE, Timetable.SOURCE)
        return Page(P_BOARD, "Abfahrten", blocks)
    }

    private fun boardInfo(): String {
        val first = departures.firstOrNull()
        val error = boardError
        return when {
            first == null && boardLoading -> "Lade die Abfahrten …"
            first == null -> error ?: ""
            else -> Format.capital(Format.day(first.time, now().toLocalDate(), zone)) + " ab ${Format.clock(first.time, zone)}" +
                if (error != null) " · nicht aktuell" else ""
        }
    }

    private fun nearPage(): Page {
        val blocks = ArrayList<Block>()
        val info = when {
            waiting == Wait.NEAR -> "Suche den Standort …"
            nearLoading -> "Suche Haltestellen in der Nähe …"
            nearError != null -> nearError!!
            else -> "Antippen zeigt die Abfahrten."
        }
        blocks += Block.Text(NA_INFO, info)
        nearby.forEachIndexed { i, p -> blocks += Block.Button("$NA_PLACE$i", "${p.name} · ${Format.distance(p.distance)}") }
        if (nearError != null && !nearLoading && waiting == null) blocks += Block.Button(NA_AGAIN, "Nochmals")
        blocks += Block.Text(NA_SOURCE, Timetable.SOURCE)
        return Page(P_NEAR, "In der Nähe", blocks)
    }

    // --- Clicks and answers -------------------------------------------------------------------------------

    private fun click(block: String) {
        when {
            block == ST_FROM -> ask(Field.FROM)
            block == ST_TO -> ask(Field.TO)
            block == ST_WHEN -> ui.askText(ASK_WHEN, "Wann? Etwa 17:30, morgen 7:15, in 20 Min.", WHEN_SUGGESTIONS)
            block == ST_SEARCH -> searchNow()
            block.startsWith(ST_ROUTE) -> index(block, ST_ROUTE)?.let { routes.getOrNull(it) }?.let { r ->
                from = r.from
                to = r.to
                at = null
                arrival = false
                save()
                searchNow()
            }
            block == ST_NEAR -> openNearby()
            block == ST_BOARD -> ask(Field.BOARD)
            block == PI_HERE -> {
                from = null
                save()
                render()
                leavePick()
            }
            block.startsWith(PI_PLACE) -> index(block, PI_PLACE)?.let { candidates.getOrNull(it) }?.let { p -> picking?.let { chose(it, p) } }
            block == PI_RETRY -> picking?.let { lookup(it, typed) }
            block == PI_AGAIN -> picking?.let { ask(it) }
            block == VE_EARLIER -> shift(earlier = true)
            block == VE_LATER -> shift(earlier = false)
            block == VE_AGAIN -> search?.let { run(it) }
            block == VE_RETURN -> returnTrip()
            block.startsWith(VE_CONN) -> index(block, VE_CONN)?.let { connections.getOrNull(it) }?.let { c ->
                chosen = c
                render()
                show(P_DETAIL)
            }
            block.startsWith(DE_LEG) -> index(block, DE_LEG)?.let { i ->
                leg = i
                render()
                show(P_STOPS)
            }
            block == AB_LATER -> departures.lastOrNull()?.let {
                boardAt = (it.time + 60) * 1000
                loadBoard()
            }
            block == AB_AGAIN -> loadBoard()
            block == AB_ROUTE -> routeFromBoard()
            block.startsWith(NA_PLACE) -> index(block, NA_PLACE)?.let { nearby.getOrNull(it) }?.let { openBoard(it) }
            block == NA_AGAIN -> locateNearby()
        }
    }

    private fun index(block: String, prefix: String): Int? = block.removePrefix(prefix).toIntOrNull()

    private fun ask(field: Field) {
        val names = places.map { it.name.take(MAX_SUGGESTION) }
        when (field) {
            Field.FROM -> ui.askText(ASK_FROM, "Von wo?", (listOf(HERE) + names).distinct().take(MAX_SUGGESTIONS))
            Field.TO -> ui.askText(ASK_TO, "Wohin?", names.filter { it != from?.name }.take(MAX_SUGGESTIONS))
            Field.BOARD -> ui.askText(ASK_BOARD, "Abfahrten von wo?", names.take(MAX_SUGGESTIONS))
        }
    }

    private fun answer(tag: String, text: String?) {
        val t = text?.trim()?.replace(SPACES, " ").orEmpty().take(MAX_QUERY)
        if (t.isEmpty()) {
            if (tag == ASK_TO) searchAfter = false
            return
        }
        when (tag) {
            ASK_FROM -> if (same(t, HERE)) {
                from = null
                save()
                render()
            } else {
                lookup(Field.FROM, t)
            }
            ASK_TO -> lookup(Field.TO, t)
            ASK_BOARD -> lookup(Field.BOARD, t)
            ASK_WHEN -> when (val a = When.parse(t, now())) {
                null -> ui.toast("„$t“ verstehe ich nicht. Etwa 17:30, morgen 7:15 oder in 20 Min.", NOTE_MS)
                When.Answer.Now -> {
                    at = null
                    render()
                }
                is When.Answer.At -> {
                    at = a.ms
                    render()
                }
            }
        }
    }

    // --- Stops by name ------------------------------------------------------------------------------------

    /** The stop the wearer means by [text]: one used before, the only fitting one, or a choice on the pick page. */
    private fun lookup(field: Field, text: String) {
        places.firstOrNull { same(it.name, text) }?.let {
            chose(field, it)
            return
        }
        lookingUp = field
        typed = text
        lookupError = null
        val no = ++lookupNo
        render()
        ui.fetch(HttpRequest(Timetable.placesUrl(text))) { r ->
            if (no != lookupNo || lookingUp != field) return@fetch
            lookingUp = null
            val found = if (r.ok) Timetable.places(r.body) else null
            when {
                found == null -> {
                    ui.log("Haltestellen: ${problem(r)}")
                    candidates = emptyList()
                    lookupError = OFFLINE
                    openPick(field)
                }
                found.isEmpty() -> {
                    candidates = emptyList()
                    lookupError = "Keine Haltestelle „$text“ gefunden."
                    openPick(field)
                }
                same(found.first().name, text) -> chose(field, found.first())
                else -> {
                    candidates = found
                    lookupError = null
                    openPick(field)
                }
            }
        }
    }

    private fun openPick(field: Field) {
        picking = field
        render()
        if (top() != P_PICK) show(P_PICK)
    }

    private fun leavePick() {
        if (top() == P_PICK) replace(P_START)
    }

    private fun chose(field: Field, place: Place) {
        lookingUp = null
        remember(place)
        when (field) {
            Field.FROM -> {
                from = place
                save()
                render()
                leavePick()
            }
            Field.TO -> {
                to = place
                save()
                render()
                leavePick()
                if (searchAfter) searchNow()
            }
            Field.BOARD -> {
                save()
                openBoard(place)
            }
        }
    }

    private fun remember(place: Place) {
        val p = place.copy(distance = null)
        places = (listOf(p) + places.filter { !same(it.name, p.name) }).take(MAX_PLACES)
    }

    // --- Connections --------------------------------------------------------------------------------------

    private fun searchNow() {
        val target = to ?: run {
            searchAfter = true
            ask(Field.TO)
            return
        }
        searchAfter = false
        val start = from
        if (start != null && (start.id != null && start.id == target.id || same(start.name, target.name))) {
            ui.toast("Von und Nach sind dieselbe Haltestelle.", NOTE_MS)
            return
        }
        routes = (listOf(Route(start, target)) + routes.filter { it.key != Route(start, target).key }).take(MAX_ROUTES)
        save()
        chosen = null
        run(Search(start, target, at, arrival))
        if (top() != P_RESULTS) show(P_RESULTS)
    }

    /** Searches [s] and shows its connections; [silent]: a refresh, the old ones stay until the new ones are there. */
    private fun run(s: Search, silent: Boolean = false) {
        val origin = s.origin ?: s.from?.let { it.id ?: it.name } ?: here()
        if (!silent) {
            connections = emptyList()
            connError = null
        }
        if (origin == null) {
            search = s
            connLoading = false
            ++connNo
            waitFor(Wait.SEARCH)
            render()
            return
        }
        val active = s.copy(origin = origin)
        search = active
        connLoading = true
        val no = ++connNo
        render()
        fetchConnections(active, W_RESULTS) { list, error ->
            if (no != connNo) return@fetchConnections
            connLoading = false
            if (list != null) {
                connections = list
                connError = if (list.isEmpty()) "Keine Verbindung gefunden." else null
                chosen = chosen?.let { c -> list.firstOrNull { it.sameAs(c) } ?: c }
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

    /** Earlier: those arriving before the first shown; later: those leaving after the last. */
    private fun shift(earlier: Boolean) {
        val s = search ?: return
        val list = connections.takeIf { it.isNotEmpty() } ?: return
        run(
            if (earlier) {
                s.copy(at = (list.first().to.arrival!! - 60) * 1000, arrival = true)
            } else {
                s.copy(at = (list.last().from.departure!! + 60) * 1000, arrival = false)
            },
        )
    }

    /** Back from where the connections go to where they came from; offered on the connections only. */
    private fun returnTrip() {
        val s = search ?: return
        val back = s.from ?: return
        from = s.to
        to = back
        at = null
        arrival = false
        searchNow()
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
                render()
            }
        }
    }

    // --- Departures ---------------------------------------------------------------------------------------

    private fun openBoard(place: Place) {
        boardPlace = place
        boardAt = null
        loadBoard()
        when (top()) {
            P_BOARD -> Unit
            P_PICK -> replace(P_BOARD)
            else -> show(P_BOARD)
        }
    }

    private fun loadBoard(silent: Boolean = false) {
        val place = boardPlace ?: return
        if (!silent) {
            departures = emptyList()
            boardError = null
        }
        boardLoading = true
        val no = ++boardNo
        render()
        val time = Instant.ofEpochMilli(boardAt ?: clock()).atZone(zone)
        ui.fetch(HttpRequest(Timetable.boardUrl(place, time))) { r ->
            if (no != boardNo) return@fetch
            if (!r.ok) {
                ui.log("Abfahrten: ${problem(r)}")
                boardLoading = false
                boardError = OFFLINE
                render()
                return@fetch
            }
            ui.work(W_BOARD, { Timetable.board(r.body) }) { result ->
                if (no != boardNo) return@work
                boardLoading = false
                result.onSuccess { b ->
                    departures = b.departures
                    boardError = if (b.departures.isEmpty()) "Keine Abfahrten gefunden." else null
                }.onFailure {
                    ui.log("Abfahrten unlesbar: ${it.message}")
                    boardError = UNREADABLE
                }
                render()
            }
        }
    }

    private fun routeFromBoard() {
        val place = boardPlace ?: return
        from = place
        save()
        render()
        show(P_START)
    }

    // --- Where the watch is -------------------------------------------------------------------------------

    /** The position for a connection, if the watch knows a recent one. */
    private fun here(): String? = fresh()?.let { Timetable.position(it.lat, it.lon) }

    private fun fresh(): AppEvent.Location? = fix?.takeIf { clock() - fixAt <= FIX_FRESH_MS }

    private fun openNearby() {
        nearby = emptyList()
        nearError = null
        render()
        show(P_NEAR)
        locateNearby()
    }

    private fun locateNearby() {
        val f = fresh()
        if (f == null) {
            nearLoading = false
            nearError = null
            waitFor(Wait.NEAR)
            render()
            return
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
     * The GPS runs only while a position is awaited, and on the start page with "Mein Standort" until one is there,
     * so that it is ready for the search.
     */
    private fun updateLocating() {
        val ahead = top() == P_START && from == null && fresh() == null
        val want = visible && (waiting != null || ahead)
        if (want == locating) return
        locating = want
        if (want) ui.subscribe(Sensor.LOCATION, FIX_RATE_S) else ui.unsubscribe(Sensor.LOCATION)
    }

    // --- Every minute anew ----------------------------------------------------------------------------------

    private fun updateRefresh() {
        val want = visible && when (top()) {
            P_RESULTS -> search != null
            P_DETAIL, P_STOPS -> chosen != null
            P_BOARD -> boardPlace != null
            else -> false
        }
        if (want == refreshing) return
        refreshing = want
        if (want) ui.timer(T_REFRESH, REFRESH_MS, repeat = true) else ui.cancelTimer(T_REFRESH)
    }

    private fun refresh() {
        when (top()) {
            P_RESULTS -> search?.takeIf { !connLoading && waiting == null }?.let { run(it, silent = true) }
            P_DETAIL, P_STOPS -> refreshChosen()
            P_BOARD -> if (!boardLoading) loadBoard(silent = true)
        }
    }

    // --- The app menu ---------------------------------------------------------------------------------------

    private fun updateMenu() {
        val items = when (top()) {
            P_RESULTS -> listOfNotNull(REFRESH, MenuItem(M_RETURN, "Rückfahrt").takeIf { search?.from != null })
            P_DETAIL, P_STOPS -> listOf(REFRESH)
            P_BOARD -> listOfNotNull(REFRESH, MenuItem(M_ROUTE, "Verbindung ab hier").takeIf { boardPlace != null })
            P_NEAR -> listOf(MenuItem(M_LOCATE, "Standort neu suchen"))
            else -> listOfNotNull(
                MenuItem(M_SWAP, "Von und Nach tauschen").takeIf { from != null && to != null },
                MenuItem(M_HERE, "Von: $HERE").takeIf { from != null },
                MenuItem(M_CLEAR, "Verlauf löschen").takeIf { routes.isNotEmpty() || places.isNotEmpty() },
            )
        }
        if (items == shownMenu) return
        shownMenu = items
        ui.menu(items)
    }

    private fun menu(item: String) {
        when (item) {
            M_REFRESH -> refresh()
            M_RETURN -> returnTrip()
            M_ROUTE -> routeFromBoard()
            M_LOCATE -> {
                fix = null
                locateNearby()
            }
            M_SWAP -> {
                val a = from ?: return
                val b = to ?: return
                from = b
                to = a
                save()
                render()
            }
            M_HERE -> {
                from = null
                save()
                render()
            }
            M_CLEAR -> {
                routes = emptyList()
                places = emptyList()
                save()
                render()
                ui.toast("Verlauf gelöscht", NOTE_MS)
            }
        }
    }

    // --- The store ------------------------------------------------------------------------------------------

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

    /** What a connection search is: [origin] is what goes to the timetable for [from], the position for "Mein Standort". */
    private data class Search(val from: Place?, val to: Place, val at: Long?, val arrival: Boolean, val origin: String? = null)

    private enum class Field { FROM, TO, BOARD }

    private enum class Wait { SEARCH, NEAR }

    /** A search kept for the start page: from null is "Mein Standort". */
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
        const val VERSION = "1.0.0"

        /** What "from" says when it is the watch's position. */
        const val HERE = "Mein Standort"

        const val P_START = "start"
        const val P_PICK = "waehlen"
        const val P_RESULTS = "verbindungen"
        const val P_DETAIL = "verbindung"
        const val P_STOPS = "halte"
        const val P_BOARD = "abfahrten"
        const val P_NEAR = "naehe"

        const val ST_FROM = "st_von"
        const val ST_TO = "st_nach"
        const val ST_WHEN = "st_wann"
        const val ST_ARRIVAL = "st_ankunft"
        const val ST_SEARCH = "st_suchen"
        const val ST_LINE = "st_linie"
        const val ST_RECENT = "st_zuletzt"
        const val ST_ROUTE = "st_route_"
        const val ST_LINE2 = "st_linie2"
        const val ST_NEAR = "st_naehe"
        const val ST_BOARD = "st_tafel"

        const val PI_INFO = "pi_info"
        const val PI_HERE = "pi_hier"
        const val PI_PLACE = "pi_ort_"
        const val PI_RETRY = "pi_nochmal"
        const val PI_AGAIN = "pi_anders"

        const val VE_ROUTE = "ve_route"
        const val VE_INFO = "ve_info"
        const val VE_EARLIER = "ve_frueher"
        const val VE_CONN = "ve_v_"
        const val VE_LATER = "ve_spaeter"
        const val VE_AGAIN = "ve_nochmal"
        const val VE_RETURN = "ve_rueck"
        const val VE_SOURCE = "ve_quelle"

        const val DE_NONE = "de_keine"
        const val DE_TIMES = "de_zeiten"
        const val DE_INFO = "de_info"
        const val DE_WALK = "de_fuss_"
        const val DE_CHANGE = "de_umst_"
        const val DE_DEP = "de_ab_"
        const val DE_LEG = "de_fahrt_"
        const val DE_ARR = "de_an_"
        const val DE_SOURCE = "de_quelle"

        const val HA_NONE = "ha_keine"
        const val HA_TITLE = "ha_titel"
        const val HA_STOP = "ha_halt_"

        const val AB_STATION = "ab_halt"
        const val AB_INFO = "ab_info"
        const val AB_DEP = "ab_d_"
        const val AB_LATER = "ab_spaeter"
        const val AB_AGAIN = "ab_nochmal"
        const val AB_ROUTE = "ab_route"
        const val AB_SOURCE = "ab_quelle"

        const val NA_INFO = "na_info"
        const val NA_PLACE = "na_ort_"
        const val NA_AGAIN = "na_nochmal"
        const val NA_SOURCE = "na_quelle"

        const val M_REFRESH = "m_aktualisieren"
        const val M_RETURN = "m_rueckfahrt"
        const val M_ROUTE = "m_ab_hier"
        const val M_LOCATE = "m_standort"
        const val M_SWAP = "m_tauschen"
        const val M_HERE = "m_hier"
        const val M_CLEAR = "m_verlauf"

        const val ASK_FROM = "von"
        const val ASK_TO = "nach"
        const val ASK_WHEN = "wann"
        const val ASK_BOARD = "tafel"

        const val T_REFRESH = "aktualisieren"
        const val T_FIX = "standort"

        const val REFRESH_MS = 60_000L
        const val FIX_TIMEOUT_MS = 30_000L
        const val FIX_FRESH_MS = 120_000L
        const val FIX_RATE_S = 2

        const val OFFLINE = "Der Fahrplan ist gerade nicht erreichbar. Ist die Uhr im Netz?"
        const val UNREADABLE = "Die Antwort des Fahrplans war unlesbar."
        const val NO_FIX = "Die Uhr findet ihren Standort nicht. Ist er für Fahrplan erlaubt? Sonst bei „Von“ eine Haltestelle wählen."

        val WHEN_SUGGESTIONS = listOf("jetzt", "in 15 Min.", "in 1 h", "morgen 7:00", "morgen 17:00")

        private const val W_RESULTS = "verbindungen"
        private const val W_CHOSEN = "verbindung"
        private const val W_BOARD = "abfahrten"

        private const val K_FROM = "von"
        private const val K_TO = "nach"
        private const val K_ROUTES = "zuletzt"
        private const val K_PLACES = "orte"

        private const val SHOWN_ROUTES = 4
        private const val MAX_ROUTES = 8
        private const val MAX_PLACES = 12
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_SUGGESTION = 40
        private const val MAX_QUERY = 80
        private const val NOTE_MS = 3_000

        private val REFRESH = MenuItem(M_REFRESH, "Aktualisieren")
        private val SPACES = Regex("\\s+")

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

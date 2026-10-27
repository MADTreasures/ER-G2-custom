package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppFiles
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.DownloadState
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.Vibration
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Offline navigation on foot and by bike (README.md of the package): maps of single countries from the
 * mapsforge server on the watch, the watch's GPS, routes and turn hints on the glasses in the manner of Even's
 * own navigation. Everything heavy (pictures, routes, search, the search index) runs as background work
 * (interface 5); the app thread only keeps the state and the pages.
 *
 * Pages: the map ([P_KARTE], full screen, gestures: tap opens the menu, scrolling zooms) with the menu, the
 * route planning with start and destination, the search results and the route found; the navigation
 * ([P_NAV], tap switches between the small turning map and the whole map); the maps on the watch with the
 * catalog of all countries.
 */
class NaviApp(private val clock: () -> Long = System::currentTimeMillis) : G2App {
    override val manifest = AppManifest(
        id = ID,
        name = "Navi",
        version = VERSION,
        permissions = setOf(Permission.LOCATION, Permission.NETWORK),
        description = "Offline-Navigation zu Fuss und mit dem Velo: Länderkarten auf der Uhr, Standort vom GPS der Uhr, Abbiegehinweise auf der Brille.",
    )

    private lateinit var ui: AppContext
    private lateinit var screens: Screens
    private lateinit var store: NaviStore
    private var catalog: Catalog? = null

    // The open maps and their search indexes, replaced as a whole when maps come or go.
    private var sources: List<MapSource> = emptyList()
    private var indexes: List<SearchIndex> = emptyList()
    private var router: Router? = null
    private val retired = ArrayList<Closeable>()
    private var opened = false
    private var mapsVersion = 0

    /** The pages as the host keeps them, oldest first: show puts one on top, replace swaps it, back takes it off. */
    private val stack = ArrayList<String>()
    private var visible = false

    /** The last fix of the GPS, and the one in use (the demo trip's while it runs). */
    private var gpsFix: AppEvent.Location? = null
    private var fix: AppEvent.Location? = null
    private var savedPositionAt = 0L
    private var zoom = MAP_ZOOM
    private var navZoom = NAV_ZOOM_FOOT

    // Planning a route.
    private var profile = Profile.FOOT
    private var from: Place? = null
    private var to: Place? = null
    private var query = ""
    private var queryFor = ASK_TO
    private var results: List<Place> = emptyList()
    private var routeNote: String? = null
    private var computing = false

    // The route and the navigation along it.
    private var route: Route? = null
    private var navigator: Navigator? = null
    private var nav: NavState? = null
    private var navigating = false
    private var fullMap = false
    private var announced = -1
    private var arrivedNoted = false
    private var rerouting = false
    private var lastRerouteMs = 0L
    private var demo: DemoTrip? = null
    private var demoStartMs = 0L

    /** A map on its way to the watch: loading, being checked, waiting for or getting its search index. */
    private class Job(val region: Region, var total: Long) {
        var bytes = 0L
        var step = STEP_LOAD
        var failed: String? = null

        /** The watch reported on its download. */
        var heard = false

        /** Per mille of the search index while it is built. */
        var indexed: AtomicLong? = null
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val tagRegion = HashMap<String, String>()
    private var nextTag = 0

    /** Search indexes are built one after another (they read the whole map): the waiting ones, and the one being built. */
    private val indexQueue = ArrayDeque<Installed>()
    private var indexing: String? = null

    private val listed = HashMap<String, Listed>()
    private val listingsAsked = HashSet<String>()
    private var groupShown: Group? = null
    private var countriesShown: List<Region> = emptyList()
    private var parentShown: Region? = null
    private var regionsShown: List<Region> = emptyList()
    private var regionShown: Region? = null
    private var deleteAsked = false
    private var installedShown: List<Installed> = emptyList()
    private var hereShown: List<Region> = emptyList()

    /** The progress blocks of each page: block id → region id. */
    private val progressShown = HashMap<String, Map<String, String>>()

    /** What each picture was last asked for, and what was drawn, so an unchanged picture is not drawn again. */
    private val asked = HashMap<String, String>()
    private val drawn = HashMap<String, String>()

    override fun onEvent(event: AppEvent, ui: AppContext) {
        this.ui = ui
        when (event) {
            AppEvent.Start -> start()
            AppEvent.Visible -> {
                visible = true
                render()
            }
            AppEvent.Hidden -> visible = false
            AppEvent.Stop -> stop()
            is AppEvent.Location -> {
                gpsFix = event
                if (demo == null) onFix(event)
            }
            is AppEvent.Gesture -> gesture(event.gesture)
            is AppEvent.Click -> click(event.block)
            is AppEvent.Toggle -> toggle(event.block, event.on)
            is AppEvent.Back -> back()
            is AppEvent.Menu -> menu(event.item)
            is AppEvent.TextInput -> answered(event.tag, event.text)
            is AppEvent.Timer -> timer(event.tag)
            is AppEvent.Download -> downloaded(event)
            else -> Unit
        }
    }

    // --- Start and end ---------------------------------------------------------------------------------

    private fun start() {
        // The fonts are read here, on the app thread; the pictures are drawn in background work.
        val fontFiles = listOf(Font.SMALL, Font.LABEL, Font.TEXT, Font.BIG).associate { fontPath(it) to ui.asset(fontPath(it)) }
        val fonts = Fonts { path -> fontFiles[path] }
        screens = Screens(fonts, MapRenderer(fonts))
        store = NaviStore(ui.storage)
        profile = store.profile
        navZoom = navZoomDefault()
        ui.definePages(pages())
        show(P_KARTE)
        ui.subscribe(Sensor.LOCATION)
        val catalogFile = ui.asset(CATALOG)
        if (catalogFile == null) ui.log("Katalog fehlt") else {
            ui.work(W_CATALOG, { Catalog.parse(catalogFile.decodeToString()) }) { result ->
                result.onSuccess {
                    catalog = it
                    if (top() == P_KARTEN) askUpdates()
                    refresh(top())
                }.onFailure { ui.log("Katalog unlesbar: ${it.message}") }
            }
        }
        openMaps()
    }

    private fun stop() {
        (sources + indexes + retired).forEach { runCatching { it.close() } }
        sources = emptyList()
        indexes = emptyList()
        router = null
        retired.clear()
    }

    /** Opens the installed maps and their search indexes in the background and puts them in place of the old ones. */
    private fun openMaps() {
        val dir = ui.files.dir
        if (dir == null) {
            opened = true
            return
        }
        val installed = store.installed()
        ui.work(W_OPEN, {
            val maps = installed.mapNotNull { m -> openOrNull(File(dir, mapPath(m.file))) { MapSource(it) } }
            val idx = installed.filter { it.indexed }.mapNotNull { m -> openOrNull(File(dir, indexPath(m.file))) { SearchIndex(it) } }
            maps to idx
        }) { result ->
            result.onSuccess { (maps, idx) ->
                // Pictures or a route under way may still read the old files: they close a minute later.
                retired += sources + indexes
                if (retired.isNotEmpty()) ui.timer(T_CLOSE, CLOSE_AFTER_MS)
                sources = maps
                indexes = idx
                router = maps.filter { it.routable }.takeIf { it.isNotEmpty() }?.let { Router(it) }
                opened = true
                mapsVersion++
                refresh(top())
                render()
            }.onFailure { ui.log("Karten nicht geöffnet: ${it.message}") }
        }
    }

    private inline fun <T> openOrNull(file: File, open: (File) -> T): T? =
        if (file.isFile) runCatching { open(file) }.getOrNull() else null

    // --- Pages -----------------------------------------------------------------------------------------

    private fun pages(): List<Page> = listOf(
        Page(P_KARTE, "Karte", listOf(Block.Image(B_KARTE, null, Screens.W, Screens.H, bleed = true)), statusBar = false, input = InputMode.GESTURES),
        Page(P_NAV, "Navigation", listOf(Block.Image(B_NAV, null, Screens.W, Screens.H, bleed = true)), statusBar = false, input = InputMode.GESTURES),
        Page(P_MENU, "Menü", menuBlocks()),
        Page(P_ROUTE, "Route planen", routeBlocks()),
        Page(P_TREFFER, "Suche", resultBlocks()),
        Page(P_ERGEBNIS, "Route", overviewBlocks()),
        Page(P_KARTEN, "Karten", mapsBlocks()),
        Page(P_ERDTEILE, "Land laden", groupBlocks()),
        Page(P_LAENDER, "Länder", countryBlocks()),
        Page(P_REGIONEN, "Regionen", regionBlocks()),
        Page(P_LAND, "Karte laden", regionDetailBlocks()),
    )

    private fun menuBlocks(): List<Block> = buildList {
        val r = route
        when {
            navigating -> add(Block.Button("m_weiter", "Navigation fortsetzen"))
            r != null -> add(Block.Button("m_weiter", "Route: ${Format.distance(r.length)} · ${Format.duration(r.seconds)}"))
        }
        add(Block.Button("m_ziel", "Ziel suchen"))
        add(Block.Button("m_route", "Route planen"))
        add(Block.Toggle("m_velo", "Mit dem Velo", profile == Profile.BIKE))
        add(Block.Button("m_karten", "Karten"))
        add(Block.Text("m_gps", gpsText()))
    }

    private fun routeBlocks(): List<Block> = listOf(
        Block.Button("r_von", "Von: ${from?.label ?: MY_POSITION}"),
        Block.Button("r_nach", "Nach: ${to?.label ?: "Ziel suchen …"}"),
        Block.Toggle("r_velo", "Mit dem Velo", profile == Profile.BIKE),
        Block.Button("r_los", if (computing) "Route wird berechnet …" else "Route berechnen"),
        Block.Text("r_info", routeNote ?: " "),
    )

    private fun resultBlocks(): List<Block> = buildList {
        val what = if (queryFor == ASK_FROM) "Start" else "Ziel"
        add(Block.Text("t_frage", if (results.isEmpty() && query.isNotEmpty()) "Nichts gefunden für „$query“" else "$what: „$query“"))
        val near = here()
        results.forEachIndexed { i, p ->
            val away = near?.let { " · " + Format.distance(it.distanceTo(p.position)) } ?: ""
            add(Block.Button("t_$i", p.label + away))
        }
        add(Block.Button("t_neu", "Neu suchen"))
    }

    private fun overviewBlocks(): List<Block> {
        val r = route
        return listOf(
            Block.Image(B_UEBERSICHT, drawn[P_ERGEBNIS], OVERVIEW_W, OVERVIEW_H),
            Block.Text("e_info", if (r == null) " " else "${Format.distance(r.length)} · ${Format.duration(r.seconds)} · ${r.profile.label}"),
            Block.Button("e_los", if (navigating) "Navigation fortsetzen" else "Navigation starten"),
            Block.Button("e_demo", "Demo-Fahrt"),
            Block.Text("e_wo", listOfNotNull(from?.label ?: MY_POSITION, to?.label).joinToString(" → ")),
        )
    }

    private fun mapsBlocks(): List<Block> = buildList {
        val installed = store.installed()
        installedShown = installed
        add(Block.Text("k_frei", "Frei auf der Uhr: ${size(ui.files.freeBytes)}"))
        installed.forEachIndexed { i, m ->
            val notes = listOfNotNull(
                if (updateFor(m) != null) "neuer Stand" else null,
                if (!m.indexed && m.id !in jobs) "ohne Suche" else null,
            )
            add(Block.Button("k_m$i", (listOf(m.name, size(m.bytes)) + notes).joinToString(" · ")))
        }
        val shown = HashMap<String, String>()
        jobs.values.forEachIndexed { i, job ->
            add(Block.Progress("k_p$i", jobText(job), percent(job)))
            shown["k_p$i"] = job.region.id
        }
        progressShown[P_KARTEN] = shown
        hereShown = suggestions()
        hereShown.forEachIndexed { i, r -> add(Block.Button("k_hier$i", "Hier: ${r.name} · ${size(r.bytes ?: 0)}")) }
        add(Block.Button("k_neu", "Land hinzufügen"))
        add(Block.Toggle("k_lte", "Auch über LTE laden", store.lte))
        if (installed.isEmpty() && jobs.isEmpty()) {
            add(Block.Text("k_hilfe", "Karten von OpenStreetMap, je Land eine Datei. Einmal geladen, brauchen Karte, Route und Suche kein Netz mehr."))
        }
    }

    private fun groupBlocks(): List<Block> = buildList {
        val groups = catalog?.groups
        if (groups == null) add(Block.Text("g_warte", "Katalog wird gelesen …"))
        groups?.forEachIndexed { i, g -> add(Block.Button("g_$i", g.name)) }
    }

    private fun countryBlocks(): List<Block> = buildList {
        add(Block.Heading("l_titel", groupShown?.name ?: "Länder"))
        val onWatch = installedIds()
        countriesShown.forEachIndexed { i, r -> add(Block.Button("l_$i", regionLine(r, onWatch))) }
    }

    private fun regionBlocks(): List<Block> = buildList {
        val parent = parentShown
        add(Block.Heading("s_titel", parent?.name ?: "Regionen"))
        val onWatch = installedIds()
        regionsShown.forEachIndexed { i, r ->
            val text = if (r == parent) {
                "Ganz ${r.name} · ${size(r.bytes ?: 0)}" + if (r.id in onWatch) " · auf der Uhr" else ""
            } else {
                regionLine(r, onWatch)
            }
            add(Block.Button("s_$i", text))
        }
    }

    private fun regionDetailBlocks(): List<Block> = buildList {
        val r = regionShown ?: run {
            add(Block.Text("d_info", " "))
            return@buildList
        }
        val installed = store.installed().firstOrNull { it.id == r.id }
        val job = jobs[r.id]
        val bytes = r.bytes ?: 0
        add(Block.Heading("d_titel", r.name))
        val newest = listed[r.id]?.date ?: r.date
        val info = buildString {
            append("Karte ${size(bytes)}")
            newest?.let { append(" · Stand ${date(it)}") }
            if (installed != null) append("\nAuf der Uhr: Stand ${date(installed.date)}")
        }
        add(Block.Text("d_info", info))
        add(Block.Text("d_frei", "Frei auf der Uhr: ${size(ui.files.freeBytes)}"))
        val shown = HashMap<String, String>()
        when {
            job != null -> {
                add(Block.Progress("d_fort", jobText(job), percent(job)))
                shown["d_fort"] = r.id
                when {
                    job.failed != null -> {
                        add(Block.Button("d_laden", "Nochmals versuchen"))
                        add(Block.Button("d_stopp", "Verwerfen"))
                    }
                    job.step == STEP_LOAD -> add(Block.Button("d_stopp", "Laden abbrechen"))
                }
            }
            installed != null -> {
                if (updateFor(installed) != null) add(Block.Button("d_laden", "Aktualisieren · ${size(bytes)}"))
                if (!installed.indexed) add(Block.Button("d_index", "Suchverzeichnis erstellen"))
                add(Block.Button("d_loeschen", if (deleteAsked) "Wirklich löschen?" else "Löschen"))
            }
            else -> {
                val part = partBytes(r)
                val what = if (part > 0) "Weiter laden · ${size(part)} von ${size(bytes)}" else "Laden · ${size(bytes)}"
                add(Block.Button("d_laden", what + if (store.lte) "" else " · WLAN"))
            }
        }
        progressShown[P_LAND] = shown
    }

    private fun installedIds(): Set<String> = store.installed().map { it.id }.toSet()

    private fun regionLine(r: Region, onWatch: Set<String>): String = when {
        r.isFolder -> "${r.name} ›"
        r.id in onWatch -> "${r.name} · auf der Uhr"
        catalog?.children(r.id).orEmpty().isNotEmpty() -> "${r.name} · ${size(r.bytes ?: 0)} ›"
        else -> "${r.name} · ${size(r.bytes ?: 0)}"
    }

    /** Fills [page] with what it shows now. */
    private fun refresh(page: String) {
        when (page) {
            P_MENU -> ui.setBlocks(P_MENU, menuBlocks())
            P_ROUTE -> ui.setBlocks(P_ROUTE, routeBlocks())
            P_TREFFER -> ui.setBlocks(P_TREFFER, resultBlocks())
            P_ERGEBNIS -> ui.setBlocks(P_ERGEBNIS, overviewBlocks())
            P_KARTEN -> ui.setBlocks(P_KARTEN, mapsBlocks())
            P_ERDTEILE -> ui.setBlocks(P_ERDTEILE, groupBlocks())
            P_LAENDER -> ui.setBlocks(P_LAENDER, countryBlocks())
            P_REGIONEN -> ui.setBlocks(P_REGIONEN, regionBlocks())
            P_LAND -> ui.setBlocks(P_LAND, regionDetailBlocks())
        }
    }

    private fun top(): String = stack.last()

    /** Shows [page] over the current one; as in the host, a page never follows itself. */
    private fun show(page: String) {
        refresh(page)
        if (stack.lastOrNull() != page) stack += page
        ui.show(page)
        changed()
    }

    /** Shows [page] in place of the current one, or goes back to it when it is the page below (as the host does). */
    private fun replace(page: String) {
        refresh(page)
        swap(page)
        changed()
    }

    private fun swap(page: String) {
        when {
            stack.isEmpty() -> stack += page
            stack.size >= 2 && stack[stack.size - 2] == page -> stack.removeAt(stack.size - 1)
            else -> stack[stack.size - 1] = page
        }
        ui.replace(page)
    }

    /** Shows [page] right above the map and closes what was opened in between; [P_KARTE] itself: the map alone. */
    private fun aboveMap(page: String) {
        while (stack.size > 2) swap(stack[stack.size - 2])
        if (stack.size == 1 && page != P_KARTE) show(page) else replace(page)
    }

    /** Back to [page] when it is open below, else [page] right above the map. */
    private fun backTo(page: String) {
        if (page !in stack) return aboveMap(page)
        while (top() != page) swap(stack[stack.size - 2])
        refresh(page)
        changed()
    }

    private fun back() {
        // The host leaves the page right after this event.
        if (stack.size > 1) stack.removeAt(stack.size - 1)
        deleteAsked = false
        refresh(top())
        changed()
    }

    /** The app menu and the picture for the page now on the glasses. */
    private fun changed() {
        setMenu()
        render()
    }

    /** The app's own entries in the app menu (tap, then hold) of the map and the navigation. */
    private fun setMenu() {
        ui.menu(
            when (stack.lastOrNull()) {
                P_KARTE -> listOfNotNull(
                    if (navigating) MenuItem(M_NAV, "Navigation") else null,
                    MenuItem(M_SEARCH, "Ziel suchen"),
                    MenuItem(M_MAPS, "Karten"),
                )
                P_NAV -> listOfNotNull(
                    MenuItem(M_STOP, "Navigation beenden"),
                    MenuItem(M_REROUTE, "Route neu berechnen"),
                    if (demo != null) MenuItem(M_DEMO_STOP, "Demo beenden") else null,
                )
                else -> emptyList()
            },
        )
    }

    // --- Input -----------------------------------------------------------------------------------------

    private fun gesture(g: GestureKind) {
        when (stack.lastOrNull()) {
            P_KARTE -> when (g) {
                GestureKind.CLICK -> show(P_MENU)
                GestureKind.SCROLL_UP, GestureKind.SCROLL_DOWN -> {
                    zoom = (zoom + if (g == GestureKind.SCROLL_UP) 1 else -1).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    render()
                }
                else -> Unit
            }
            P_NAV -> when (g) {
                GestureKind.CLICK -> {
                    fullMap = !fullMap
                    render()
                }
                GestureKind.SCROLL_UP, GestureKind.SCROLL_DOWN -> {
                    navZoom = (navZoom + if (g == GestureKind.SCROLL_UP) 1 else -1).coerceIn(MIN_NAV_ZOOM, MAX_ZOOM)
                    render()
                }
                else -> Unit
            }
        }
    }

    private fun click(block: String) {
        when {
            block == "m_weiter" -> when {
                navigating -> aboveMap(P_NAV)
                route != null -> aboveMap(P_ERGEBNIS)
            }
            block == "m_ziel" -> askDestination()
            block == "m_route" -> aboveMap(P_ROUTE)
            block == "m_karten" -> openMapsPage()
            block == "r_von" -> askStart()
            block == "r_nach" -> askDestination()
            block == "r_los" -> computeRoute()
            block == "t_neu" -> if (queryFor == ASK_FROM) askStart() else askDestination()
            block.startsWith("t_") -> results.getOrNull(index(block, "t_"))?.let { choose(queryFor, it) }
            block == "e_los" -> if (navigating) aboveMap(P_NAV) else startNavigation(demoTrip = false)
            block == "e_demo" -> startNavigation(demoTrip = true)
            block == "k_neu" -> show(P_ERDTEILE)
            block.startsWith("k_hier") -> hereShown.getOrNull(index(block, "k_hier"))?.let { openRegion(it) }
            block.startsWith("k_m") -> installedShown.getOrNull(index(block, "k_m"))?.let { m -> openRegion(catalog?.region(m.id) ?: m.asRegion()) }
            block.startsWith("g_") -> catalog?.groups?.getOrNull(index(block, "g_"))?.let { openGroup(it) }
            block.startsWith("l_") -> countriesShown.getOrNull(index(block, "l_"))?.let { chooseRegion(it) }
            block.startsWith("s_") -> regionsShown.getOrNull(index(block, "s_"))?.let { if (it.isFolder) chooseRegion(it) else openRegion(it) }
            block == "d_laden" -> regionShown?.let { loadOrRetry(it) }
            block == "d_stopp" -> regionShown?.let { stopJob(it) }
            block == "d_index" -> regionShown?.let { r -> store.installed().firstOrNull { it.id == r.id }?.let { buildIndex(it) } }
            block == "d_loeschen" -> regionShown?.let { r ->
                if (deleteAsked) {
                    delete(r)
                } else {
                    deleteAsked = true
                    refresh(P_LAND)
                }
            }
        }
    }

    private fun index(block: String, prefix: String): Int = block.removePrefix(prefix).toIntOrNull() ?: -1

    private fun toggle(block: String, on: Boolean) {
        when (block) {
            "m_velo", "r_velo" -> {
                profile = if (on) Profile.BIKE else Profile.FOOT
                store.profile = profile
                if (!navigating) navZoom = navZoomDefault()
            }
            "k_lte" -> store.lte = on
        }
    }

    private fun menu(item: String) {
        when (item) {
            M_SEARCH -> askDestination()
            M_MAPS -> openMapsPage()
            M_NAV -> if (navigating) aboveMap(P_NAV)
            M_STOP -> {
                stopNavigation()
                route = null
                aboveMap(P_KARTE)
            }
            M_REROUTE -> reroute(force = true)
            M_DEMO_STOP -> {
                stopDemo()
                fix?.let { f -> navigator?.let { navigateWith(it, f) } }
                changed()
            }
        }
    }

    private fun timer(tag: String) {
        when (tag) {
            T_DEMO -> demo?.let { trip ->
                val elapsed = clock() - demoStartMs
                onFix(trip.fixAt(elapsed))
                if (trip.finished(elapsed)) ui.cancelTimer(T_DEMO)
            }
            T_SILENT -> for (job in jobs.values) {
                // The watch reports every download within a few seconds; nothing at all means it was refused.
                if (job.step == STEP_LOAD && !job.heard && job.failed == null) {
                    job.failed = "Keine Antwort. Ist „Internet“ für Navi erlaubt (Einstellungen → Apps)?"
                    tagRegion.entries.removeAll { it.value == job.region.id }
                    refresh(top())
                }
            }
            T_PROGRESS -> {
                if (jobs.values.none { it.indexed != null }) ui.cancelTimer(T_PROGRESS)
                showProgress()
            }
            T_CLOSE -> {
                retired.forEach { runCatching { it.close() } }
                retired.clear()
            }
        }
    }

    // --- Position --------------------------------------------------------------------------------------

    private fun onFix(f: AppEvent.Location) {
        fix = f
        val now = clock()
        if (demo == null && now - savedPositionAt > SAVE_POSITION_MS) {
            savedPositionAt = now
            store.lastPosition = LatLon(f.lat, f.lon)
        }
        navigator?.let { navigateWith(it, f) }
        when (stack.lastOrNull()) {
            P_MENU -> ui.patch(P_MENU) { text("m_gps", gpsText()) }
            P_KARTEN -> if (suggestions() != hereShown) refresh(P_KARTEN)
            P_KARTE, P_NAV -> render()
        }
    }

    private fun here(): LatLon? = fix?.let { LatLon(it.lat, it.lon) }

    private fun gpsText(): String {
        val f = fix
        return when {
            demo != null -> "Demo-Fahrt läuft"
            f == null -> "GPS: warte auf den Standort …"
            else -> "GPS: ±${f.acc.roundToInt()} m"
        }
    }

    /** Where the map looks: the position, else where it was last, else the first map's own start. */
    private fun mapCenter(): LatLon =
        here() ?: store.lastPosition ?: sources.firstOrNull { it.routable }?.map?.header?.let { it.start ?: it.box.center } ?: SWITZERLAND

    // --- Route -----------------------------------------------------------------------------------------

    private fun askDestination() {
        ui.askText(ASK_TO, "Wohin?", recentLabels(5))
    }

    private fun askStart() {
        ui.askText(ASK_FROM, "Von wo?", listOf(MY_POSITION) + recentLabels(4))
    }

    private fun recentLabels(n: Int): List<String> = store.recent().map { it.label.take(MAX_SUGGESTION) }.distinct().take(n)

    private fun answered(tag: String, text: String?) {
        val answer = text?.trim()
        if (answer.isNullOrEmpty() || (tag != ASK_TO && tag != ASK_FROM)) return
        if (tag == ASK_FROM && answer == MY_POSITION) {
            from = null
            routeNote = null
            backTo(P_ROUTE)
            return
        }
        store.recent().firstOrNull { it.label.take(MAX_SUGGESTION) == answer }?.let { return choose(tag, it) }
        search(tag, answer)
    }

    private fun search(tag: String, text: String) {
        val idx = indexes
        if (idx.isEmpty()) {
            val waiting = jobs.values.any { it.step == STEP_INDEX && it.failed == null }
            ui.toast(if (waiting) "Das Suchverzeichnis entsteht noch" else "Zuerst eine Karte laden: Menü → Karten", 3_000)
            return
        }
        val near = here() ?: mapCenter()
        ui.work(W_SEARCH, { SearchIndex.searchAll(idx, text, near) }) { result ->
            query = text
            queryFor = tag
            results = result.getOrElse {
                ui.log("Suche: ${it.message}")
                emptyList()
            }
            // Asked from the route page, the results lie over it, so back returns there.
            when (top()) {
                P_TREFFER -> {
                    refresh(P_TREFFER)
                    changed()
                }
                P_ROUTE -> show(P_TREFFER)
                else -> aboveMap(P_TREFFER)
            }
        }
    }

    private fun choose(tag: String, place: Place) {
        routeNote = null
        if (tag == ASK_FROM) {
            from = place
            backTo(P_ROUTE)
            return
        }
        to = place
        store.remember(place)
        computeRoute()
    }

    private fun computeRoute() {
        if (computing) return
        val origin = from?.position ?: here()
        val dest = to?.position
        val r = router
        routeNote = when {
            r == null -> if (sources.isEmpty()) "Zuerst eine Karte laden: Menü → Karten" else "Diese Karten haben keine Strassen für Routen"
            dest == null -> "Zuerst ein Ziel wählen"
            origin == null -> "Noch kein Standort: auf GPS warten oder einen Start wählen"
            else -> null
        }
        if (r == null || origin == null || dest == null) return backTo(P_ROUTE)
        computing = true
        when (top()) {
            P_ROUTE -> refresh(P_ROUTE)
            P_TREFFER -> ui.patch(P_TREFFER) { text("t_frage", "Route wird berechnet …") }
            else -> ui.toast("Route wird berechnet …", 1_500)
        }
        val p = profile
        ui.work(W_ROUTE, { r.route(origin, dest, p) }) { result ->
            computing = false
            result.onSuccess { found ->
                stopNavigation()
                route = found
                aboveMap(P_ERGEBNIS)
            }.onFailure { e ->
                routeNote = (e as? RouteException)?.message ?: "Keine Route gefunden (${e.message})"
                backTo(P_ROUTE)
            }
        }
    }

    private fun startNavigation(demoTrip: Boolean) {
        val r = route ?: return
        stopDemo()
        navigator = Navigator(r)
        navigating = true
        fullMap = false
        announced = -1
        arrivedNoted = false
        nav = null
        navZoom = if (r.profile == Profile.BIKE) NAV_ZOOM_BIKE else NAV_ZOOM_FOOT
        if (demoTrip) {
            demoStartMs = clock()
            demo = DemoTrip(r, demoStartMs, r.profile.speedMs * if (r.profile == Profile.BIKE) DEMO_SPEEDUP_BIKE else DEMO_SPEEDUP_FOOT)
            ui.timer(T_DEMO, DEMO_STEP_MS, repeat = true)
        }
        aboveMap(P_NAV)
        (demo?.fixAt(0) ?: fix)?.let { onFix(it) }
    }

    private fun stopNavigation() {
        navigating = false
        navigator = null
        nav = null
        stopDemo()
    }

    /** Ends the demo trip; the GPS's position counts again. */
    private fun stopDemo() {
        if (demo == null) return
        demo = null
        ui.cancelTimer(T_DEMO)
        fix = gpsFix
    }

    private fun navigateWith(navigator: Navigator, f: AppEvent.Location) {
        val s = navigator.update(f)
        nav = s
        if (s.arrived) {
            if (!arrivedNoted) {
                arrivedNoted = true
                ui.vibrate(Vibration.LONG)
                if (demo != null) ui.cancelTimer(T_DEMO)
            }
            return
        }
        if (s.offRoute) reroute(force = false)
        // A tick on the wrist before each turn.
        val warnAt = if (navigator.route.profile == Profile.BIKE) WARN_BIKE_M else WARN_FOOT_M
        if (s.next.turn != Turn.ARRIVE && s.next.index != announced && s.toNext <= warnAt) {
            announced = s.next.index
            ui.vibrate(Vibration.TICK)
        }
    }

    /** A new route from where the wearer is to the same destination. */
    private fun reroute(force: Boolean) {
        val r = router ?: return
        val current = route ?: return
        val start = here() ?: return
        val now = clock()
        if (rerouting || computing || (!force && now - lastRerouteMs < REROUTE_PAUSE_MS)) return
        rerouting = true
        lastRerouteMs = now
        val dest = current.end
        val p = current.profile
        ui.work(W_REROUTE, { r.route(start, dest, p) }) { result ->
            rerouting = false
            result.onSuccess { found ->
                if (!navigating || route !== current) return@onSuccess
                route = found
                val n = Navigator(found)
                navigator = n
                announced = -1
                ui.vibrate(Vibration.DOUBLE)
                fix?.let { navigateWith(n, it) }
                render()
            }.onFailure { e ->
                ui.log("Neue Route: ${e.message}")
                if (force) ui.toast((e as? RouteException)?.message ?: "Keine neue Route gefunden", 3_000)
            }
        }
    }

    // --- Pictures --------------------------------------------------------------------------------------

    private fun render() {
        if (!visible || !::screens.isInitialized) return
        when (stack.lastOrNull()) {
            P_KARTE -> renderMap()
            P_NAV -> renderNav()
            P_ERGEBNIS -> renderOverview()
        }
    }

    private fun renderMap() {
        if (!opened) return
        val maps = sources
        if (maps.isEmpty()) {
            val lines = listOfNotNull(jobs.values.firstOrNull()?.let { jobText(it) }, "Tippen: Menü → Karten", "Je Land eine Datei, danach ganz offline")
            picture(P_KARTE, B_KARTE, "leer|$lines", Screens.W, Screens.H) { screens.message("Noch keine Karte", lines) }
            return
        }
        val f = fix
        val center = mapCenter()
        val heading = f?.takeIf { (it.speed ?: 0f) >= Navigator.MOVING_MS }?.bearing?.toDouble()
        val r = route
        val along = nav?.along ?: 0.0
        val overlay = Overlay(route = r?.line(along), position = here(), heading = heading, accuracyM = f?.acc?.toDouble(), destination = r?.end)
        val status = when {
            demo != null -> "Demo"
            f == null -> "Warte auf GPS"
            else -> "±${f.acc.roundToInt()} m"
        }
        val z = zoom
        val key = listOf(
            mapsVersion, center.latE6 / KEY_E6, center.lonE6 / KEY_E6, z, heading?.let { (it / KEY_DEG).roundToInt() },
            f?.acc?.let { (it / KEY_M).roundToInt() }, System.identityHashCode(r), (along / KEY_M).toInt(), status,
        ).joinToString("|")
        picture(P_KARTE, B_KARTE, key, Screens.W, Screens.H) { detail -> screens.map(maps, center, z, overlay.copy(detail = detail), status) }
    }

    private fun renderNav() {
        val r = route ?: return
        if (navigator == null) return
        val maps = sources
        val now = clock()
        // Before the first fix the screen stands at the start of the route.
        val state = nav ?: Navigator(r).update(AppEvent.Location(r.start.lat, r.start.lon, START_ACCURACY_M, now))
        val note = if (nav == null) "Warte auf GPS …" else null
        val full = fullMap
        val z = navZoom
        val key = listOf(
            mapsVersion, System.identityHashCode(r), (state.along / KEY_M).toInt(), state.position.latE6 / KEY_E6, state.position.lonE6 / KEY_E6,
            state.heading?.let { (it / KEY_DEG).roundToInt() }, full, z, (now + (state.secondsLeft * 1000).toLong()) / 60_000, note,
            state.offRoute, state.arrived,
        ).joinToString("|")
        picture(P_NAV, B_NAV, key, Screens.W, Screens.H) { detail -> screens.navigation(maps, r, state, full, z, now, note, detail) }
    }

    private fun renderOverview() {
        val r = route ?: return
        val maps = sources
        picture(P_ERGEBNIS, B_UEBERSICHT, "$mapsVersion|${System.identityHashCode(r)}", OVERVIEW_W, OVERVIEW_H) { detail ->
            screens.overview(maps, r, OVERVIEW_W, OVERVIEW_H, detail)
        }
    }

    /**
     * Draws the picture of image [block] on [page] in the background, unless it was asked for with the same [key]
     * already. [draw] gets the detail to draw with: a picture too big for an image block is drawn again with less.
     */
    private fun picture(page: String, block: String, key: String, w: Int, h: Int, draw: (Int) -> Raster) {
        if (asked[page] == key) return
        asked[page] = key
        ui.work(W_PICTURE + page, { fitted(w, h, draw) }) { result ->
            result.onSuccess { url ->
                drawn[page] = url
                ui.patch(page) { image(block, url) }
            }.onFailure { e ->
                asked.remove(page)
                ui.log("Bild nicht gezeichnet ($page): ${e.message}")
            }
        }
    }

    private fun fitted(w: Int, h: Int, draw: (Int) -> Raster): String {
        for (detail in 2 downTo 1) {
            val url = draw(detail).dataUrl()
            if (url.length <= Raster.MAX_DATA_URL) return url
        }
        return Raster(w, h).dataUrl()
    }

    // --- Maps on the watch -----------------------------------------------------------------------------

    private fun openMapsPage() {
        aboveMap(P_KARTEN)
        askUpdates()
    }

    private fun openGroup(g: Group) {
        val cat = catalog ?: return
        groupShown = g
        countriesShown = cat.countries(g.id)
        show(P_LAENDER)
        askListings(countriesShown)
    }

    /** A country with regions shows them (with the whole country first, if there is one file for it); else its page. */
    private fun chooseRegion(r: Region) {
        val cat = catalog ?: return
        val children = cat.children(r.id)
        if (children.isEmpty()) return openRegion(r)
        parentShown = r
        regionsShown = (if (r.isFolder) emptyList() else listOf(r)) + children
        show(P_REGIONEN)
        askListings(regionsShown)
    }

    private fun openRegion(r: Region) {
        regionShown = r
        deleteAsked = false
        show(P_LAND)
        askListings(listOf(r))
    }

    /** Whether the server has newer maps than the watch: the folder listings of the installed maps. */
    private fun askUpdates() {
        val cat = catalog ?: return
        askListings(store.installed().mapNotNull { cat.region(it.id) })
    }

    /** The newest dates from the server's folder listings, once per folder and start. */
    private fun askListings(regions: List<Region>) {
        val cat = catalog ?: return
        for (folder in regions.filter { !it.isFolder }.map { Listing.folderOf(cat, it) }.distinct()) {
            if (!listingsAsked.add(folder)) continue
            ui.fetch(HttpRequest(folder)) { result ->
                if (!result.ok) {
                    listingsAsked.remove(folder)
                    return@fetch
                }
                val rows = Listing.parse(result.body)
                for (r in cat.regions) {
                    if (r.isFolder || Listing.folderOf(cat, r) != folder) continue
                    rows[r.id.substringAfterLast('/')]?.let { listed[r.id] = it }
                }
                refresh(top())
            }
        }
    }

    /** The server's newer date for [m], when its map there is a few days newer than the one on the watch. */
    private fun updateFor(m: Installed): String? {
        val newest = listed[m.id]?.date ?: return null
        return runCatching {
            if (ChronoUnit.DAYS.between(LocalDate.parse(m.date), LocalDate.parse(newest)) >= UPDATE_DAYS) newest else null
        }.getOrNull()
    }

    /**
     * The smallest maps for where the wearer is, when no installed map reaches there. Their boxes are rectangles,
     * so near a border a neighbour may come first: the wearer picks.
     */
    private fun suggestions(): List<Region> {
        val p = here() ?: return emptyList()
        val cat = catalog ?: return emptyList()
        if (sources.any { it.routable && it.box.contains(p.latE6, p.lonE6) }) return emptyList()
        val onWatch = installedIds()
        return cat.holding(p).filter { it.id !in jobs && it.id !in onWatch }.take(MAX_SUGGESTED_MAPS)
    }

    private fun partBytes(r: Region): Long = ui.files.dir?.let { File(it, mapPath(r.file) + AppFiles.PART).length() } ?: 0

    private fun startDownload(r: Region) {
        val cat = catalog ?: return
        val bytes = r.bytes ?: return
        val free = ui.files.freeBytes
        val needed = bytes - partBytes(r) + bytes * INDEX_SHARE / 100 + RESERVE_BYTES
        if (needed > free) {
            ui.toast("Zu wenig Platz: braucht ${size(needed)}, frei sind ${size(free)}", 4_000)
            return
        }
        val tag = "dl${nextTag++}"
        tagRegion[tag] = r.id
        jobs[r.id] = Job(r, bytes)
        ui.files.download(tag, cat.url(r), mapPath(r.file), wifiOnly = !store.lte)
        ui.timer(T_SILENT, SILENT_MS)
        refresh(top())
    }

    private fun loadOrRetry(r: Region) {
        val job = jobs[r.id]
        val installed = store.installed().firstOrNull { it.id == r.id }
        if (job?.failed != null && job.step == STEP_INDEX && installed != null) buildIndex(installed) else startDownload(r)
    }

    /** Stops the download of [r], or drops its failed job. */
    private fun stopJob(r: Region) {
        val job = jobs[r.id] ?: return
        if (job.failed != null) {
            jobs.remove(r.id)
            refresh(top())
            return
        }
        tagRegion.entries.firstOrNull { it.value == r.id }?.let { ui.files.cancelDownload(it.key) }
    }

    private fun downloaded(e: AppEvent.Download) {
        val id = tagRegion[e.tag] ?: return
        val job = jobs[id]
        if (job == null) {
            tagRegion.remove(e.tag)
            return
        }
        job.heard = true
        when (e.state) {
            DownloadState.RUNNING -> {
                job.bytes = e.bytes
                if (e.total > 0) job.total = e.total
                showProgress()
                return
            }
            DownloadState.DONE -> {
                tagRegion.remove(e.tag)
                check(job)
            }
            DownloadState.FAILED -> {
                tagRegion.remove(e.tag)
                job.failed = e.message ?: "Laden fehlgeschlagen"
            }
            DownloadState.CANCELLED -> {
                tagRegion.remove(e.tag)
                jobs.remove(id)
            }
        }
        refresh(top())
    }

    /** A loaded map counts once it reads: then it shows at once, and its search index follows. */
    private fun check(job: Job) {
        val dir = ui.files.dir ?: return
        val r = job.region
        val file = File(dir, mapPath(r.file))
        // An update keeps the old search index until the new one is there.
        val keepIndex = store.installed().any { it.id == r.id && it.indexed } && File(dir, indexPath(r.file)).isFile
        job.step = STEP_CHECK
        ui.work("pruefen${nextTag++}", { MapFile(file).use { it.header.date } }) { result ->
            result.onSuccess { made ->
                val installed = Installed(r.id, r.name, file.length(), day(made), indexed = keepIndex)
                store.put(installed)
                openMaps()
                buildIndex(installed)
            }.onFailure { e ->
                file.delete()
                job.step = STEP_LOAD
                job.failed = "Karte unlesbar (${e.message}), bitte nochmals laden"
                refresh(top())
            }
        }
    }

    /** Builds the search index of [m] once the ones before it are done. */
    private fun buildIndex(m: Installed) {
        val job = jobs.getOrPut(m.id) { Job(catalog?.region(m.id) ?: m.asRegion(), m.bytes) }
        job.step = STEP_INDEX
        job.failed = null
        job.indexed = null
        indexQueue.removeAll { it.id == m.id }
        indexQueue.addLast(m)
        nextIndex()
        refresh(top())
    }

    private fun nextIndex() {
        if (indexing != null) return
        val dir = ui.files.dir ?: return
        val m = indexQueue.removeFirstOrNull() ?: return
        val job = jobs[m.id] ?: return nextIndex()
        val progress = AtomicLong(0)
        job.indexed = progress
        indexing = m.id
        ui.timer(T_PROGRESS, PROGRESS_MS, repeat = true)
        val mapFile = File(dir, mapPath(m.file))
        val indexFile = File(dir, indexPath(m.file))
        ui.work("index${nextTag++}", {
            MapFile(mapFile).use { map -> SearchIndexBuilder.build(map, indexFile) { p -> progress.set((p * 1000).toLong()) } }
        }) { result ->
            indexing = null
            result.onSuccess {
                if (store.installed().none { it.id == m.id }) {
                    // Deleted meanwhile.
                    indexFile.delete()
                    jobs.remove(m.id)
                } else {
                    store.put(m.copy(indexed = true))
                    jobs.remove(m.id)
                    ui.vibrate(Vibration.DOUBLE)
                    ui.toast("${m.name} ist bereit")
                    openMaps()
                }
            }.onFailure { e ->
                job.indexed = null
                job.failed = "Suchverzeichnis: ${e.message}"
            }
            refresh(top())
            nextIndex()
        }
    }

    private fun delete(r: Region) {
        val dir = ui.files.dir ?: return
        store.remove(r.id)
        deleteAsked = false
        val names = listOf(mapPath(r.file), indexPath(r.file)).flatMap { listOf(it, it + AppFiles.PART) }
        // The open files go with the next opening; deleted files stay readable until then.
        openMaps()
        ui.work("loeschen${nextTag++}", { names.forEach { File(dir, it).delete() } }) {
            ui.toast("${r.name} gelöscht")
            refresh(top())
        }
        refresh(top())
    }

    /** The progress blocks of the page on the glasses, while something loads or an index is built. */
    private fun showProgress() {
        val page = stack.lastOrNull() ?: return
        val shown = progressShown[page].orEmpty().filterValues { it in jobs }
        if (shown.isNotEmpty()) {
            ui.patch(page) {
                for ((block, id) in shown) {
                    val job = jobs.getValue(id)
                    text(block, jobText(job))
                    progress(block, percent(job))
                }
            }
        }
        if (page == P_KARTE && sources.isEmpty()) render()
    }

    private fun jobText(job: Job): String {
        val name = job.region.name
        job.failed?.let { return "$name: $it" }
        return when (job.step) {
            STEP_CHECK -> "$name: wird geprüft …"
            STEP_INDEX -> job.indexed?.let { "$name: Suchverzeichnis ${it.get() / 10} %" } ?: "$name: Suchverzeichnis wartet"
            else -> "$name: ${size(job.bytes)} von ${size(job.total)}"
        }
    }

    private fun percent(job: Job): Int = when (job.step) {
        STEP_INDEX -> ((job.indexed?.get() ?: 0) / 10).toInt()
        STEP_CHECK -> 100
        else -> if (job.total > 0) (job.bytes * 100 / job.total).toInt() else 0
    }.coerceIn(0, 100)

    private fun navZoomDefault() = if (profile == Profile.BIKE) NAV_ZOOM_BIKE else NAV_ZOOM_FOOT

    private fun Installed.asRegion() = Region(id, name, "", null, bytes, date, Box(0, 0, 0, 0))

    companion object {
        const val ID = "ch.madtreasures.navi"
        const val VERSION = "1.0.0"

        const val P_KARTE = "p_karte"
        const val P_NAV = "p_nav"
        const val P_MENU = "p_menu"
        const val P_ROUTE = "p_route"
        const val P_TREFFER = "p_treffer"
        const val P_ERGEBNIS = "p_ergebnis"
        const val P_KARTEN = "p_karten"
        const val P_ERDTEILE = "p_erdteile"
        const val P_LAENDER = "p_laender"
        const val P_REGIONEN = "p_regionen"
        const val P_LAND = "p_land"
        const val B_KARTE = "karte"
        const val B_NAV = "nav"
        const val B_UEBERSICHT = "e_bild"

        const val M_SEARCH = "suchen"
        const val M_MAPS = "karten"
        const val M_NAV = "navigation"
        const val M_STOP = "beenden"
        const val M_REROUTE = "neu"
        const val M_DEMO_STOP = "demo_stopp"

        const val ASK_TO = "ziel"
        const val ASK_FROM = "start"
        const val MY_POSITION = "Mein Standort"

        const val T_DEMO = "demo"
        const val T_SILENT = "stille"
        const val T_PROGRESS = "fortschritt"
        const val T_CLOSE = "schliessen"

        private const val W_CATALOG = "katalog"
        private const val W_OPEN = "oeffnen"
        private const val W_SEARCH = "suche"
        private const val W_ROUTE = "route"
        private const val W_REROUTE = "umleitung"
        private const val W_PICTURE = "bild-"

        private const val STEP_LOAD = 0
        private const val STEP_CHECK = 1
        private const val STEP_INDEX = 2

        const val CATALOG = "apps/$ID/catalog.json"
        const val OVERVIEW_W = 544
        const val OVERVIEW_H = 150

        const val MAP_ZOOM = 16.0
        const val MIN_ZOOM = 5.0
        const val MAX_ZOOM = 18.5
        const val MIN_NAV_ZOOM = 14.0
        const val NAV_ZOOM_FOOT = 17.0
        const val NAV_ZOOM_BIKE = 16.0

        /** Where the map looks with nothing else to go by. */
        val SWITZERLAND = LatLon(46.8, 8.23)

        /** The demo trip goes at a few times the profile's speed, a fix a second. */
        const val DEMO_SPEEDUP_FOOT = 3.0
        const val DEMO_SPEEDUP_BIKE = 2.0
        const val DEMO_STEP_MS = 1_000L

        /** The wrist ticks this far before a turn. */
        const val WARN_FOOT_M = 30.0
        const val WARN_BIKE_M = 60.0
        const val REROUTE_PAUSE_MS = 15_000L
        const val SAVE_POSITION_MS = 60_000L
        const val PROGRESS_MS = 1_000L
        const val CLOSE_AFTER_MS = 60_000L

        /** A download the watch has not reported on after this long counts as refused. */
        const val SILENT_MS = 90_000L
        private const val START_ACCURACY_M = 50f

        /** The watch offers suggestions of at most this many characters; the wearer is offered so many maps for here. */
        const val MAX_SUGGESTION = 40
        const val MAX_SUGGESTED_MAPS = 3

        /** A search index takes about this share of its map; the watch keeps this much free besides. */
        const val INDEX_SHARE = 7
        const val RESERVE_BYTES = 500L * 1024 * 1024

        /** A map on the server this many days newer than the one on the watch is offered as an update. */
        const val UPDATE_DAYS = 3

        /** Pictures are drawn again when something moved this much: microdegrees, degrees, metres. */
        private const val KEY_E6 = 20
        private const val KEY_DEG = 5.0
        private const val KEY_M = 3.0

        fun mapPath(file: String) = "maps/$file.map"

        fun indexPath(file: String) = "index/$file.idx"

        fun fontPath(name: String) = "apps/$ID/fonts/$name.font"

        /** "8,5 MB", "320 MB", "3,2 GB". */
        fun size(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return when {
                mb < 10 -> String.format(Locale.GERMANY, "%.1f MB", mb)
                mb < 1000 -> "${mb.roundToInt()} MB"
                else -> String.format(Locale.GERMANY, "%.1f GB", mb / 1024)
            }
        }

        /** "2026-09-23" → "23.09.2026". */
        fun date(iso: String): String = iso.split('-').takeIf { it.size == 3 }?.let { "${it[2]}.${it[1]}.${it[0]}" } ?: iso

        /** The UTC day of [epochMs] as "2026-09-23". */
        fun day(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }
}

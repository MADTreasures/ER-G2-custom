package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppCommand
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppFiles
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.BaukastenFormatException
import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.BuzzNote
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.DownloadState
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.PackageManifest
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PatchBuilder
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.PhoneAction
import ch.madtreasures.g2watch.apps.PlayerAction
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.VideoAction
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.apps.WebAction
import ch.madtreasures.g2watch.apps.WebState
import ch.madtreasures.g2watch.apps.builtInApps
import ch.madtreasures.g2watch.apps.files.DownloadJob
import ch.madtreasures.g2watch.apps.files.DownloadListener
import ch.madtreasures.g2watch.apps.files.DownloadRequest
import ch.madtreasures.g2watch.apps.launcher.LaunchEntry
import ch.madtreasures.g2watch.apps.launcher.LaunchKind
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.launcher.LauncherHost
import ch.madtreasures.g2watch.apps.location.LocationLine
import ch.madtreasures.g2watch.apps.media.MediaLine
import ch.madtreasures.g2watch.apps.media.StreamPlayer
import ch.madtreasures.g2watch.apps.location.LocationListener
import ch.madtreasures.g2watch.apps.phone.PhoneLine
import ch.madtreasures.g2watch.apps.phone.PhoneListener
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.GrayImages
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.PageLook
import ch.madtreasures.g2watch.apps.render.PageMetrics
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.apps.render.hit
import ch.madtreasures.g2watch.apps.video.VideoListener
import ch.madtreasures.g2watch.apps.video.VideoPlayer
import ch.madtreasures.g2watch.apps.video.VideoRequest
import ch.madtreasures.g2watch.apps.web.WebListener
import ch.madtreasures.g2watch.apps.web.WebPage
import ch.madtreasures.g2watch.apps.web.WebRequest
import ch.madtreasures.g2watch.apps.web.WebStatus
import ch.madtreasures.g2watch.desktop.AppInput
import ch.madtreasures.g2watch.desktop.AppScreen
import ch.madtreasures.g2watch.desktop.AppView
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.Rect
import ch.madtreasures.g2watch.desktop.TextPainter
import ch.madtreasures.g2watch.input.KeyPress
import ch.madtreasures.g2watch.link.PhoneLinkWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64
import kotlin.math.abs

/** What the host shows and runs, for the watch UI and for tests. */
data class HostState(
    /** App id of the session on the glasses (the launcher at home); null only until the launcher is up. */
    val visible: String? = null,
    /** Id of the page on the glasses; host pages start with "host.". */
    val page: String? = null,
    val focus: FocusTarget? = null,
    /** Pixels the page on the glasses is scrolled down by. */
    val scroll: Int = 0,
    /** App ids of all running app sessions, the launcher excluded. */
    val running: List<String> = emptyList(),
    /** Entry id of the app the wearer moves in the launcher right now ("watch:…"), or null (03 §5). */
    val moving: String? = null,
)

/**
 * Runs the apps on the watch and shows one of them on the glasses (docs/app-entwicklung/03 §5).
 *
 * Everything happens on one thread ([scheduler], "G2Watch-apps"): the host delivers events to the apps
 * there, applies their commands to each session's [PageState], lays out and draws the visible page at
 * most every [RENDER_INTERVAL_MS] (new video pictures every [VIDEO_INTERVAL_MS]) and hands the picture to
 * the desktop ([AppScreen]), which puts the header above it and sends it to the glasses. Input arrives
 * from the desktop thread ([AppInput]: pointer, clicks, header), from the temples and the ring ([gesture],
 * via [InputRouter]), from the watch touchpad in gesture mode and from a Bluetooth keyboard and mouse
 * ([key], [wheel]); all of it is posted to the app thread first.
 *
 * The host guarantees what 02 promises regardless of the app: back always works and leaves the app on
 * its first page, the app menu (tap-then-hold, or the title in the header) always offers "Apps", "Zurück"
 * and "Schließen", an app that takes over 500 ms for an event or throws is ended, and a command it may
 * not give is refused and logged instead of silently dropped.
 *
 * The launcher is home: the host shows it as soon as it exists, every app that ends hands back to it,
 * and back on it stays there.
 */
class AppHost(
    private val scheduler: Scheduler,
    private val screen: AppScreen,
    text: TextPainter,
    private val ports: HostPorts,
    builtIn: List<() -> G2App> = builtInApps,
    private val evenHub: EvenHubRegistry = EvenHubRegistry.NONE,
    private val installed: InstalledApps = InstalledApps.NONE,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val nanoTime: () -> Long = System::nanoTime,
) : AppInput, LauncherHost {

    private class WatchApp(val manifest: AppManifest, val factory: () -> G2App)

    private val watchApps: Map<String, WatchApp> = LinkedHashMap<String, WatchApp>().apply {
        for (factory in builtIn) {
            val manifest = factory().manifest
            val id = WATCH_PREFIX + manifest.id
            require(id !in this) { "app ${manifest.id} is registered twice" }
            put(id, WatchApp(manifest, factory))
        }
    }

    private val renderer = PageRenderer(text)

    // Everything below is only touched on the app thread.
    private val sessions = LinkedHashMap<String, Session>()
    private var visible: Session? = null
    private var overlay: HostPage? = null
    private var launcher: Launcher? = null
    private var glasses = GlassesStatus()
    private var pointer: Pair<Int, Int>? = null
    private var renderScheduled = false
    private var renderAtMs = 0L
    private var renderToken = 0
    private var lastRenderMs = Long.MIN_VALUE / 2
    private var appOnScreen = false
    private var cachedLayout: PageLayout? = null
    private var question: TextQuestion? = null

    /** Launcher icons by "app id@version"; null for an app without one ([iconOf]). */
    private val icons = HashMap<String, GrayRaster?>()
    /** The line typed on the glasses for [question] while a Bluetooth keyboard is there. */
    private var editor: TextEditor? = null

    /** The glasses went away while connected; videos and web pages wait until they are back. */
    private var glassesGone = false

    /** Pointer pushes past the edge over a web page, gathered for one [AppEvent.ImageScroll]. */
    private var pushedScroll = 0
    private var pushScheduled = false

    /** The image block the gathered scroll is for (the web page under the pointer), or null for the first. */
    private var pushedBlock: String? = null

    /** A finger or a mouse button held on an app of the launcher: after [HOLD_MS] the app lifts to move. */
    private class Hold(val block: String, val x: Int, val y: Int, val click: Boolean) {
        /** Still where it came down: lifts when the time is up. */
        var still = true
    }

    private var hold: Hold? = null

    private val _inputMode = MutableStateFlow(InputMode.POINTER)

    /** How the watch touchpad works right now: pointer, or gestures for the app on the glasses. */
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _glasses = MutableStateFlow(GlassesStatus())

    /** Battery, charging and wearing of the glasses, as apps see them (03 §5.2). */
    val glassesStatus: StateFlow<GlassesStatus> = _glasses.asStateFlow()

    init {
        // Home is the launcher, from the first picture on.
        scheduler.post { showLauncher(null) }
    }

    // --- Input, from any thread -------------------------------------------------------------------

    override fun openLauncher() = scheduler.post { showLauncher(null) }

    /** Starts the app with launcher entry [entryId] ("watch:<app id>"), or shows it if it runs already. */
    override fun launch(entryId: String) = scheduler.post { launchNow(entryId) }

    override fun pointerAt(x: Int, y: Int) = scheduler.post {
        pointer = if (x < 0 || y < 0) null else Pair(x, y)
        hold?.let { h ->
            val p = pointer
            if (p == null || abs(p.first - h.x) > HOLD_SLOP || abs(p.second - h.y) > HOLD_SLOP) h.still = false
        }
        pointerMoved()
    }

    override fun clickAt(x: Int, y: Int) = scheduler.post { clickNow(x, y) }

    override fun pressAt(x: Int, y: Int, click: Boolean) = scheduler.post { pressNow(x, y, click) }

    override fun release() = scheduler.post { releaseNow() }

    private fun clickNow(x: Int, y: Int) {
        // While an app is being moved, a click puts it down.
        if (dropMoving()) return
        val scr = currentScreen() ?: return
        val layout = layoutOf(scr)
        val target = layout.hit(x, y, scr.view.scroll)?.target
        if (target == null) {
            // A web page takes the click itself, at that spot (05 §10).
            webAt(scr, x, y)?.let { (w, at) -> webClick(scr, w, at) }
            return
        }
        scr.view.focus = target
        scr.view.fresh = false
        activate(scr, target)
    }

    /** The pointer moved, or the page under it: what is under it now gets the focus, or the lifted app goes there. */
    private fun pointerMoved() {
        if (launcher?.lifted != null) dragNow() else hover()
    }

    override fun scrollBy(dy: Int) = scheduler.post {
        val scr = currentScreen() ?: return@post
        val layout = layoutOf(scr)
        val next = (scr.view.scroll + dy).coerceIn(0, layout.maxScroll)
        if (next == scr.view.scroll) {
            // The page is at its end; a web page on it scrolls instead.
            if (webOnPage(scr) != null) pushWebScroll(dy)
            return@post
        }
        scr.view.scroll = next
        pointerMoved()
        invalidate()
    }

    override fun back() = scheduler.post { backNow() }

    override fun openMenu() = scheduler.post { openMenuNow() }

    /** A gesture of the watch, a temple or the ring (from [InputRouter]). */
    fun gesture(g: Gesture) = scheduler.post { gestureNow(g) }

    /** A key of a Bluetooth keyboard (03 §5.4). */
    fun key(press: KeyPress) = scheduler.post { keyNow(press) }

    /** The mouse wheel turned by [notches], positive further down, with the pointer where it is (03 §5.4). */
    fun wheel(notches: Int) = scheduler.post { wheelNow(notches) }

    /** What the connection knows about the glasses. */
    fun updateGlasses(status: GlassesStatus) = scheduler.post {
        if (status == glasses) return@post
        val was = glasses
        glasses = status
        _glasses.value = status
        if (was.connected != status.connected) {
            glassesGone = !status.connected
            visible?.let { syncMedia(it) }
        }
        for (s in sessions.values.toList()) {
            val internal = s.internal ?: continue
            try {
                internal.onGlassesStatus(status, s.context)
            } catch (e: RuntimeException) {
                crash(s, e)
            }
        }
    }

    /**
     * App packages were installed, updated or removed (09): a running app whose package is gone or has
     * another version now ends, and the launcher lists the installed apps again.
     */
    fun packagesChanged() = scheduler.post {
        icons.clear()
        val current = installed.apps.associateBy { WATCH_PREFIX + it.id }
        for (s in sessions.values.toList()) {
            if (s.app == null || s.entryId in watchApps) continue
            val now = current[s.entryId]
            when {
                now == null -> stop(s, "wurde entfernt")
                now.version != s.manifest.version -> stop(s, "wurde aktualisiert")
            }
        }
        refreshLauncher()
    }

    /** The page app [appId] shows right now, with every toggle and tick; for tests. Call on the app thread. */
    internal fun currentPageOf(appId: String): Page? = sessions.values.firstOrNull { it.manifest.id == appId }?.pages?.current

    // --- Launcher -------------------------------------------------------------------------------

    /** The order the wearer gave the apps, kept in the launcher's own store (03 §5). */
    override fun order(): List<String> = try {
        (ports.storage(Launcher.ID).get(ORDER_KEY) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    } catch (e: RuntimeException) {
        ports.log("Starter: Reihenfolge nicht lesbar – ${e.message}")
        emptyList()
    }

    override fun saveOrder(ids: List<String>) {
        try {
            ports.storage(Launcher.ID).set(ORDER_KEY, JsonArray(ids.map { JsonPrimitive(it) }))
        } catch (e: RuntimeException) {
            ports.log("Starter: Reihenfolge nicht gespeichert – ${e.message}")
        }
    }

    // --- Moving apps in the launcher (03 §5) -----------------------------------------------------

    /**
     * A finger or a mouse button came down at ([x], [y]). On an app of the launcher it waits [HOLD_MS] and
     * lifts that app, unless it moved meanwhile; a mouse button's click waits for its release there. A mouse
     * button elsewhere clicks at once, as before.
     */
    private fun pressNow(x: Int, y: Int, click: Boolean) {
        // A release that never came (the watch lost the window): the app lands before anything new.
        dropMoving()
        hold = null
        pointer = Pair(x, y)
        val scr = currentScreen()
        val tile = scr?.let { launcherTileAt(it, x, y) }
        if (tile == null) {
            if (click) clickNow(x, y)
            return
        }
        val h = Hold(tile, x, y, click)
        hold = h
        scheduler.postDelayed(HOLD_MS) { if (hold === h && h.still) liftNow(h) }
    }

    private fun releaseNow() {
        val h = hold
        hold = null
        if (dropMoving()) return
        // A short press of the mouse on an app: the click it was, if the pointer is still on that app.
        if (h == null || !h.click) return
        val p = pointer ?: return
        val scr = currentScreen() ?: return
        if (launcherTileAt(scr, p.first, p.second) == h.block) clickNow(p.first, p.second)
    }

    /** The block of the launcher's app at ([x], [y]), while the launcher is on the glasses without a host page over it. */
    private fun launcherTileAt(scr: Screen, x: Int, y: Int): String? {
        if (scr.host != null || scr.session?.isLauncher != true) return null
        val block = layoutOf(scr).hit(x, y, scr.view.scroll)?.target?.block ?: return null
        return block.takeIf { launcher?.entryAt(it) != null }
    }

    /** The held app lifts: the watch ticks, as a phone does, and the app follows the pointer from now on. */
    private fun liftNow(h: Hold) {
        hold = null
        val l = launcher ?: return
        val s = sessions[LAUNCHER_ENTRY] ?: return
        val scr = currentScreen() ?: return
        if (scr.session !== s || scr.host != null) return
        val lifted = try {
            l.lift(h.block, s.context)
        } catch (e: RuntimeException) {
            ports.log("Starter: ${e.message}")
            false
        }
        if (!lifted) return
        ports.vibrate(Vibration.TICK)
        focusLifted()
        invalidate()
        publish()
    }

    /**
     * The lifted app goes to the cell under the pointer, among the apps of its kind; the others move up or
     * down by one. A cell right of the last app, or below it, means the end, as on a phone.
     */
    private fun dragNow() {
        val l = launcher ?: return
        val s = sessions[LAUNCHER_ENTRY] ?: return
        val scr = currentScreen() ?: return
        val p = pointer ?: return
        if (scr.session !== s || scr.host != null) return
        val group = layoutOf(scr).boxes.filter { it.tile && l.canTake(it.block.id) }
        if (group.isEmpty()) return
        val first = group.first().rect
        val m = PageMetrics
        val col = Math.floorDiv(p.first - first.x, m.TILE_W).coerceIn(0, m.TILE_COLUMNS - 1)
        val row = Math.floorDiv(p.second + scr.view.scroll - first.y, m.TILE_H).coerceAtLeast(0)
        val target = group[(row * m.TILE_COLUMNS + col).coerceAtMost(group.size - 1)]
        val moved = try {
            l.moveTo(target.block.id, s.context)
        } catch (e: RuntimeException) {
            ports.log("Starter: ${e.message}")
            false
        }
        if (!moved) return
        focusLifted()
        invalidate()
        publish()
    }

    /** Puts a lifted app down where it is; false if none was lifted. */
    private fun dropMoving(): Boolean {
        val l = launcher ?: return false
        val app = l.lifted ?: return false
        val s = sessions[LAUNCHER_ENTRY] ?: return false
        try {
            l.drop(s.context)
        } catch (e: RuntimeException) {
            ports.log("Starter: ${e.message}")
        }
        focusLifted(l.blockOf(app))
        invalidate()
        publish()
        return true
    }

    /** The focus on the lifted app (or on [block]), wherever its cell is now. */
    private fun focusLifted(block: String? = launcher?.lifted?.let { launcher?.blockOf(it) }) {
        val s = sessions[LAUNCHER_ENTRY] ?: return
        val page = s.pages.current ?: return
        if (block == null) return
        val view = s.view(page.id)
        view.focus = FocusTarget(block)
        view.fresh = false
    }

    override fun entries(): List<LaunchEntry> =
        watchApps.map { (id, app) ->
            val m = app.manifest
            LaunchEntry(id, m.name, LaunchKind.WATCH, id in sessions, icon = iconOf(m) { ports.asset(PackageManifest.iconPath(m.id)) })
        } +
            installed.apps.map { WATCH_PREFIX + it.id to it }.filter { (id, _) -> id !in watchApps }.map { (id, m) ->
                LaunchEntry(id, m.name, LaunchKind.WATCH, id in sessions, icon = iconOf(m) { installed.icon(m.id) })
            } +
            // Even Hub apps have no icon source yet (05 §3): their initials.
            evenHub.apps.map {
                val id = EVEN_HUB_PREFIX + it.id
                LaunchEntry(id, it.name, LaunchKind.EVEN_HUB, id in sessions, detail = it.location.label)
            }

    /**
     * The launcher icon of app [m], decoded and scaled to the cell's icon size once per app version
     * (cleared by [packagesChanged]); null if it has none or it does not decode.
     */
    private fun iconOf(m: AppManifest, load: () -> ByteArray?): GrayRaster? {
        val key = "${m.id}@${m.version}"
        if (key in icons) return icons[key]
        val bytes = try {
            load()
        } catch (e: Exception) {
            ports.log("${m.name}: Symbol ließ sich nicht laden – ${e.message}")
            null
        }
        val picture = bytes?.let { ports.decodeImage(it) }
        if (bytes != null && picture == null) ports.log("${m.name}: Symbol ließ sich nicht lesen")
        val icon = picture?.let { GrayImages.scale(it, PageMetrics.TILE_ICON, PageMetrics.TILE_ICON) }
        icons[key] = icon
        return icon
    }

    private fun showLauncher(notice: String?) {
        var s = sessions[LAUNCHER_ENTRY]
        if (s == null) {
            val l = Launcher(this)
            launcher = l
            s = Session(LAUNCHER_ENTRY, l.manifest, app = null, internal = l)
            s.granted = Permission.entries.toSet()
            start(s)
        } else {
            makeVisible(s)
        }
        if (notice != null) showToast(s, notice, NOTICE_MS)
    }

    private fun refreshLauncher() {
        val s = sessions[LAUNCHER_ENTRY] ?: return
        val l = launcher ?: return
        if (s.pages.page(Launcher.PAGE) == null) return
        try {
            l.refresh(s.context)
        } catch (e: RuntimeException) {
            ports.log("Starter: ${e.message}")
        }
    }

    // --- Lifecycle ------------------------------------------------------------------------------

    private fun launchNow(entryId: String) {
        sessions[entryId]?.let {
            makeVisible(it)
            return
        }
        val s = when {
            entryId.startsWith(WATCH_PREFIX) -> {
                val entry = watchApps[entryId]
                if (entry == null) {
                    openInstalled(entryId)?.let { launchSession(it) }
                    return
                }
                val app = try {
                    entry.factory()
                } catch (e: RuntimeException) {
                    return ports.log("${entry.manifest.name} ließ sich nicht anlegen: $e")
                }
                Session(entryId, app.manifest, app = app, internal = null)
            }
            entryId.startsWith(EVEN_HUB_PREFIX) -> {
                val internal = evenHub.open(entryId.removePrefix(EVEN_HUB_PREFIX)) ?: return ports.log("Even-Hub-App $entryId ist nicht installiert")
                Session(entryId, internal.manifest, app = null, internal = internal)
            }
            else -> return ports.log("Unbekannte App $entryId")
        }
        launchSession(s)
    }

    /** A session for the installed package of [entryId] (09), or null if it is gone or does not load. */
    private fun openInstalled(entryId: String): Session? {
        val id = entryId.removePrefix(WATCH_PREFIX)
        val name = installed.apps.firstOrNull { it.id == id }?.name ?: id
        val opened = try {
            installed.open(id)
        } catch (e: Exception) {
            ports.log("$name: ${e.message}")
            showLauncher("$name lässt sich nicht starten")
            return null
        }
        if (opened == null) {
            ports.log("Unbekannte App $entryId")
            return null
        }
        return Session(entryId, opened.app.manifest, app = opened.app, internal = null, assets = opened.asset)
    }

    /** Loads [s]'s pages and starts it, after asking for its permissions if needed. */
    private fun launchSession(s: Session) {
        loadUi(s)
        val wanted = s.manifest.permissions
        if (s.internal != null) {
            // Even Hub apps asked at installation (05 §4.4); the launcher needs nothing.
            s.granted = wanted
            start(s)
            return
        }
        val answer = ports.grantedPermissions(s.manifest.id, s.manifest.version)
        if (wanted.isEmpty() || answer != null) {
            s.granted = answer.orEmpty() intersect wanted
            start(s)
        } else {
            askPermissions(s, wanted)
        }
    }

    /** Loads the Baukasten pages of [s]'s manifest before the app starts. */
    private fun loadUi(s: Session) {
        val path = s.manifest.ui ?: return
        val bytes = s.assets(path)
        if (bytes == null) {
            ports.log("${s.manifest.name}: Seiten $path fehlen")
            return
        }
        try {
            val project = BaukastenProject.parse(bytes.toString(Charsets.UTF_8))
            s.pages.define(project.pages)
            s.project = project
            syncImages(s)
        } catch (e: BaukastenFormatException) {
            ports.log("${s.manifest.name}: Seiten $path unlesbar – ${e.message}")
        } catch (e: CommandException) {
            ports.log("${s.manifest.name}: Seiten $path ungültig – ${e.message}")
        }
    }

    private fun start(s: Session) {
        sessions[s.entryId] = s
        s.startedAtMs = nowMs()
        makeVisible(s)
        s.started = true
        deliver(s, AppEvent.Start)
        if (s.visible) deliver(s, AppEvent.Visible)
        val timeout = s.internal?.startTimeoutMs ?: FIRST_PAGE_MS
        scheduler.postDelayed(timeout) { firstPageDue(s, timeout) }
        if (!s.isLauncher) refreshLauncher()
        publish()
    }

    /** Two seconds after the start (02 §3): the app must show a page by now. */
    private fun firstPageDue(s: Session, timeout: Long) {
        if (s.ended || s.pages.current != null) return
        val project = s.project
        if (project != null && s.pages.page(project.start) != null) {
            s.pages.show(project.start)
            pageChanged(s, fresh = true)
            return
        }
        ports.log("${s.manifest.name} zeigt nach ${timeout / 1000} s noch keine Seite")
        s.notice = HostPage(
            kind = "host.notResponding",
            page = Page(
                "host.notResponding", "", listOf(
                    Block.Heading("host_h", "App antwortet nicht"),
                    Block.Text("host_t", "Sie wird gleich beendet."),
                    Block.Button("host_close", "Schließen"),
                ),
            ),
            onClick = { stop(s) },
            onBack = { stop(s) },
        )
        if (s === visible) invalidate()
        scheduler.postDelayed(GIVE_UP_MS - FIRST_PAGE_MS) {
            if (!s.ended && s.pages.current == null) stop(s, "antwortet nicht")
        }
    }

    private fun makeVisible(s: Session) {
        if (editor != null) cancelEditor()
        hold = null
        // Leaving the launcher while an app is lifted puts it down where it is.
        if (s !== visible) dropMoving()
        overlay = null
        if (visible === s) {
            invalidate()
            return
        }
        visible?.let { old ->
            old.visible = false
            if (old.started && !old.ended) {
                deliver(old, AppEvent.Hidden)
                pauseTimers(old)
                syncMedia(old)
                phoneMicOff(old)
                syncLocation(old)
            }
        }
        visible = s
        s.visible = true
        if (s.started) {
            resumeTimers(s)
            syncMedia(s)
            syncLocation(s)
            deliver(s, AppEvent.Visible)
        }
        if (s.isLauncher) refreshLauncher()
        cachedLayout = null
        updateInputMode()
        invalidate()
        publish()
    }

    /** Ends [s]: it gets [AppEvent.Stop], its timers end, and the launcher takes over if it was visible. */
    private fun stop(s: Session, reason: String? = null) {
        if (s.ended || s.stopping) return
        s.stopping = true
        if (s.started) deliver(s, AppEvent.Stop)
        s.ended = true
        s.timers.values.forEach { it.token++ }
        s.timers.clear()
        s.videos.values.forEach { it.player.release() }
        s.videos.clear()
        s.webs.values.forEach { it.page.release() }
        s.webs.clear()
        s.phone?.line?.close()
        s.phone = null
        s.location?.stop()
        s.location = null
        s.downloads.values.forEach { it.job?.cancel() }
        s.downloads.clear()
        s.work?.end()
        s.media?.line?.stop()
        s.media = null
        s.stream?.player?.release()
        s.stream = null
        if (question?.session === s) {
            question = null
            ports.cancelText()
        }
        if (editor?.question?.session === s) editor = null
        sessions.remove(s.entryId)
        if (overlay?.session === s) overlay = null
        if (s.isLauncher) launcher = null
        if (visible === s) {
            visible = null
            if (!s.isLauncher) showLauncher(reason?.let { "${s.manifest.name} $it" })
        } else {
            refreshLauncher()
            reason?.let { r -> sessions[LAUNCHER_ENTRY]?.let { showToast(it, "${s.manifest.name} $r", NOTICE_MS) } }
        }
        updateInputMode()
        invalidate()
        publish()
    }

    private fun crash(s: Session, e: Throwable) {
        ports.log("${s.manifest.name} ist abgestürzt: $e")
        stop(s, "ist abgestürzt")
    }

    private fun deliver(s: Session, event: AppEvent) {
        if (s.ended) return
        val internal = s.internal
        if (internal != null) {
            try {
                internal.onEvent(event, s.context)
            } catch (e: RuntimeException) {
                crash(s, e)
            }
            return
        }
        val app = s.app ?: return
        runApp(s, eventName(event)) { app.onEvent(event, s.context) }
    }

    /** Runs app code with the 50/500 ms rule (03 §3); an exception ends the app. */
    private fun runApp(s: Session, what: String, block: () -> Unit) {
        val start = nanoTime()
        try {
            block()
        } catch (e: Exception) {
            crash(s, e)
            return
        } catch (e: LinkageError) {
            // An app package built for another watch app version calls something that is not there.
            crash(s, e)
            return
        }
        val ms = (nanoTime() - start) / 1_000_000L
        when {
            ms > SLOW_STOP_MS -> {
                ports.log("${s.manifest.name}: „$what“ dauerte $ms ms – beendet")
                stop(s, "reagiert zu langsam")
            }
            ms > SLOW_LOG_MS -> ports.log("${s.manifest.name}: „$what“ dauerte $ms ms (erlaubt sind $SLOW_LOG_MS ms)")
        }
    }

    // --- Permissions and menus -------------------------------------------------------------------

    private fun askPermissions(s: Session, wanted: Set<Permission>) {
        val list = wanted.sortedBy { it.ordinal }.joinToString("\n") { "• ${it.label}" }
        fun answer(granted: Set<Permission>) {
            overlay = null
            ports.saveGrantedPermissions(s.manifest.id, s.manifest.version, granted)
            s.granted = granted
            start(s)
        }
        overlay = HostPage(
            kind = "host.permission",
            page = Page(
                "host.permission", "Berechtigung", listOf(
                    Block.Heading("host_h", "${s.manifest.name} möchte:"),
                    Block.Text("host_t", list),
                    Block.Button("host_allow", "Erlauben"),
                    Block.Button("host_deny", "Ablehnen"),
                ),
            ),
            session = s,
            onClick = { block ->
                when (block) {
                    "host_allow" -> answer(wanted)
                    "host_deny" -> answer(emptySet())
                }
            },
            onBack = {
                overlay = null
                invalidate()
            },
        )
        updateInputMode()
        invalidate()
        publish()
    }

    private fun openMenuNow() {
        val s = visible ?: return
        if (s.isLauncher || overlay != null || s.notice != null) return
        val blocks = ArrayList<Block>()
        s.menu.forEachIndexed { i, item -> blocks += Block.Button("host_m$i", item.text) }
        if (blocks.isNotEmpty()) blocks += Block.Divider("host_d")
        blocks += Block.Button("host_apps", "Apps")
        blocks += Block.Button("host_back", "Zurück")
        blocks += Block.Button("host_close", "Schließen")
        val items = s.menu
        overlay = HostPage(
            kind = MENU_KIND,
            page = Page("host.menu", "Menü", blocks),
            session = s,
            onClick = { block ->
                overlay = null
                when (block) {
                    "host_apps" -> showLauncher(null)
                    "host_back" -> backNow()
                    "host_close" -> stop(s)
                    else -> items.getOrNull(block.removePrefix("host_m").toIntOrNull() ?: -1)?.let {
                        deliver(s, AppEvent.Menu(it.id))
                    }
                }
                invalidate()
                publish()
            },
            onBack = {
                overlay = null
                invalidate()
                publish()
            },
        )
        invalidate()
        publish()
    }

    private fun setMenu(s: Session, items: List<MenuItem>) {
        if (items.size > MAX_MENU) throw CommandException.badValue("Höchstens $MAX_MENU Menüeinträge, nicht ${items.size}")
        val ids = HashSet<String>()
        for (item in items) {
            if (item.id.isEmpty() || !ids.add(item.id)) throw CommandException.badValue("Menüeintrag „${item.id}“ fehlt oder kommt doppelt vor")
            if (item.text.isBlank() || item.text.toByteArray(Charsets.UTF_8).size > MAX_MENU_TEXT) {
                throw CommandException.badValue("Menütext „${item.text}“: 1 bis $MAX_MENU_TEXT Byte")
            }
        }
        s.menu = items.toList()
    }

    // --- Navigation and input -------------------------------------------------------------------

    private fun backNow() {
        // Back while an app is lifted only puts it down.
        if (dropMoving()) return
        overlay?.let {
            it.onBack()
            return
        }
        val s = visible ?: return
        s.notice?.let {
            it.onBack()
            return
        }
        val page = s.pages.current
        if (page == null) {
            if (!s.isLauncher) stop(s)
            return
        }
        // A web page on this page goes back in its own history first, like any browser (05 §10).
        s.webs.values.firstOrNull { it.status?.canBack == true && page.block(it.block) != null }?.let {
            it.page.back()
            return
        }
        val history = s.pages.historyIds
        deliver(s, AppEvent.Back(page.id))
        if (s.ended) return
        // Whatever the app did meanwhile, back leaves the page it was on (02 §5); the launcher is home and stays.
        if (history.size <= 1) {
            if (!s.isLauncher) stop(s)
            return
        }
        s.pages.resetHistory(history.dropLast(1))
        pageChanged(s, fresh = false)
    }

    private fun gestureNow(g: Gesture) {
        // A temple or the ring while an app is lifted: it lands where it is.
        if (dropMoving()) return
        val s = visible
        if (s == null && overlay == null) {
            // Nothing on the glasses (the launcher crashed): a tap brings it back.
            if (g.kind == GestureKind.CLICK) showLauncher(null)
            return
        }
        val scr = currentScreen() ?: return
        if (scr.host == null && s != null && inputOf(s) == InputMode.GESTURES && s.pages.current != null) {
            when {
                g.kind == GestureKind.SHORT_THEN_LONG_PRESS -> openMenuNow()
                g.kind == GestureKind.SWIPE_RIGHT -> backNow()
                g.kind == GestureKind.DOUBLE_CLICK && g.source != InputSource.WATCH && s.internal?.ownsDoubleClick != true -> backNow()
                else -> deliver(s, AppEvent.Gesture(g.kind, g.source))
            }
            return
        }
        when (g.kind) {
            GestureKind.SCROLL_DOWN -> moveFocus(scr, forward = true)
            GestureKind.SCROLL_UP -> moveFocus(scr, forward = false)
            GestureKind.CLICK -> clickFocus(scr)
            GestureKind.DOUBLE_CLICK, GestureKind.SWIPE_RIGHT -> backNow()
            GestureKind.SHORT_THEN_LONG_PRESS -> openMenuNow()
            else -> if (scr.host == null && s != null && Sensor.GESTURES in s.subscriptions) {
                deliver(s, AppEvent.Gesture(g.kind, g.source))
            }
        }
    }

    /** A tap on a temple (or Enter): the web page under the pointer is clicked there, else the focus. */
    private fun clickFocus(scr: Screen) {
        pointerWeb(scr)?.let { (w, at) ->
            webClick(scr, w, at)
            return
        }
        val layout = layoutOf(scr)
        settle(layout, scr.view)
        scr.view.focus?.let { activate(scr, it) }
    }

    // --- Keyboard and mouse (03 §5.4) -------------------------------------------------------------

    private fun keyNow(k: KeyPress) {
        editor?.let {
            it.key(k)
            return
        }
        // A key while an app is lifted puts it down, and does nothing else.
        if (dropMoving()) return
        val s = visible
        if (s == null && overlay == null) {
            // Nothing on the glasses (the launcher crashed): Enter or Space brings it back.
            if (!k.repeat && (k.key == Keys.ENTER || k.key == Keys.SPACE)) showLauncher(null)
            return
        }
        val scr = currentScreen() ?: return
        // Back and the app menu stay the host's, whatever the app subscribed to (02 §5); a held key acts
        // once, so the menu does not flicker and back does not run on out of the app.
        if (isBack(k)) {
            if (!k.repeat) backNow()
            return
        }
        if (isMenu(k)) {
            if (k.repeat) return
            if (overlay?.kind == MENU_KIND) overlay?.onBack?.invoke() else openMenuNow()
            return
        }
        if (scr.host == null && s != null && Sensor.KEYS in s.subscriptions) {
            deliver(s, k.toEvent())
            return
        }
        if (scr.host == null && s != null && inputOf(s) == InputMode.GESTURES && s.pages.current != null) {
            gestureKey(k)
            return
        }
        when (k.key) {
            Keys.ARROW_DOWN -> if (!moveFocusInGrid(scr, down = true)) moveFocus(scr, forward = true)
            Keys.ARROW_UP -> if (!moveFocusInGrid(scr, down = false)) moveFocus(scr, forward = false)
            Keys.ARROW_RIGHT -> moveFocus(scr, forward = true)
            Keys.ARROW_LEFT -> moveFocus(scr, forward = false)
            Keys.TAB -> moveFocus(scr, forward = !k.shift)
            // One click per press, like a tap on a temple; the arrows and the page keys repeat.
            Keys.ENTER -> if (!k.repeat) clickFocus(scr)
            Keys.SPACE -> scrollPage(scr, forward = !k.shift)
            Keys.PAGE_DOWN -> scrollPage(scr, forward = true)
            Keys.PAGE_UP -> scrollPage(scr, forward = false)
            Keys.BACKSPACE -> if (!k.repeat) backNow()
            else -> Unit
        }
    }

    /**
     * In the launcher's grid the arrows up and down go to the nearest cell in the row above or below,
     * as on any icon grid; in the last row nothing moves. False where there is no grid or no focus yet,
     * so the caller steps in reading order instead.
     */
    private fun moveFocusInGrid(scr: Screen, down: Boolean): Boolean {
        val layout = layoutOf(scr)
        if (!layout.tiles) return false
        val view = scr.view
        settle(layout, view)
        val from = layout.focusable(view.focus)?.rect ?: return false
        val beyond = layout.focusables.filter { if (down) it.rect.y >= from.bottom else it.rect.bottom <= from.y }
        if (beyond.isEmpty()) return true
        val row = if (down) beyond.minOf { it.rect.y } else beyond.maxOf { it.rect.y }
        val centre = from.x + from.w / 2
        val next = beyond.filter { it.rect.y == row }.minBy { abs(it.rect.x + it.rect.w / 2 - centre) }
        view.focus = next.target
        view.scroll = scrollToShow(layout, view.scroll, next.rect)
        invalidate()
        publish()
        return true
    }

    /** Escape, Alt+Left and the keyboard's own back key. */
    private fun isBack(k: KeyPress) =
        k.key == Keys.ESCAPE || k.key == Keys.BROWSER_BACK || (k.alt && k.key == Keys.ARROW_LEFT)

    /** The context menu key and F10 (for keyboards without one). */
    private fun isMenu(k: KeyPress) = k.key == Keys.CONTEXT_MENU || k.key == Keys.F10

    /**
     * A page that takes gestures (a video) and did not subscribe to keys: the keys a remote has become
     * the watch's gestures. Space, Enter and play/pause tap; right, down and the page key down swipe on
     * (`scrollDown`), left, up and page up back; Backspace goes back. Held, only the swipes repeat.
     */
    private fun gestureKey(k: KeyPress) {
        if (k.repeat && k.key in GESTURE_ONCE_KEYS) return
        val kind = when (k.key) {
            Keys.SPACE, Keys.ENTER, Keys.MEDIA_PLAY_PAUSE, Keys.MEDIA_PLAY, Keys.MEDIA_PAUSE -> GestureKind.CLICK
            Keys.ARROW_RIGHT, Keys.ARROW_DOWN, Keys.PAGE_DOWN, Keys.MEDIA_FAST_FORWARD -> GestureKind.SCROLL_DOWN
            Keys.ARROW_LEFT, Keys.ARROW_UP, Keys.PAGE_UP, Keys.MEDIA_REWIND -> GestureKind.SCROLL_UP
            Keys.BACKSPACE -> {
                backNow()
                return
            }
            else -> return
        }
        gestureNow(Gesture(kind, InputSource.WATCH))
    }

    /** Space and the page keys: the web page under the pointer, else the page by three quarters of its height. */
    private fun scrollPage(scr: Screen, forward: Boolean) {
        pointerWeb(scr)?.let { (w, _) ->
            webScroll(scr, w, forward)
            return
        }
        val layout = layoutOf(scr)
        val step = layout.height * 3 / 4
        if (!scrollView(scr, layout, if (forward) step else -step)) webOnPage(scr)?.let { webScroll(scr, it, forward) }
    }

    /** The wheel: the web page under the pointer scrolls, else the page; at its end a web page on it. */
    private fun wheelNow(notches: Int) {
        val scr = currentScreen() ?: return
        val dy = notches * WHEEL_STEP
        pointerWeb(scr)?.let { (w, _) ->
            pushWebScroll(dy, w.block)
            return
        }
        if (!scrollView(scr, layoutOf(scr), dy) && webOnPage(scr) != null) pushWebScroll(dy)
    }

    /** Scrolls the page on the glasses by [dy]; false at its end. A focus that leaves the view goes. */
    private fun scrollView(scr: Screen, layout: PageLayout, dy: Int): Boolean {
        val view = scr.view
        settle(layout, view)
        val next = (view.scroll + dy).coerceIn(0, layout.maxScroll)
        if (next == view.scroll) return false
        val current = layout.focusable(view.focus)
        view.scroll = next
        if (current != null && (current.rect.bottom <= next || current.rect.y >= next + layout.height)) view.focus = null
        hover()
        invalidate()
        publish()
        return true
    }

    /**
     * Moves the focus to the next or previous target; far apart targets are reached by scrolling first.
     * Over a web page, and where nothing else moves, the web page scrolls by three quarters of its height.
     */
    private fun moveFocus(scr: Screen, forward: Boolean) {
        pointerWeb(scr)?.let { (w, _) ->
            webScroll(scr, w, forward)
            return
        }
        val layout = layoutOf(scr)
        val view = scr.view
        settle(layout, view)
        val targets = layout.focusables
        val step = layout.height * 3 / 4
        val top = view.scroll
        val bottom = view.scroll + layout.height
        val current = layout.focusable(view.focus)
        val candidate = if (forward) {
            if (current != null) targets.getOrNull(targets.indexOf(current) + 1) else targets.firstOrNull { it.rect.y >= top }
        } else {
            if (current != null) targets.getOrNull(targets.indexOf(current) - 1) else targets.lastOrNull { it.rect.bottom <= bottom }
        }
        if (candidate != null) {
            val r = candidate.rect
            val near = if (forward) r.bottom <= bottom + step else r.y >= top - step
            if (near) {
                view.focus = candidate.target
                view.scroll = scrollToShow(layout, view.scroll, r)
                invalidate()
                publish()
                return
            }
        }
        val next = (view.scroll + if (forward) step else -step).coerceIn(0, layout.maxScroll)
        if (next == view.scroll) {
            webOnPage(scr)?.let { webScroll(scr, it, forward) }
            return
        }
        view.scroll = next
        // A target that left the view is nothing to tap any more.
        if (current != null && (current.rect.bottom <= next || current.rect.y >= next + layout.height)) view.focus = null
        invalidate()
        publish()
    }

    private fun hover() {
        val p = pointer ?: return
        val scr = currentScreen() ?: return
        val hit = layoutOf(scr).hit(p.first, p.second, scr.view.scroll) ?: return
        if (hit.target == scr.view.focus) return
        scr.view.focus = hit.target
        scr.view.fresh = false
        invalidate()
        publish()
    }

    /** A click on [target]: buttons, toggles and checklist rows (02 §5, §6.1). */
    private fun activate(scr: Screen, target: FocusTarget) {
        scr.host?.let {
            it.onClick(target.block)
            return
        }
        val s = scr.session ?: return
        val page = s.pages.current ?: return
        when (val block = page.block(target.block)) {
            is Block.Button -> when (val to = block.target) {
                null -> deliver(s, AppEvent.Click(page.id, block.id))
                Block.BACK -> backNow()
                else -> {
                    if (s.pages.page(to) == null) {
                        ports.log("${s.manifest.name}: Knopf „${block.id}“ führt zur unbekannten Seite „$to“")
                        return
                    }
                    s.pages.show(to)
                    pageChanged(s, fresh = true)
                    deliver(s, AppEvent.Navigate(page.id, to, block.id))
                }
            }
            is Block.Toggle -> {
                val next = block.copy(on = !block.on)
                s.pages.update(next)
                invalidate()
                deliver(s, AppEvent.Toggle(page.id, block.id, next.on))
            }
            is Block.List -> {
                if (block.style != ListStyle.CHECKS || target.row !in block.items.indices) return
                val item = block.items[target.row]
                val done = !item.done
                s.pages.update(block.copy(items = block.items.toMutableList().also { it[target.row] = item.copy(done = done) }))
                invalidate()
                deliver(s, AppEvent.Check(page.id, block.id, target.row, done))
            }
            else -> Unit
        }
    }

    // --- Commands -------------------------------------------------------------------------------

    /**
     * Carries out [command] for [s] or throws [CommandException]. Watch apps get here through
     * [AppContext]; remote apps (M5) will send the same commands over the wire.
     */
    private fun execute(s: Session, command: AppCommand) {
        when (command) {
            is AppCommand.DefinePages -> {
                s.pages.define(command.pages)
                pagesChanged(s)
            }
            is AppCommand.Show -> {
                s.pages.show(command.page)
                pageChanged(s, fresh = true)
            }
            is AppCommand.Replace -> {
                s.pages.replace(command.page)
                pageChanged(s, fresh = true)
            }
            is AppCommand.Patch -> {
                s.pages.patch(command.page, command.changes)
                pagesChanged(s)
            }
            is AppCommand.SetBlocks -> {
                s.pages.setBlocks(command.page, command.blocks)
                pagesChanged(s)
            }
            is AppCommand.Toast -> showToast(s, command.text, command.ms)
            is AppCommand.Vibrate -> ports.vibrate(command.pattern)
            is AppCommand.Menu -> setMenu(s, command.items)
            is AppCommand.Buzz -> {
                requirePermission(s, Permission.BUZZER)
                validateNotes(command.notes)
                notYet(s, "Summer")
            }
            is AppCommand.Subscribe -> {
                command.sensor.permission?.let { requirePermission(s, it) }
                s.subscriptions += command.sensor
                when (command.sensor) {
                    Sensor.GESTURES, Sensor.KEYS -> Unit
                    Sensor.LOCATION -> {
                        if (command.rate != s.locationRate) {
                            // A new rate: the GPS starts over with it.
                            s.locationRate = command.rate
                            s.location?.stop()
                            s.location = null
                        }
                        syncLocation(s)
                    }
                    else -> notYet(s, command.sensor.json)
                }
            }
            is AppCommand.Unsubscribe -> {
                s.subscriptions -= command.sensor
                if (command.sensor == Sensor.LOCATION) syncLocation(s)
            }
            is AppCommand.Audio -> {
                requirePermission(s, Permission.MIC)
                if (command.on) notYet(s, "Mikrofon")
            }
            is AppCommand.AskText -> askText(s, command)
            is AppCommand.Video -> video(s, command.block, command.action)
            is AppCommand.Web -> web(s, command.block, command.action)
            is AppCommand.Phone -> phone(s, command.action)
            is AppCommand.Media -> media(s, command.action)
            is AppCommand.Player -> player(s, command.action)
            AppCommand.Close -> scheduler.post { stop(s) }
        }
    }

    private fun requirePermission(s: Session, permission: Permission) {
        if (permission !in s.granted) throw CommandException.denied(permission)
    }

    /** Sensors, microphone and buzzer reach the apps with M6; until then a note in the log, once. */
    private fun notYet(s: Session, what: String) {
        if (s.notedMissing.add(what)) ports.log("${s.manifest.name}: $what ist noch nicht angeschlossen (kommt mit M6)")
    }

    private fun validateNotes(notes: List<BuzzNote>) {
        if (notes.isEmpty() || notes.size > MAX_BUZZ) throw CommandException.badValue("1 bis $MAX_BUZZ Töne, nicht ${notes.size}")
        for (n in notes) {
            if (n.freqHz !in 0..20_000 || n.dutyPercent !in 0..100 || n.ms !in 1..65_535) {
                throw CommandException.badValue("Ton $n ist ungültig")
            }
        }
    }

    private fun refuse(s: Session, command: String, e: CommandException) {
        ports.log("${s.manifest.name}: „$command“ abgelehnt – ${e.message}")
    }

    private fun pagesChanged(s: Session) {
        syncImages(s)
        // New content without a focus (a list that just arrived, a focused block that went): the
        // next drawing focuses the first target in view, so a tap on the temple reaches it.
        s.pages.current?.let { page ->
            val view = s.views[page.id] ?: return@let
            val focus = view.focus
            if (focus != null && page.block(focus.block) == null) view.focus = null
            if (view.focus == null) view.fresh = true
        }
        if (s === visible) {
            cachedLayout = null
            updateInputMode()
            invalidate()
        }
    }

    /** The current page of [s] changed; [fresh]: a new visit, so scroll and focus start over. */
    private fun pageChanged(s: Session, fresh: Boolean) {
        val page = s.pages.current ?: return
        if (fresh) s.views[page.id] = ViewState()
        s.notice = null
        syncImages(s)
        if (s === visible) {
            cachedLayout = null
            updateInputMode()
            invalidate()
            publish()
        }
    }

    private fun showToast(s: Session, text: String, ms: Int) {
        if (text.isBlank()) throw CommandException.badValue("Hinweis ohne Text")
        s.toast = text
        val token = ++s.toastToken
        scheduler.postDelayed(ms.toLong().coerceIn(500, 10_000)) {
            if (s.toastToken == token) {
                s.toast = null
                if (s === visible) invalidate()
            }
        }
        if (s === visible) invalidate()
    }

    // --- Timers ---------------------------------------------------------------------------------

    private fun startTimer(s: Session, tag: String, ms: Long, repeat: Boolean) {
        if (tag.isEmpty()) throw CommandException.badValue("Timer ohne Namen")
        if (ms < 0 || (repeat && ms < MIN_REPEAT_MS)) {
            throw CommandException.badValue("Timer „$tag“: ${ms} ms ist zu kurz (wiederholt mindestens $MIN_REPEAT_MS ms)")
        }
        s.timers.remove(tag)?.let { it.token++ }
        val t = TimerState(tag, ms, repeat)
        s.timers[tag] = t
        if (timersRun(s)) arm(s, t, ms) else t.paused = ms
    }

    private fun arm(s: Session, t: TimerState, delay: Long) {
        val token = ++t.token
        t.dueAt = nowMs() + delay
        t.paused = null
        scheduler.postDelayed(delay) {
            if (s.ended || t.token != token || s.timers[t.tag] !== t) return@postDelayed
            if (t.repeat) arm(s, t, t.ms) else s.timers.remove(t.tag)
            deliver(s, AppEvent.Timer(t.tag))
        }
    }

    /** Hidden apps run timers only with [Permission.BACKGROUND] (02 §3). */
    private fun timersRun(s: Session) = s.visible || s.internal != null || Permission.BACKGROUND in s.granted

    private fun pauseTimers(s: Session) {
        if (timersRun(s)) return
        val now = nowMs()
        for (t in s.timers.values) {
            if (t.paused != null) continue
            t.paused = (t.dueAt - now).coerceAtLeast(0)
            t.token++
        }
    }

    private fun resumeTimers(s: Session) {
        for (t in s.timers.values.toList()) t.paused?.let { arm(s, t, it) }
    }

    // --- Images ---------------------------------------------------------------------------------

    /** Decodes the pictures of image blocks whose source or size changed. */
    private fun syncImages(s: Session) {
        val seen = HashSet<String>()
        for (pageId in s.pages.pageIds()) {
            for (b in s.pages.page(pageId)?.blocks.orEmpty()) {
                if (b !is Block.Image) continue
                seen += b.id
                val key = "${b.src}|${b.w}x${b.h}"
                if (s.imageKeys[b.id] == key) continue
                s.imageKeys[b.id] = key
                val src = b.src
                s.images[b.id] = if (src == null) GrayRaster(b.w, b.h) else loadImage(s, b, src) ?: GrayRaster(b.w, b.h)
            }
        }
        s.images.keys.retainAll(seen)
        s.imageKeys.keys.retainAll(seen)
    }

    private fun loadImage(s: Session, b: Block.Image, src: String): GrayRaster? {
        val bytes = try {
            when {
                src.startsWith("data:") -> {
                    val comma = src.indexOf(',')
                    if (comma < 0 || !src.substring(0, comma).endsWith(";base64")) null
                    else Base64.getDecoder().decode(src.substring(comma + 1))
                }
                src.startsWith("asset:") -> {
                    val file = src.removePrefix("asset:")
                    if (file.contains("..") || file.startsWith("/")) null else s.assets("apps/${s.manifest.id}/$file")
                }
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
        val picture = bytes?.let { ports.decodeImage(it) }
        if (picture == null) {
            ports.log("${s.manifest.name}: Bild „${b.id}“ ließ sich nicht lesen")
            return null
        }
        return GrayImages.scale(picture, b.w, b.h)
    }

    private fun setRaster(s: Session, blockId: String, raster: GrayRaster) {
        val block = s.pages.block(blockId) as? Block.Image
            ?: throw CommandException.unknownBlock(blockId, s.pages.pageOf(blockId) ?: "?")
        if (raster.width != block.w || raster.height != block.h) {
            throw CommandException.badValue("Raster ${raster.width} × ${raster.height} passt nicht zu „$blockId“ (${block.w} × ${block.h})")
        }
        s.images[blockId] = GrayImages.copy(raster)
        if (s === visible) invalidate()
    }

    // --- Fetch ----------------------------------------------------------------------------------

    private fun fetch(s: Session, request: HttpRequest, onResult: (HttpResult) -> Unit) {
        requirePermission(s, Permission.NETWORK)
        if (!request.url.startsWith("https://")) throw CommandException.badValue("Nur https://-Adressen, nicht ${request.url}")
        ports.fetch(request) { result ->
            scheduler.post {
                if (!s.ended) runApp(s, "fetch") { onResult(result) }
            }
        }
    }

    // --- Text from the watch ---------------------------------------------------------------------

    private class TextQuestion(val session: Session, val tag: String, val prompt: String, val suggestions: List<String>)

    private fun askText(s: Session, c: AppCommand.AskText) {
        if (c.tag.isEmpty() || c.tag.length > MAX_TAG) throw CommandException.badValue("Frage braucht einen Namen mit 1 bis $MAX_TAG Zeichen")
        if (c.prompt.length > MAX_PROMPT) throw CommandException.badValue("Frage länger als $MAX_PROMPT Zeichen")
        if (c.suggestions.size > MAX_SUGGESTIONS || c.suggestions.any { it.isBlank() || it.length > MAX_SUGGESTION }) {
            throw CommandException.badValue("Höchstens $MAX_SUGGESTIONS Vorschläge mit je 1 bis $MAX_SUGGESTION Zeichen")
        }
        // The open question, if any, is answered with "cancelled" – after this command, not inside it.
        val old = question
        old?.let { scheduler.post { deliver(it.session, AppEvent.TextInput(it.tag, null)) } }
        val oldOnWatch = old != null && editor == null
        if (editor != null) closeEditor()
        val q = TextQuestion(s, c.tag, c.prompt, c.suggestions)
        question = q
        // With a Bluetooth keyboard the question shows on the glasses, and the wearer types there; the
        // watch takes back its input for the old question (a new one on the watch replaces it anyway).
        if (ports.hardwareKeyboard) {
            if (oldOnWatch) ports.cancelText()
            openEditor(q)
        } else {
            askOnWatch(q)
        }
    }

    /** The watch's own keyboard and voice input (RemoteInput), with a hint on the glasses. */
    private fun askOnWatch(q: TextQuestion) {
        val s = q.session
        ports.askText(q.prompt, q.suggestions) { text ->
            scheduler.post {
                if (question !== q) return@post
                question = null
                // The hint has done its job.
                if (s.toast == TEXT_HINT) {
                    s.toast = null
                    s.toastToken++
                    if (s === visible) invalidate()
                }
                deliver(s, AppEvent.TextInput(q.tag, text?.trim()?.takeIf { it.isNotEmpty() }))
            }
        }
        ports.vibrate(Vibration.TICK)
        showToast(s, TEXT_HINT, TEXT_HINT_MS)
    }

    // --- Text from a Bluetooth keyboard, on the glasses -------------------------------------------

    /**
     * The question of [question] on the glasses: its prompt, the line with a cursor, the suggestions
     * as buttons and a way to the watch's own input. The keyboard edits the line ([key]); Enter answers,
     * Escape (or back) cancels, up and down put the suggestions into the line.
     */
    private inner class TextEditor(val question: TextQuestion) {
        val line = StringBuilder()
        var cursor = 0
        var choice = -1
        val host = HostPage(
            kind = EDITOR_KIND,
            page = page(),
            session = question.session,
            onClick = { block ->
                when {
                    block == EDITOR_WATCH -> toWatch()
                    block.startsWith(EDITOR_SUGGESTION) ->
                        question.suggestions.getOrNull(block.removePrefix(EDITOR_SUGGESTION).toIntOrNull() ?: -1)?.let { answerEditor(it) }
                }
            },
            onBack = { answerEditor(null) },
        ).also {
            // Nothing is focused at first: Enter answers with the line, not with a suggestion.
            it.view.fresh = false
        }

        fun page(): Page {
            val blocks = ArrayList<Block>()
            blocks += Block.Text("host_h", question.prompt.ifBlank { "Text eingeben" })
            // The line stands out, bold and bright.
            blocks += Block.Heading("host_line", line.substring(0, cursor) + EDITOR_CURSOR + line.substring(cursor))
            question.suggestions.forEachIndexed { i, text -> blocks += Block.Button("$EDITOR_SUGGESTION$i", text) }
            blocks += Block.Button(EDITOR_WATCH, "Auf der Uhr eingeben")
            blocks += Block.Text(
                "host_hint",
                "Enter: fertig · Esc: abbrechen" + if (question.suggestions.isNotEmpty()) " · ↑ ↓: Vorschläge" else "",
            )
            return Page(EDITOR_KIND, "Eingabe", blocks)
        }

        fun key(k: KeyPress) {
            when {
                // Held from the key that asked (Enter on a button), Enter must not answer at once.
                k.repeat && (k.key == Keys.ENTER || isBack(k)) -> return
                k.key == Keys.ENTER -> return answerEditor(line.toString())
                isBack(k) -> return answerEditor(null)
                k.key == Keys.BACKSPACE -> if (cursor > 0) {
                    val n = Character.charCount(line.codePointBefore(cursor))
                    line.delete(cursor - n, cursor)
                    cursor -= n
                }
                k.key == Keys.DELETE -> if (cursor < line.length) line.delete(cursor, cursor + Character.charCount(line.codePointAt(cursor)))
                k.key == Keys.ARROW_LEFT -> if (cursor > 0) cursor -= Character.charCount(line.codePointBefore(cursor))
                k.key == Keys.ARROW_RIGHT -> if (cursor < line.length) cursor += Character.charCount(line.codePointAt(cursor))
                k.key == Keys.HOME -> cursor = 0
                k.key == Keys.END -> cursor = line.length
                k.key == Keys.ARROW_DOWN || (k.key == Keys.TAB && !k.shift) -> suggest(+1)
                k.key == Keys.ARROW_UP || (k.key == Keys.TAB && k.shift) -> suggest(-1)
                k.plain && k.text.isNotEmpty() && line.length + k.text.length <= MAX_EDITOR_TEXT -> {
                    line.insert(cursor, k.text)
                    cursor += k.text.length
                }
                else -> return
            }
            refresh()
        }

        /** The next or previous suggestion goes into the line, the cursor at its end. */
        private fun suggest(step: Int) {
            val all = question.suggestions
            if (all.isEmpty()) return
            choice = if (choice < 0) (if (step > 0) 0 else all.lastIndex) else (choice + step).mod(all.size)
            line.setLength(0)
            line.append(all[choice])
            cursor = line.length
        }

        fun refresh() {
            host.page = page()
            if (overlay === host) invalidate()
        }

        /** The wearer would rather use the watch: its input opens for the same question. */
        private fun toWatch() {
            closeEditor()
            askOnWatch(question)
        }
    }

    private fun openEditor(q: TextQuestion) {
        val e = TextEditor(q)
        editor = e
        overlay = e.host
        updateInputMode()
        invalidate()
        publish()
    }

    /** Takes the editor off the glasses; its question stays open. */
    private fun closeEditor() {
        val e = editor ?: return
        editor = null
        if (overlay === e.host) overlay = null
        updateInputMode()
        invalidate()
        publish()
    }

    /** The editor's answer: [text], or null when the wearer cancelled. */
    private fun answerEditor(text: String?) {
        val e = editor ?: return
        closeEditor()
        val q = e.question
        if (question !== q) return
        question = null
        deliver(q.session, AppEvent.TextInput(q.tag, text?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** Something else takes the glasses (another app): the open question counts as cancelled. */
    private fun cancelEditor() {
        val q = editor?.question ?: return
        closeEditor()
        if (question !== q) return
        question = null
        scheduler.post { deliver(q.session, AppEvent.TextInput(q.tag, null)) }
    }

    // --- Video ----------------------------------------------------------------------------------

    /** A video playing into image block [block] (03 §10). */
    private class ActiveVideo(val block: String) {
        lateinit var player: VideoPlayer
        var state = VideoState.LOADING

        /** Paused by the app. */
        var paused = false

        /** Paused by the host while the app is hidden or the glasses are gone; resumes by itself. */
        var held = false
    }

    private fun video(s: Session, block: String, action: VideoAction) {
        if (action is VideoAction.Play) {
            play(s, block, action)
            return
        }
        val v = s.videos[block] ?: throw CommandException.badValue("In „$block“ läuft kein Video")
        when (action) {
            VideoAction.Pause -> {
                v.paused = true
                v.player.pause()
            }
            VideoAction.Resume -> {
                v.paused = false
                if (!v.held) v.player.resume()
            }
            is VideoAction.Seek -> v.player.seekTo(action.positionMs.coerceAtLeast(0))
            is VideoAction.Profile -> v.player.setProfile(action.profile)
            VideoAction.Stop -> s.videos.remove(block)?.player?.release()
            is VideoAction.Play -> Unit
        }
    }

    private fun play(s: Session, block: String, a: VideoAction.Play) {
        val image = s.pages.block(block) as? Block.Image
            ?: throw CommandException.unknownBlock(block, s.pages.pageOf(block) ?: s.pages.current?.id ?: "?")
        when {
            a.src.startsWith("https://") -> requirePermission(s, Permission.NETWORK)
            a.src.startsWith("test:") -> Unit
            else -> throw CommandException.badValue("Video-Adresse muss mit https:// oder test: beginnen, nicht „${a.src.take(40)}“")
        }
        s.videos.remove(block)?.player?.release()
        // A new video starts on a black picture, not on the last one of the previous.
        s.images[block] = GrayRaster(image.w, image.h)
        if (s === visible) invalidate()
        val v = ActiveVideo(block)
        val listener = object : VideoListener {
            override fun onFrame(raster: GrayRaster) = scheduler.post { videoFrame(s, v, raster) }

            override fun onState(state: VideoState, positionMs: Long, durationMs: Long, message: String?) =
                scheduler.post { videoState(s, v, state, positionMs, durationMs, message) }
        }
        val request = VideoRequest(a.src, image.w, image.h, a.profile, a.sound, a.startMs.coerceAtLeast(0))
        v.player = try {
            ports.video.open(request, listener)
        } catch (e: RuntimeException) {
            throw CommandException.badValue("Video ließ sich nicht starten: ${e.message}")
        }
        s.videos[block] = v
        if (!mediaRuns(s)) {
            v.held = true
            v.player.pause()
        }
    }

    private fun videoFrame(s: Session, v: ActiveVideo, raster: GrayRaster) {
        if (s.ended || s.videos[v.block] !== v) return
        val image = s.pages.block(v.block) as? Block.Image ?: return
        // The app made the block another size meanwhile: this picture no longer fits.
        if (raster.width != image.w || raster.height != image.h) return
        s.images[v.block] = raster
        // Pictures go out as fast as they come (the player keeps to its profile, the core sends the newest),
        // unless a menu or notice hides them.
        if (s === visible && s.pages.current?.block(v.block) != null) {
            invalidate(if (overlay == null && s.notice == null) VIDEO_INTERVAL_MS else RENDER_INTERVAL_MS)
        }
    }

    private fun videoState(s: Session, v: ActiveVideo, state: VideoState, positionMs: Long, durationMs: Long, message: String?) {
        if (s.ended || s.videos[v.block] !== v) return
        v.state = state
        if (state == VideoState.ERROR) {
            s.videos.remove(v.block)
            v.player.release()
            ports.log("${s.manifest.name}: Video in „${v.block}“ – $message")
        }
        deliver(s, AppEvent.Video(v.block, state, positionMs, durationMs, message))
    }

    /** Videos play (and web pages work) only while their app is on the glasses and the glasses are there (akku, data). */
    private fun mediaRuns(s: Session) = s.visible && !glassesGone

    private fun syncVideos(s: Session) {
        val run = mediaRuns(s)
        for (v in s.videos.values) {
            if (!run && !v.held && !v.paused && v.state != VideoState.ENDED) {
                v.held = true
                v.player.pause()
            } else if (run && v.held) {
                v.held = false
                if (!v.paused) v.player.resume()
            }
        }
    }

    private fun videoSearch(s: Session, query: String, onResult: (VideoSearchResult) -> Unit) {
        requirePermission(s, Permission.NETWORK)
        if (query.isBlank() || query.length > MAX_QUERY) throw CommandException.badValue("Suche mit 1 bis $MAX_QUERY Zeichen")
        ports.video.search(query.trim()) { result ->
            scheduler.post {
                if (!s.ended) runApp(s, "videoSearch") { onResult(result) }
            }
        }
    }

    // --- Web pages ------------------------------------------------------------------------------

    /** A web page in image block [block] (05 §10, M7), painted at [width] × [height]. */
    private class ActiveWeb(val block: String, val width: Int, val height: Int) {
        lateinit var page: WebPage

        /** What the page last reported. */
        var status: WebStatus? = null

        /** Resting while its app is hidden or the glasses are gone. */
        var held = false
    }

    private fun web(s: Session, block: String, action: WebAction) {
        if (action is WebAction.Open) {
            openWeb(s, block, action)
            return
        }
        val w = s.webs[block] ?: throw CommandException.badValue("In „$block“ ist keine Web-Seite offen")
        when (action) {
            WebAction.Back -> w.page.back()
            WebAction.Forward -> w.page.forward()
            WebAction.Reload -> w.page.reload()
            is WebAction.Scroll -> {
                if (abs(action.dy) > MAX_WEB_SCROLL) throw CommandException.badValue("Höchstens $MAX_WEB_SCROLL Pixel auf einmal scrollen")
                if (action.dy != 0) w.page.scrollBy(action.dy)
            }
            is WebAction.Tap -> {
                if (action.x !in 0 until w.width || action.y !in 0 until w.height) {
                    throw CommandException.badValue("Stelle (${action.x}, ${action.y}) liegt nicht auf „$block“ (${w.width} × ${w.height})")
                }
                w.page.tap(action.x, action.y)
            }
            is WebAction.Type -> {
                if (action.text.length > MAX_WEB_TEXT) throw CommandException.badValue("Höchstens $MAX_WEB_TEXT Zeichen auf einmal")
                w.page.type(action.text, action.enter)
            }
            is WebAction.Key -> {
                if (action.key.isEmpty() || action.key.length > MAX_KEY_NAME) throw CommandException.badValue("Unbekannte Taste „${action.key.take(20)}“")
                w.page.key(action.key, action.shift, action.ctrl, action.alt)
            }
            is WebAction.Insert -> {
                if (action.text.length > MAX_WEB_TEXT) throw CommandException.badValue("Höchstens $MAX_WEB_TEXT Zeichen auf einmal")
                if (action.text.isNotEmpty()) w.page.insert(action.text)
            }
            is WebAction.Reader -> w.page.setReader(action.on)
            is WebAction.Contrast -> w.page.setContrast(action.contrast)
            WebAction.Stop -> s.webs.remove(block)?.page?.release()
            is WebAction.Open -> Unit
        }
    }

    private fun openWeb(s: Session, block: String, a: WebAction.Open) {
        val image = s.pages.block(block) as? Block.Image
            ?: throw CommandException.unknownBlock(block, s.pages.pageOf(block) ?: s.pages.current?.id ?: "?")
        val url = a.url.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length > MAX_URL) {
            throw CommandException.badValue("Web-Adresse muss mit https:// oder http:// beginnen, nicht „${url.take(40)}“")
        }
        requirePermission(s, Permission.NETWORK)
        s.webs[block]?.let {
            // The block shows a page already: the new one joins its history, as when typing an address.
            it.page.open(url, a.reader)
            return
        }
        if (image.w < MIN_WEB_W || image.h < MIN_WEB_H) {
            throw CommandException.badValue("„$block“ ist zu klein für eine Web-Seite (mindestens $MIN_WEB_W × $MIN_WEB_H)")
        }
        // A new page starts on a black picture.
        s.images[block] = GrayRaster(image.w, image.h)
        if (s === visible) invalidate()
        val w = ActiveWeb(block, image.w, image.h)
        val listener = object : WebListener {
            override fun onFrame(raster: GrayRaster) = scheduler.post { webFrame(s, w, raster) }

            override fun onState(status: WebStatus) = scheduler.post { webState(s, w, status) }
        }
        w.page = try {
            ports.web.open(WebRequest(url, image.w, image.h, a.reader), listener)
        } catch (e: RuntimeException) {
            throw CommandException.badValue("Web-Seite ließ sich nicht öffnen: ${e.message}")
        }
        s.webs[block] = w
        if (!mediaRuns(s)) {
            w.held = true
            w.page.setActive(false)
        }
    }

    private fun webFrame(s: Session, w: ActiveWeb, raster: GrayRaster) {
        if (s.ended || s.webs[w.block] !== w) return
        val image = s.pages.block(w.block) as? Block.Image ?: return
        // The app made the block another size meanwhile: this picture no longer fits.
        if (raster.width != image.w || raster.height != image.h) return
        s.images[w.block] = raster
        if (s === visible && s.pages.current?.block(w.block) != null) invalidate()
    }

    private fun webState(s: Session, w: ActiveWeb, status: WebStatus) {
        if (s.ended || s.webs[w.block] !== w) return
        w.status = status
        if (status.state == WebState.ERROR) ports.log("${s.manifest.name}: Web-Seite in „${w.block}“ – ${status.message}")
        deliver(
            s,
            AppEvent.Web(
                w.block, status.state, status.url, status.title, status.progress, status.canBack, status.canForward,
                status.reader, status.readable, status.field, status.message,
            ),
        )
    }

    /** The web page under ([x], [y]) of the app area, if any, and that point in the page's pixels. */
    private fun webAt(scr: Screen, x: Int, y: Int): Pair<ActiveWeb, Pair<Int, Int>>? {
        if (scr.host != null) return null
        val s = scr.session ?: return null
        if (s.webs.isEmpty()) return null
        val layout = layoutOf(scr)
        val py = y + scr.view.scroll
        for (w in s.webs.values) {
            val r = layout.box(w.block)?.rect ?: continue
            if (r.contains(x, py)) return w to Pair(x - r.x, py - r.y)
        }
        return null
    }

    /** The web page under the pointer. */
    private fun pointerWeb(scr: Screen): Pair<ActiveWeb, Pair<Int, Int>>? = pointer?.let { (x, y) -> webAt(scr, x, y) }

    /** The first web page on the page on the glasses (no host page over it). */
    private fun webOnPage(scr: Screen): ActiveWeb? {
        if (scr.host != null) return null
        return scr.session?.webs?.values?.firstOrNull { scr.page.block(it.block) != null }
    }

    private fun webClick(scr: Screen, w: ActiveWeb, at: Pair<Int, Int>) {
        val s = scr.session ?: return
        deliver(s, AppEvent.ImageClick(scr.page.id, w.block, at.first, at.second))
    }

    /** A temple swipe over a web page: three quarters of its height on or back. */
    private fun webScroll(scr: Screen, w: ActiveWeb, forward: Boolean) {
        val s = scr.session ?: return
        val step = w.height * 3 / 4
        deliver(s, AppEvent.ImageScroll(scr.page.id, w.block, if (forward) step else -step))
    }

    /**
     * The pointer pushes past the edge over a web page, or the wheel turns: steps are gathered for
     * [PUSH_SCROLL_MS] into one scroll of the page in [block] (null: the first on the page).
     */
    private fun pushWebScroll(dy: Int, block: String? = null) {
        pushedScroll += dy
        if (block != null) pushedBlock = block
        if (pushScheduled) return
        pushScheduled = true
        scheduler.postDelayed(PUSH_SCROLL_MS) {
            pushScheduled = false
            val dyAll = pushedScroll
            val wanted = pushedBlock
            pushedScroll = 0
            pushedBlock = null
            val scr = currentScreen() ?: return@postDelayed
            if (scr.host != null) return@postDelayed
            val s = scr.session ?: return@postDelayed
            val w = wanted?.let { b -> s.webs[b]?.takeIf { scr.page.block(b) != null } } ?: webOnPage(scr) ?: return@postDelayed
            if (dyAll != 0) deliver(s, AppEvent.ImageScroll(scr.page.id, w.block, dyAll))
        }
    }

    /** Videos play and web pages work only while their app is on the glasses and the glasses are there. */
    private fun syncMedia(s: Session) {
        syncVideos(s)
        val run = mediaRuns(s)
        for (w in s.webs.values) {
            if (w.held == run) {
                w.held = !run
                w.page.setActive(run)
            }
        }
    }

    // --- GPS, files and downloads (interface 5) -----------------------------------------------------

    /**
     * The watch's GPS runs for [s] while it subscribed to [Sensor.LOCATION] and is on the glasses: a hidden
     * app gets no positions, and the receiver rests. The rate is in seconds (03 §14).
     */
    private fun syncLocation(s: Session) {
        val wanted = !s.ended && s.started && s.visible && Sensor.LOCATION in s.subscriptions
        val line = s.location
        if (wanted && line == null) {
            val seconds = if (s.locationRate <= 0) 1 else s.locationRate.coerceAtMost(MAX_LOCATION_SECONDS)
            lateinit var started: LocationLine
            started = ports.location.start(
                seconds * 1000L,
                object : LocationListener {
                    override fun onFix(fix: AppEvent.Location) = scheduler.post {
                        if (s.location === started && !s.ended) deliver(s, fix)
                    }

                    override fun onNotice(message: String) = scheduler.post {
                        if (s.location !== started || s.ended) return@post
                        ports.log("${s.manifest.name}: $message")
                        showToast(s, message, NOTICE_MS)
                    }
                },
            )
            s.location = started
        } else if (!wanted && line != null) {
            s.location = null
            line.stop()
        }
    }

    /** A download of an app; [job] is set right after it started. */
    private class ActiveDownload(val tag: String, val name: String) {
        var job: DownloadJob? = null
    }

    /** The folder of [s]'s large files, or null where the host keeps none. */
    private fun filesOf(s: Session): File? = ports.files(s.manifest.id)

    private fun download(s: Session, tag: String, url: String, name: String, wifiOnly: Boolean) {
        requirePermission(s, Permission.NETWORK)
        if (tag.isEmpty() || tag.length > MAX_TAG) throw CommandException.badValue("Download braucht einen Namen mit 1 bis $MAX_TAG Zeichen")
        if (!url.startsWith("https://") || url.length > MAX_URL) throw CommandException.badValue("Nur https://-Adressen, nicht $url")
        if (!AppFiles.isValidName(name)) throw CommandException.badValue("Ungültiger Dateiname „$name“")
        val dir = filesOf(s) ?: throw CommandException.badValue("Diese Uhr-App hat keinen Ordner für Dateien")
        s.downloads.remove(tag)?.job?.cancel()
        val active = ActiveDownload(tag, name)
        s.downloads[tag] = active
        active.job = ports.downloads.start(
            DownloadRequest(url, File(dir, name), wifiOnly),
            object : DownloadListener {
                override fun onProgress(bytes: Long, total: Long) = scheduler.post {
                    if (s.downloads[tag] === active && !s.ended) deliver(s, AppEvent.Download(tag, DownloadState.RUNNING, bytes, total))
                }

                override fun onEnd(state: DownloadState, bytes: Long, total: Long, message: String?) = scheduler.post {
                    if (s.downloads[tag] !== active || s.ended) return@post
                    s.downloads.remove(tag)
                    if (state == DownloadState.FAILED) ports.log("${s.manifest.name}: Download $name – $message")
                    deliver(s, AppEvent.Download(tag, state, bytes, total, message))
                }
            },
        )
    }

    private fun workQueue(s: Session): WorkQueue =
        s.work ?: WorkQueue(ports::runWork, scheduler::post, { tag, block -> runApp(s, "work $tag", block) }).also { s.work = it }

    // --- Music on the watch (interface 6) -----------------------------------------------------------

    /** An app's following of the players ([HostPorts.media]). */
    private class ActiveMedia {
        var line: MediaLine? = null
    }

    /** An app's own audio stream ([HostPorts.streams]). */
    private class ActiveStream(val url: String) {
        var player: StreamPlayer? = null
    }

    private fun media(s: Session, action: MediaAction) {
        requirePermission(s, Permission.MEDIA)
        when (action) {
            MediaAction.Follow -> {
                if (s.media != null) return
                val active = ActiveMedia()
                s.media = active
                active.line = ports.media.follow { snapshot ->
                    scheduler.post {
                        if (s.media !== active || s.ended) return@post
                        deliver(s, AppEvent.Media(snapshot.players, snapshot.volume, snapshot.maxVolume, snapshot.output, snapshot.message))
                    }
                }
            }
            MediaAction.Unfollow -> {
                s.media?.line?.stop()
                s.media = null
            }
            else -> {
                checkMedia(action)
                ports.media.command(action)?.let { note ->
                    ports.log("${s.manifest.name}: Musik – $note")
                    if (s === visible) showToast(s, note, NOTICE_MS)
                }
            }
        }
    }

    private fun checkMedia(action: MediaAction) {
        fun app(app: String?) {
            if (app != null && (app.length > MAX_APP_NAME || !MediaAction.APP.matches(app))) {
                throw CommandException.badValue("„${app.take(60)}“ ist kein Paketname einer App auf der Uhr")
            }
        }
        when (action) {
            is MediaAction.Play -> app(action.app)
            is MediaAction.Pause -> app(action.app)
            is MediaAction.PlayPause -> app(action.app)
            is MediaAction.Next -> app(action.app)
            is MediaAction.Previous -> app(action.app)
            is MediaAction.Seek -> {
                app(action.app)
                if (action.positionMs < 0) throw CommandException.badValue("Position ${action.positionMs} ist ungültig")
            }
            is MediaAction.Search -> {
                app(action.app)
                if (action.query.isBlank() || action.query.length > MediaAction.MAX_QUERY) {
                    throw CommandException.badValue("Suche braucht 1 bis ${MediaAction.MAX_QUERY} Zeichen")
                }
            }
            is MediaAction.Open -> app(action.app)
            is MediaAction.Volume -> if (action.steps == 0 || abs(action.steps) > MAX_VOLUME_STEPS) {
                throw CommandException.badValue("Lautstärke um 1 bis $MAX_VOLUME_STEPS Stufen, nicht ${action.steps}")
            }
            MediaAction.Follow, MediaAction.Unfollow -> Unit
        }
    }

    private fun player(s: Session, action: PlayerAction) {
        when (action) {
            is PlayerAction.Play -> {
                requirePermission(s, Permission.NETWORK)
                val url = action.url
                if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length > MAX_URL) {
                    throw CommandException.badValue("Ein Stream braucht eine http(s)-Adresse, nicht ${url.take(60)}")
                }
                if (action.title.length > MAX_STREAM_TITLE) throw CommandException.badValue("Name des Streams höchstens $MAX_STREAM_TITLE Zeichen")
                s.stream?.player?.release()
                val active = ActiveStream(url)
                s.stream = active
                active.player = ports.streams.open(url, action.title.ifBlank { s.manifest.name }) { state, title, station, message ->
                    scheduler.post {
                        if (s.stream !== active || s.ended) return@post
                        if (state == PlayerState.ERROR) ports.log("${s.manifest.name}: Stream – ${message ?: "fehlgeschlagen"}")
                        deliver(s, AppEvent.Player(state, url, title, station, message))
                    }
                }
            }
            PlayerAction.Pause -> stream(s).player?.pause()
            PlayerAction.Resume -> stream(s).player?.resume()
            PlayerAction.Stop -> {
                s.stream?.player?.release()
                s.stream = null
            }
        }
    }

    private fun stream(s: Session): ActiveStream = s.stream ?: throw CommandException.badValue("Kein Stream")

    // --- The phone (interface 4) -------------------------------------------------------------------

    /** An app's connection to [partner] on the phone; [line] goes through G2 Handy ([HostPorts.phone]). */
    private class ActivePhone(val partner: String) {
        var line: PhoneLine? = null
        var connected = false

        /** The app has the watch's microphone on. */
        var mic = false

        /** Messages sent in the current second, for [MAX_PHONE_MESSAGES]. */
        var sent = 0
        var secondStart = 0L
    }

    private fun phone(s: Session, action: PhoneAction) {
        when (action) {
            is PhoneAction.Connect -> {
                if (Permission.PHONE !in s.granted) {
                    // A connect always has an outcome the app hears, also this one.
                    scheduler.post { deliver(s, AppEvent.Phone(PhoneState.DISCONNECTED, message = PHONE_DENIED)) }
                }
                requirePermission(s, Permission.PHONE)
                if (!PhoneLinkWire.isPackageName(action.partner)) {
                    throw CommandException.badValue("„${action.partner.take(60)}“ ist kein Paketname einer App auf dem Handy")
                }
                s.phone?.let { old ->
                    if (old.partner == action.partner) return // connected or connecting already
                    old.line?.close()
                    s.phone = null
                }
                val p = ActivePhone(action.partner)
                val listener = object : PhoneListener {
                    override fun onState(state: PhoneState, text: String?, message: String?) =
                        scheduler.post { phoneState(s, p, state, text, message) }

                    override fun onNotice(message: String) = scheduler.post { phoneNotice(s, p, message) }
                }
                s.phone = p
                p.line = try {
                    ports.phone.connect(s.manifest.id, s.manifest.version, action.partner, listener)
                } catch (e: RuntimeException) {
                    s.phone = null
                    throw CommandException.badValue("Verbindung zum Handy ging nicht: ${e.message}")
                }
            }
            is PhoneAction.Send -> {
                requirePermission(s, Permission.PHONE)
                val p = connectedPhone(s)
                if (action.text.encodeToByteArray().size > PhoneAction.MAX_TEXT_BYTES) {
                    throw CommandException.badValue("Eine Nachricht ans Handy darf höchstens ${PhoneAction.MAX_TEXT_BYTES / 1024} KiB haben")
                }
                val now = nowMs()
                if (now - p.secondStart >= 1_000) {
                    p.secondStart = now
                    p.sent = 0
                }
                if (++p.sent > MAX_PHONE_MESSAGES) {
                    throw CommandException.badValue("Höchstens $MAX_PHONE_MESSAGES Nachrichten je Sekunde ans Handy")
                }
                p.line?.send(action.text)
            }
            is PhoneAction.Microphone -> {
                // Without it the app would wait for sound that never comes: the wearer reads why.
                if (action.on && Permission.WATCH_MIC !in s.granted && s === visible) showToast(s, MIC_DENIED, NOTICE_MS)
                requirePermission(s, Permission.WATCH_MIC)
                val p = connectedPhone(s)
                if (action.on && !s.visible) throw CommandException.badValue("Das Mikrofon der Uhr gibt es nur, solange die App zu sehen ist")
                if (p.mic != action.on) {
                    p.mic = action.on
                    p.line?.microphone(action.on)
                }
            }
            PhoneAction.Disconnect -> {
                s.phone?.line?.close()
                s.phone = null
            }
        }
    }

    private fun connectedPhone(s: Session): ActivePhone =
        s.phone?.takeIf { it.connected } ?: throw CommandException.badValue("Keine Verbindung zum Handy")

    private fun phoneState(s: Session, p: ActivePhone, state: PhoneState, text: String?, message: String?) {
        if (s.ended || s.phone !== p) return
        when (state) {
            PhoneState.CONNECTED -> {
                if (p.connected) return
                p.connected = true
            }
            PhoneState.MESSAGE -> if (!p.connected) return
            PhoneState.DISCONNECTED -> {
                s.phone = null
                p.line?.close()
                message?.let { ports.log("${s.manifest.name}: Handy – $it") }
            }
        }
        deliver(s, AppEvent.Phone(state, text, message))
    }

    /** Something about the connection the wearer should know (the microphone is not allowed …): a note on the glasses. */
    private fun phoneNotice(s: Session, p: ActivePhone, message: String) {
        if (s.ended || s.phone !== p) return
        ports.log("${s.manifest.name}: Handy – $message")
        if (s === visible) showToast(s, message, NOTICE_MS)
    }

    /** Hidden, an app does not hear: its microphone stops and stays off until the app turns it on again. */
    private fun phoneMicOff(s: Session) {
        val p = s.phone ?: return
        if (!p.mic) return
        p.mic = false
        p.line?.microphone(false)
    }

    // --- Drawing --------------------------------------------------------------------------------

    /** A page the host draws itself (menu, question, notice), with its own focus and scroll. */
    private class HostPage(
        val kind: String,
        var page: Page,
        val session: Session? = null,
        val onClick: (String) -> Unit,
        val onBack: () -> Unit,
    ) {
        val view = ViewState()
    }

    /** What is on the glasses: a page of [session], or a [host] page over it. */
    private class Screen(val session: Session?, val page: Page, val view: ViewState, val host: HostPage?, val title: String)

    private fun currentScreen(): Screen? {
        overlay?.let { o ->
            val owner = o.session ?: visible
            return Screen(owner, o.page, o.view, o, owner?.manifest?.name ?: "Apps")
        }
        val s = visible ?: return null
        s.notice?.let { return Screen(s, it.page, it.view, it, s.manifest.name) }
        val page = s.pages.current ?: placeholder(s)
        return Screen(s, page, s.view(page.id), null, s.manifest.name)
    }

    /** What an app shows before its first page: nothing, or its starting text. */
    private fun placeholder(s: Session): Page {
        val text = s.internal?.startingText
        return Page(PLACEHOLDER, "", if (text != null) listOf(Block.Text("host_starting", text, Align.CENTER)) else emptyList())
    }

    /** The layout of [scr]'s page; the launcher's buttons are cells of a grid (03 §5). */
    private fun layoutOf(scr: Screen): PageLayout {
        val tiles = scr.host == null && scr.session?.isLauncher == true
        cachedLayout?.let { if (it.page === scr.page && it.tiles == tiles) return it }
        return renderer.layout(scr.page, tiles).also { cachedLayout = it }
    }

    /** A page seen for the first time gets the focus on its first target in view. */
    private fun settle(layout: PageLayout, view: ViewState) {
        view.scroll = view.scroll.coerceIn(0, layout.maxScroll)
        if (!view.fresh) return
        view.fresh = false
        if (view.focus == null) {
            view.focus = layout.focusables.firstOrNull { it.rect.y >= view.scroll && it.rect.bottom <= view.scroll + layout.height }?.target
        }
    }

    /**
     * Draws the visible page again, at most [interval] after the last time: [RENDER_INTERVAL_MS] for
     * commands, [VIDEO_INTERVAL_MS] when a new video picture came. A pending render that would come
     * later is brought forward.
     */
    private fun invalidate(interval: Long = RENDER_INTERVAL_MS) {
        val now = nowMs()
        val at = maxOf(now, lastRenderMs + interval)
        if (renderScheduled && renderAtMs <= at) return
        renderScheduled = true
        renderAtMs = at
        val token = ++renderToken
        scheduler.postDelayed(at - now) { if (token == renderToken) render() }
    }

    private fun render() {
        renderScheduled = false
        lastRenderMs = nowMs()
        val scr = currentScreen()
        if (scr == null) {
            if (appOnScreen) {
                appOnScreen = false
                screen.closeApp()
            }
            publish()
            return
        }
        val layout = layoutOf(scr)
        settle(layout, scr.view)
        val raster = GrayRaster(layout.width, layout.height)
        val s = scr.session
        renderer.render(
            raster,
            layout,
            PageLook(
                scroll = scr.view.scroll,
                focus = scr.view.focus,
                toast = if (scr.host == null) s?.toast else null,
                images = if (scr.host == null && s != null) s.images else emptyMap(),
                tiles = if (scr.host == null && s?.isLauncher == true) launcher?.tiles.orEmpty() else emptyMap(),
            ),
        )
        val subtitle = scr.page.name.takeIf { it.isNotBlank() && it != scr.title }
        screen.showApp(
            AppView(
                title = scr.title,
                subtitle = subtitle,
                fullScreen = !scr.page.statusBar,
                pixels = raster.pixels,
                width = raster.width,
                height = raster.height,
                pointer = _inputMode.value == InputMode.POINTER,
                home = scr.host == null && s?.isLauncher == true,
            ),
        )
        appOnScreen = true
        publish()
    }

    private fun updateInputMode() {
        val s = visible
        // Host pages that wait for an answer take the pointer, also over a page with gestures.
        val kind = overlay?.kind
        _inputMode.value = if (s == null || kind == "host.permission" || kind == EDITOR_KIND) InputMode.POINTER else inputOf(s)
    }

    /** How the wearer works [s] right now: its page's mode, else its manifest's. */
    private fun inputOf(s: Session): InputMode = s.pages.current?.input ?: s.manifest.input

    private fun publish() {
        val scr = currentScreen()
        _state.value = HostState(
            visible = visible?.manifest?.id,
            page = scr?.page?.id,
            focus = scr?.view?.focus,
            scroll = scr?.view?.scroll ?: 0,
            running = sessions.values.filter { !it.isLauncher }.map { it.manifest.id },
            moving = launcher?.lifted,
        )
    }

    private fun scrollToShow(layout: PageLayout, scroll: Int, r: Rect): Int {
        var next = scroll
        if (r.y - PageMetrics.GAP < next) next = r.y - PageMetrics.GAP
        if (r.bottom + PageMetrics.GAP > next + layout.height) next = r.bottom + PageMetrics.GAP - layout.height
        return next.coerceIn(0, layout.maxScroll)
    }

    // --- Sessions -------------------------------------------------------------------------------

    private class ViewState {
        var scroll = 0
        var focus: FocusTarget? = null

        /** Not drawn yet: the first draw puts the focus on the first target. */
        var fresh = true
    }

    private class TimerState(val tag: String, val ms: Long, val repeat: Boolean) {
        var token = 0
        var dueAt = 0L

        /** Remaining time while the app is hidden without [Permission.BACKGROUND]. */
        var paused: Long? = null
    }

    /** One running app (or the launcher): its pages and everything the host keeps for it. */
    private inner class Session(
        val entryId: String,
        val manifest: AppManifest,
        val app: G2App?,
        val internal: InternalSession?,
        /** The session's files: the APK's assets, or those of its package. */
        val assets: (String) -> ByteArray? = ports::asset,
    ) {
        val pages = PageState()
        val views = HashMap<String, ViewState>()
        val images = HashMap<String, GrayRaster>()
        val imageKeys = HashMap<String, String>()
        val timers = LinkedHashMap<String, TimerState>()
        val subscriptions = HashSet<Sensor>()
        val notedMissing = HashSet<String>()
        val videos = LinkedHashMap<String, ActiveVideo>()
        val webs = LinkedHashMap<String, ActiveWeb>()
        var phone: ActivePhone? = null

        /** The watch's GPS while the app subscribed to it and is on the glasses (interface 5). */
        var location: LocationLine? = null
        var locationRate = 0

        /** Running downloads by tag (interface 5). */
        val downloads = HashMap<String, ActiveDownload>()
        var work: WorkQueue? = null

        /** The app follows the players on the watch; its own audio stream (interface 6). */
        var media: ActiveMedia? = null
        var stream: ActiveStream? = null
        var project: BaukastenProject? = null
        var granted: Set<Permission> = emptySet()
        var menu: List<MenuItem> = emptyList()
        var toast: String? = null
        var toastToken = 0
        var notice: HostPage? = null
        var started = false
        var stopping = false
        var ended = false
        var visible = false
        var startedAtMs = 0L
        val context = HostContext(this)

        val isLauncher: Boolean get() = entryId == LAUNCHER_ENTRY

        fun view(pageId: String): ViewState = views.getOrPut(pageId) { ViewState() }
    }

    /** The [ch.madtreasures.g2watch.apps.AppContext] of one session; refused commands go to the log. */
    private inner class HostContext(private val s: Session) : InternalContext {
        override fun definePages(pages: List<Page>) = command(AppCommand.DefinePages(pages))

        override fun definePages(project: BaukastenProject) = guarded("definePages") {
            execute(s, AppCommand.DefinePages(project.pages))
            s.project = project
        }

        override fun show(pageId: String) = command(AppCommand.Show(pageId))

        override fun replace(pageId: String) = command(AppCommand.Replace(pageId))

        override fun patch(pageId: String, changes: PatchBuilder.() -> Unit) =
            guarded("patch") { execute(s, AppCommand.Patch(pageId, PatchBuilder().apply(changes).build())) }

        override fun setBlocks(pageId: String, blocks: List<Block>) = command(AppCommand.SetBlocks(pageId, blocks))

        override fun toast(text: String, ms: Int) = command(AppCommand.Toast(text, ms))

        override fun vibrate(pattern: Vibration) = command(AppCommand.Vibrate(pattern))

        override fun menu(items: List<MenuItem>) = command(AppCommand.Menu(items))

        override fun buzz(notes: List<BuzzNote>) = command(AppCommand.Buzz(notes))

        override fun timer(tag: String, ms: Long, repeat: Boolean) = guarded("timer") { startTimer(s, tag, ms, repeat) }

        override fun cancelTimer(tag: String) = guarded("cancelTimer") { s.timers.remove(tag)?.let { it.token++ } }

        override fun subscribe(sensor: Sensor, rate: Int) = command(AppCommand.Subscribe(sensor, rate))

        override fun unsubscribe(sensor: Sensor) = command(AppCommand.Unsubscribe(sensor))

        override fun audio(on: Boolean) = command(AppCommand.Audio(on))

        override fun fetch(request: HttpRequest, onResult: (HttpResult) -> Unit) = guarded("fetch") { fetch(s, request, onResult) }

        override fun askText(tag: String, prompt: String, suggestions: List<String>) = command(AppCommand.AskText(tag, prompt, suggestions))

        override fun video(block: String, action: VideoAction) = command(AppCommand.Video(block, action))

        override fun videoSearch(query: String, onResult: (VideoSearchResult) -> Unit) =
            guarded("videoSearch") { videoSearch(s, query, onResult) }

        override fun web(block: String, action: WebAction) = command(AppCommand.Web(block, action))

        override fun phone(action: PhoneAction) = command(AppCommand.Phone(action))

        override fun media(action: MediaAction) = command(AppCommand.Media(action))

        override fun player(action: PlayerAction) = command(AppCommand.Player(action))

        override val files: AppFiles = object : AppFiles {
            override val dir: File? get() = filesOf(s)

            override val freeBytes: Long get() = filesOf(s)?.usableSpace ?: 0

            override fun download(tag: String, url: String, name: String, wifiOnly: Boolean) =
                guarded("download") { download(s, tag, url, name, wifiOnly) }

            override fun cancelDownload(tag: String) = guarded("cancelDownload") { s.downloads[tag]?.job?.cancel() }
        }

        override fun <T> work(tag: String, task: () -> T, onDone: (Result<T>) -> Unit) = guarded("work") {
            if (tag.isEmpty() || tag.length > MAX_TAG) throw CommandException.badValue("Arbeit braucht einen Namen mit 1 bis $MAX_TAG Zeichen")
            workQueue(s).submit(tag, task, onDone)
        }

        override fun asset(path: String): ByteArray? = s.assets(path)

        override val storage: AppStorage = object : AppStorage {
            private val store by lazy { ports.storage(s.manifest.id) }

            override fun get(key: String): JsonElement? = store.get(key)

            override fun set(key: String, value: JsonElement) = guarded("storage.set") { store.set(key, value) }
        }

        override fun log(message: String) = ports.log("${s.manifest.name}: $message")

        override fun close() = command(AppCommand.Close)

        override fun setRaster(blockId: String, raster: GrayRaster) = guarded("setRaster") { setRaster(s, blockId, raster) }

        override val glasses: GlassesStatus get() = this@AppHost.glasses

        override val keyboard: Boolean get() = ports.hardwareKeyboard

        private fun command(c: AppCommand) = guarded(c.name) { execute(s, c) }

        private fun guarded(name: String, block: () -> Unit) {
            if (s.ended) return
            try {
                block()
            } catch (e: CommandException) {
                refuse(s, name, e)
            }
        }
    }

    companion object {
        /** An app must show a page this long after its start (02 §3) … */
        const val FIRST_PAGE_MS = 2_000L

        /** … and is ended if it still shows none after this. */
        const val GIVE_UP_MS = 10_000L

        /** The host draws at most this often and combines the commands in between (02 §6.2). */
        const val RENDER_INTERVAL_MS = 200L

        /** … but a new video picture is drawn after this (about 15 a second), with the commands so far. */
        const val VIDEO_INTERVAL_MS = 66L

        const val SLOW_LOG_MS = 50L
        const val SLOW_STOP_MS = 500L
        const val MIN_REPEAT_MS = 50L
        const val MAX_MENU = 10
        const val MAX_MENU_TEXT = 32
        const val MAX_BUZZ = 48
        const val NOTICE_MS = 3_000

        /** Messages an app may send to the phone per second (interface 4). */
        const val MAX_PHONE_MESSAGES = 20

        /** The slowest GPS rate an app may ask for, in seconds (interface 5). */
        const val MAX_LOCATION_SECONDS = 60

        /** Limits of the music commands (interface 6): package names, volume steps per command, stream names. */
        const val MAX_APP_NAME = 200
        const val MAX_VOLUME_STEPS = 15
        const val MAX_STREAM_TITLE = 100

        /** The wearer did not allow it when the app asked (02 §8); the answer holds until the app's version changes. */
        const val PHONE_DENIED = "Verbindung zum Handy nicht erlaubt"
        const val MIC_DENIED = "Mikrofon der Uhr nicht erlaubt"
        const val MAX_TAG = 40
        const val MAX_PROMPT = 100
        const val MAX_SUGGESTIONS = 5
        const val MAX_SUGGESTION = 40
        const val MAX_QUERY = 200

        /** Limits of web pages (05 §10): address length, smallest block, text and scroll per command. */
        const val MAX_URL = 2_000
        const val MIN_WEB_W = 200
        const val MIN_WEB_H = 100
        const val MAX_WEB_TEXT = 2_000
        const val MAX_WEB_SCROLL = 10_000

        /** Pointer pushes over a web page are gathered this long into one scroll. */
        const val PUSH_SCROLL_MS = 120L

        /** Shown on the glasses while the watch asks for text. */
        const val TEXT_HINT = "Bitte auf der Uhr eingeben"
        const val TEXT_HINT_MS = 4_000

        /** The host page that shows a question with the line typed on a Bluetooth keyboard. */
        const val EDITOR_KIND = "host.text"
        const val EDITOR_WATCH = "host_watch"
        const val EDITOR_SUGGESTION = "host_s"
        const val EDITOR_CURSOR = "|"
        const val MAX_EDITOR_TEXT = 500

        const val MENU_KIND = "host.menu"

        /** Keys a page with gestures takes as a tap or back: once per press, however long held. */
        private val GESTURE_ONCE_KEYS = setOf(
            Keys.SPACE, Keys.ENTER, Keys.MEDIA_PLAY_PAUSE, Keys.MEDIA_PLAY, Keys.MEDIA_PAUSE, Keys.BACKSPACE,
        )

        /** Pixels the page scrolls per notch of the mouse wheel. */
        const val WHEEL_STEP = 48

        /** Longest key name of [WebAction.Key] ("MediaTrackPrevious" and the like). */
        const val MAX_KEY_NAME = 32

        /** How long a finger or a mouse button rests on an app of the launcher before the app lifts to move (03 §5). */
        const val HOLD_MS = 2_000L

        /** How far the pointer may wander while held, in pixels of the app area, and still lift the app. */
        const val HOLD_SLOP = 8

        /** Key of the apps' order in the launcher's store. */
        const val ORDER_KEY = "reihenfolge"

        const val WATCH_PREFIX = "watch:"
        const val EVEN_HUB_PREFIX = "evenhub:"
        const val LAUNCHER_ENTRY = "launcher"
        const val PLACEHOLDER = "host.empty"

        private fun eventName(event: AppEvent): String = when (event) {
            is AppEvent.Timer -> "timer ${event.tag}"
            else -> event::class.simpleName.orEmpty()
        }
    }
}

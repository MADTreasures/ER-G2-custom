package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppCommand
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.BaukastenFormatException
import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.BuzzNote
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PatchBuilder
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.builtInApps
import ch.madtreasures.g2watch.apps.launcher.LaunchEntry
import ch.madtreasures.g2watch.apps.launcher.LaunchKind
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.launcher.LauncherHost
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.GrayImages
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.PageLook
import ch.madtreasures.g2watch.apps.render.PageMetrics
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.apps.render.hit
import ch.madtreasures.g2watch.desktop.AppInput
import ch.madtreasures.g2watch.desktop.AppScreen
import ch.madtreasures.g2watch.desktop.AppView
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.Rect
import ch.madtreasures.g2watch.desktop.TextPainter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import java.util.Base64

/** What the host shows and runs, for the watch UI and for tests. */
data class HostState(
    /** App id of the session on the glasses (the launcher included); null while the desktop shows. */
    val visible: String? = null,
    /** Id of the page on the glasses; host pages start with "host.". */
    val page: String? = null,
    val focus: FocusTarget? = null,
    /** Pixels the page on the glasses is scrolled down by. */
    val scroll: Int = 0,
    /** App ids of all running app sessions, the launcher excluded. */
    val running: List<String> = emptyList(),
)

/**
 * Runs the apps on the watch and shows one of them on the glasses (docs/app-entwicklung/03 §5).
 *
 * Everything happens on one thread ([scheduler], "G2Watch-apps"): the host delivers events to the apps
 * there, applies their commands to each session's [PageState], lays out and draws the visible page at
 * most every [RENDER_INTERVAL_MS] and hands the picture to the desktop ([AppScreen]), which puts the
 * header above it and sends it to the glasses. Input arrives from the desktop thread ([AppInput]: pointer,
 * clicks, header), from the temples and the ring ([gesture], via [InputRouter]) and from the watch
 * touchpad in gesture mode; all of it is posted to the app thread first.
 *
 * The host guarantees what 02 promises regardless of the app: back always works and leaves the app on
 * its first page, the app menu (tap-then-hold, or the title in the header) always offers "Apps", "Zurück"
 * and "Schließen", an app that takes over 500 ms for an event or throws is ended, and a command it may
 * not give is refused and logged instead of silently dropped.
 */
class AppHost(
    private val scheduler: Scheduler,
    private val screen: AppScreen,
    text: TextPainter,
    private val ports: HostPorts,
    builtIn: List<() -> G2App> = builtInApps,
    private val evenHub: EvenHubRegistry = EvenHubRegistry.NONE,
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
    private var lastRenderMs = Long.MIN_VALUE / 2
    private var appOnScreen = false
    private var cachedLayout: PageLayout? = null

    private val _inputMode = MutableStateFlow(InputMode.POINTER)

    /** How the watch touchpad works right now: pointer, or gestures for the app on the glasses. */
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _glasses = MutableStateFlow(GlassesStatus())

    /** Battery, charging and wearing of the glasses, as apps see them (03 §5.2). */
    val glassesStatus: StateFlow<GlassesStatus> = _glasses.asStateFlow()

    // --- Input, from any thread -------------------------------------------------------------------

    override fun openLauncher() = scheduler.post { showLauncher(null) }

    /** Starts the app with launcher entry [entryId] ("watch:<app id>"), or shows it if it runs already. */
    override fun launch(entryId: String) = scheduler.post { launchNow(entryId) }

    override fun pointerAt(x: Int, y: Int) = scheduler.post {
        pointer = if (x < 0 || y < 0) null else Pair(x, y)
        hover()
    }

    override fun clickAt(x: Int, y: Int) = scheduler.post {
        val scr = currentScreen() ?: return@post
        val layout = layoutOf(scr.page)
        val target = layout.hit(x, y, scr.view.scroll)?.target ?: return@post
        scr.view.focus = target
        scr.view.fresh = false
        activate(scr, target)
    }

    override fun scrollBy(dy: Int) = scheduler.post {
        val scr = currentScreen() ?: return@post
        val layout = layoutOf(scr.page)
        val next = (scr.view.scroll + dy).coerceIn(0, layout.maxScroll)
        if (next == scr.view.scroll) return@post
        scr.view.scroll = next
        hover()
        invalidate()
    }

    override fun back() = scheduler.post { backNow() }

    override fun openMenu() = scheduler.post { openMenuNow() }

    /** A gesture of the watch, a temple or the ring (from [InputRouter]). */
    fun gesture(g: Gesture) = scheduler.post { gestureNow(g) }

    /** What the connection knows about the glasses. */
    fun updateGlasses(status: GlassesStatus) = scheduler.post {
        if (status == glasses) return@post
        glasses = status
        _glasses.value = status
        for (s in sessions.values.toList()) {
            val internal = s.internal ?: continue
            try {
                internal.onGlassesStatus(status, s.context)
            } catch (e: RuntimeException) {
                crash(s, e)
            }
        }
    }

    /** The page app [appId] shows right now, with every toggle and tick; for tests. Call on the app thread. */
    internal fun currentPageOf(appId: String): Page? = sessions.values.firstOrNull { it.manifest.id == appId }?.pages?.current

    // --- Launcher -------------------------------------------------------------------------------

    override fun entries(): List<LaunchEntry> =
        watchApps.map { (id, app) -> LaunchEntry(id, app.manifest.name, LaunchKind.WATCH, id in sessions) } +
            evenHub.apps.map {
                val id = EVEN_HUB_PREFIX + it.id
                LaunchEntry(id, it.name, LaunchKind.EVEN_HUB, id in sessions, detail = it.location.label)
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
                val entry = watchApps[entryId] ?: return ports.log("Unbekannte App $entryId")
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
        val bytes = ports.asset(path)
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
            }
        }
        visible = s
        s.visible = true
        if (s.started) {
            resumeTimers(s)
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
            kind = "host.menu",
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
        overlay?.let {
            it.onBack()
            return
        }
        val s = visible ?: return screen.back()
        s.notice?.let {
            it.onBack()
            return
        }
        val page = s.pages.current
        if (page == null) {
            stop(s)
            return
        }
        val history = s.pages.historyIds
        deliver(s, AppEvent.Back(page.id))
        if (s.ended) return
        // Whatever the app did meanwhile, back leaves the page it was on (02 §5).
        if (history.size <= 1) {
            stop(s)
            return
        }
        s.pages.resetHistory(history.dropLast(1))
        pageChanged(s, fresh = false)
    }

    private fun gestureNow(g: Gesture) {
        val s = visible
        if (s == null && overlay == null) {
            // No app on the glasses: the temples work the desktop as before.
            when (g.kind) {
                GestureKind.CLICK -> screen.click()
                GestureKind.DOUBLE_CLICK -> screen.back()
                else -> Unit
            }
            return
        }
        val scr = currentScreen() ?: return
        if (scr.host == null && s != null && s.manifest.input == InputMode.GESTURES && s.pages.current != null) {
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
            GestureKind.CLICK -> {
                val layout = layoutOf(scr.page)
                settle(layout, scr.view)
                scr.view.focus?.let { activate(scr, it) }
            }
            GestureKind.DOUBLE_CLICK, GestureKind.SWIPE_RIGHT -> backNow()
            GestureKind.SHORT_THEN_LONG_PRESS -> openMenuNow()
            else -> if (scr.host == null && s != null && Sensor.GESTURES in s.subscriptions) {
                deliver(s, AppEvent.Gesture(g.kind, g.source))
            }
        }
    }

    /** Moves the focus to the next or previous target; far apart targets are reached by scrolling first. */
    private fun moveFocus(scr: Screen, forward: Boolean) {
        val layout = layoutOf(scr.page)
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
        if (next == view.scroll) return
        view.scroll = next
        // A target that left the view is nothing to tap any more.
        if (current != null && (current.rect.bottom <= next || current.rect.y >= next + layout.height)) view.focus = null
        invalidate()
        publish()
    }

    private fun hover() {
        val p = pointer ?: return
        val scr = currentScreen() ?: return
        val hit = layoutOf(scr.page).hit(p.first, p.second, scr.view.scroll) ?: return
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
                if (command.sensor != Sensor.GESTURES) notYet(s, command.sensor.json)
            }
            is AppCommand.Unsubscribe -> s.subscriptions -= command.sensor
            is AppCommand.Audio -> {
                requirePermission(s, Permission.MIC)
                if (command.on) notYet(s, "Mikrofon")
            }
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
        if (s === visible) {
            cachedLayout = null
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
                    if (file.contains("..") || file.startsWith("/")) null else ports.asset("apps/${s.manifest.id}/$file")
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

    // --- Drawing --------------------------------------------------------------------------------

    /** A page the host draws itself (menu, question, notice), with its own focus and scroll. */
    private class HostPage(
        val kind: String,
        val page: Page,
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

    private fun layoutOf(page: Page): PageLayout {
        cachedLayout?.let { if (it.page === page) return it }
        return renderer.layout(page).also { cachedLayout = it }
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

    private fun invalidate() {
        if (renderScheduled) return
        renderScheduled = true
        val wait = (lastRenderMs + RENDER_INTERVAL_MS - nowMs()).coerceAtLeast(0L)
        scheduler.postDelayed(wait) { render() }
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
        val layout = layoutOf(scr.page)
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
            ),
        )
        appOnScreen = true
        publish()
    }

    private fun updateInputMode() {
        val s = visible
        _inputMode.value = if (s == null || overlay?.kind == "host.permission") InputMode.POINTER else s.manifest.input
    }

    private fun publish() {
        val scr = currentScreen()
        _state.value = HostState(
            visible = visible?.manifest?.id,
            page = scr?.page?.id,
            focus = scr?.view?.focus,
            scroll = scr?.view?.scroll ?: 0,
            running = sessions.values.filter { !it.isLauncher }.map { it.manifest.id },
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
    private inner class Session(val entryId: String, val manifest: AppManifest, val app: G2App?, val internal: InternalSession?) {
        val pages = PageState()
        val views = HashMap<String, ViewState>()
        val images = HashMap<String, GrayRaster>()
        val imageKeys = HashMap<String, String>()
        val timers = LinkedHashMap<String, TimerState>()
        val subscriptions = HashSet<Sensor>()
        val notedMissing = HashSet<String>()
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

        override val storage: AppStorage = object : AppStorage {
            private val store by lazy { ports.storage(s.manifest.id) }

            override fun get(key: String): JsonElement? = store.get(key)

            override fun set(key: String, value: JsonElement) = guarded("storage.set") { store.set(key, value) }
        }

        override fun log(message: String) = ports.log("${s.manifest.name}: $message")

        override fun close() = command(AppCommand.Close)

        override fun setRaster(blockId: String, raster: GrayRaster) = guarded("setRaster") { setRaster(s, blockId, raster) }

        override val glasses: GlassesStatus get() = this@AppHost.glasses

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

        const val SLOW_LOG_MS = 50L
        const val SLOW_STOP_MS = 500L
        const val MIN_REPEAT_MS = 50L
        const val MAX_MENU = 10
        const val MAX_MENU_TEXT = 32
        const val MAX_BUZZ = 48
        const val NOTICE_MS = 3_000

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

package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.ThreadScheduler
import ch.madtreasures.g2watch.apps.AppCommand
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppRegistry
import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.CommandError
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.launcher.LauncherEntry
import ch.madtreasures.g2watch.apps.launcher.Program
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.PageMetrics
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.apps.render.PageView
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

/** What the host shows in the app area; the desktop draws the header from [title] and [fullscreen]. */
class AppFrame(
    /** The page's name for the header; tapping it opens the app menu. */
    val title: String,
    /** No header: [pixels] fill the whole visible band (576 × 288). */
    val fullscreen: Boolean,
    /** [PageMetrics.WIDTH] × ([PageMetrics.HEIGHT] or [PageMetrics.FULL_HEIGHT]) gray pixels. */
    val pixels: GrayRaster,
    /** Pointer and focus, or raw gestures: the desktop hides the pointer and the watch switches its touchpad. */
    val input: InputMode,
    /** An app page (not the launcher, a menu or a question): the header title opens the app menu. */
    val hasMenu: Boolean,
)

/** What [AppHost.snapshot] reports. */
internal data class HostSnapshot(
    val active: Boolean,
    /** LAUNCHER, MENU, PROMPT, APP or WAIT; null while the apps are closed. */
    val kind: String?,
    val page: Page?,
    val focus: FocusTarget?,
    val scroll: Int,
    val running: List<String>,
    val visibleApp: String?,
    val history: List<String>,
)

/** Where the host puts its picture: the desktop on the watch, a fake in tests. Called on the app thread. */
interface AppScreen {
    fun showApps(frame: AppFrame)

    /** The wearer left the apps; the desktop shows its tiles again. */
    fun closeApps()
}

/**
 * The app host (03 §5): runs the sessions of watch apps and platform parts on one thread
 * ("G2Watch-apps"), keeps their pages, history, focus, timers and permissions, draws the visible
 * page with [PageRenderer] and hands it to the [screen]. It also draws the launcher, the app menu
 * and the permission question itself.
 *
 * The public methods may be called from any thread; they post to the app thread. Input arrives
 * from the desktop (pointer, clicks, header) and from the [InputRouter] (temples, ring, watch
 * gestures).
 */
class AppHost(
    private val platform: AppPlatform,
    private val renderer: PageRenderer,
    private val scheduler: Scheduler = ThreadScheduler(THREAD_NAME),
    private val builtIn: List<() -> G2App> = AppRegistry.builtInApps,
    private val internalApps: List<() -> InternalApp> = emptyList(),
    private val evenHub: EvenHubRegistry = NoEvenHubApps,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Measures how long an app takes per event; tests pass their own. */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile
    var screen: AppScreen? = null

    private val _glasses = MutableStateFlow(GlassesStatus())

    /** The glasses as the connection reports them; for platform parts and, later, `getGlassesInfo`. */
    val glasses: StateFlow<GlassesStatus> = _glasses.asStateFlow()

    // Everything below is only touched on the app thread.
    private val sessions = LinkedHashMap<String, Session>()
    private var visible: Session? = null
    private var active = false
    private var menu: MenuState? = null
    private val launcherView = ViewState()
    private var hostToast: Toast? = null
    private var entries: List<LauncherEntry>? = null

    private var dirty = false
    private var renderPending = false
    private var renderDueAt = 0L
    private var renderToken = 0
    private var lastRenderAt = Long.MIN_VALUE / 2
    private var cachedLayout: PageLayout? = null
    private var cachedPage: Page? = null
    private var cachedHeight = 0

    private var pointerX = -1
    private var pointerY = -1
    private var edgeDirection = 0
    private var edgeToken = 0

    // --- Entry points (any thread) ---------------------------------------------------------------

    /** The "Apps" tile was chosen: show the launcher. */
    fun open() = scheduler.post {
        active = true
        visible?.let { hide(it) }
        visible = null
        menu = null
        invalidate(immediate = true)
    }

    /** Back to the desktop; running apps stay in the background. */
    fun leave() = scheduler.post { leaveNow() }

    /** The pointer moved to ([x], [y]) in app-area pixels (outside the area is fine). */
    fun pointerAt(x: Int, y: Int) = scheduler.post { onPointer(x, y) }

    /** A click (watch double tap) at ([x], [y]) in app-area pixels. */
    fun click(x: Int, y: Int) = scheduler.post { onClick(x, y) }

    /** Back: double tap on a temple, the "‹" in the header, a watch swipe to the right. */
    fun back() = scheduler.post { if (active) onBack() }

    /** The header title was clicked: the app menu of the visible app. */
    fun openMenu() = scheduler.post { visible?.let { if (active && it.currentPage != null) openMenuFor(it) } }

    /** A gesture from the temples, the ring or (in gesture mode) the watch. */
    fun gesture(kind: GestureKind, source: InputSource) = scheduler.post { if (active) onGesture(kind, source) }

    /** Starts (or brings back) the app with [appId], as if chosen in the launcher. */
    fun launch(appId: String) = scheduler.post {
        active = true
        menu = null
        launchEntry(appId)
    }

    fun updateGlasses(status: GlassesStatus) {
        _glasses.value = status
    }

    /** The EvenHub registry changed (M3); draws the launcher anew. */
    fun refreshLauncher() = scheduler.post {
        entries = null
        invalidate()
    }

    // --- Sessions --------------------------------------------------------------------------------

    private fun launcherEntries(): List<LauncherEntry> =
        entries ?: Launcher.entries(builtIn, internalApps, evenHub).also { entries = it }

    private fun launchEntry(appId: String) {
        val running = sessions[appId]
        if (running != null) {
            makeVisible(running)
            return
        }
        val entry = launcherEntries().firstOrNull { it.id == appId }
        val program = entry?.create?.invoke()
        if (program == null) {
            hostToast("App „$appId“ gibt es nicht")
            return
        }
        val s = Session(program)
        s.context = HostContext(this, s, platform)
        sessions[s.id] = s
        visible?.let { hide(it) }
        visible = s
        val asked = platform.grantedPermissions(s.id, s.manifest.version)
        if (s.manifest.permissions.isNotEmpty() && asked == null) {
            s.prompting = true
            invalidate(immediate = true)
        } else {
            s.granted = asked.orEmpty() intersect s.manifest.permissions
            begin(s)
        }
    }

    /** Loads the Baukasten asset, then `start` and `visible`, and watches that a page shows up in time. */
    private fun begin(s: Session) {
        s.prompting = false
        s.manifest.ui?.let { path -> loadProject(s, path) }
        s.started = true
        dispatch(s, AppEvent.Start)
        if (s.closed) return
        s.onGlasses = true
        // Timers set during `start` were held until the app is on the glasses.
        resumeTimers(s)
        dispatch(s, AppEvent.Visible)
        if (s.closed) return
        invalidate(immediate = true)
        val limit = (s.program as? Program.Internal)?.app?.startTimeoutMs ?: START_LIMIT_MS
        scheduler.postDelayed(limit) { checkStarted(s) }
    }

    private fun loadProject(s: Session, path: String) {
        val bytes = platform.readAsset(path)
        if (bytes == null) {
            appLog(s, "Seiten „$path“ fehlen im App-Paket")
            return
        }
        try {
            val project = BaukastenProject.parse(bytes.toString(Charsets.UTF_8)) { appLog(s, "ui.json: $it") }
            s.project = project
            execute(s, AppCommand.DefinePages(project.pages))
        } catch (e: IllegalArgumentException) {
            appLog(s, "ui.json unbrauchbar: ${e.message}")
        }
    }

    private fun checkStarted(s: Session) {
        if (s.closed || s.currentPage != null) return
        val start = s.project?.start
        if (start != null && start in s.pages) {
            s.history += start
            invalidate()
            return
        }
        s.notResponding = true
        appLog(s, "zeigt keine Seite – „App antwortet nicht“")
        invalidate()
        scheduler.postDelayed(NOT_RESPONDING_END_MS) {
            if (!s.closed && s.currentPage == null) end(s, "${s.name} antwortet nicht und wurde beendet")
        }
    }

    private fun makeVisible(s: Session) {
        if (visible !== s) visible?.let { hide(it) }
        visible = s
        if (s.started && !s.onGlasses) {
            s.onGlasses = true
            resumeTimers(s)
            dispatch(s, AppEvent.Visible)
        }
        invalidate(immediate = true)
    }

    private fun hide(s: Session) {
        if (!s.onGlasses || s.closed) return
        s.onGlasses = false
        if (Permission.BACKGROUND !in s.granted) pauseTimers(s)
        dispatch(s, AppEvent.Hidden)
    }

    /** Ends [s] properly: `stop`, then gone. */
    private fun stop(s: Session) {
        if (s.closed) return
        s.closed = true
        cancelTimers(s)
        if (s.started) dispatch(s, AppEvent.Stop)
        remove(s)
    }

    /** Ends [s] without asking it (crash, too slow, no page): a note on the launcher says why. */
    private fun end(s: Session, why: String) {
        if (!s.closed) {
            s.closed = true
            cancelTimers(s)
            remove(s)
        }
        platform.log(why)
        hostToast(why)
    }

    private fun remove(s: Session) {
        sessions.remove(s.id)
        if (menu?.session === s) menu = null
        if (visible === s) visible = null
        invalidate(immediate = true)
    }

    private fun leaveNow() {
        menu = null
        visible?.let { hide(it) }
        visible = null
        edgeDirection = 0
        edgeToken++
        if (active) {
            active = false
            screen?.closeApps()
        }
    }

    // --- Events to the app -----------------------------------------------------------------------

    private fun dispatch(s: Session, event: AppEvent) {
        if (s.closed && event != AppEvent.Stop) return
        runApp(s) {
            when (val p = s.program) {
                is Program.Watch -> p.app.onEvent(event, s.context)
                is Program.Internal -> p.app.onEvent(event, s.context)
            }
        }
    }

    /**
     * Runs app code with the rules of 03 §3: an exception ends the app ("abgestürzt"), a watch app
     * taking over 50 ms is logged, over 500 ms it is ended. A `close()` during the call takes effect
     * right after it.
     */
    private fun runApp(s: Session, body: () -> Unit) {
        val started = nanoTime()
        val nested = s.inEvent
        s.inEvent = true
        try {
            body()
        } catch (e: Exception) {
            s.inEvent = nested
            appLog(s, "Fehler: ${e.javaClass.simpleName}: ${e.message}")
            end(s, "${s.name} ist abgestürzt")
            return
        }
        s.inEvent = nested
        if (!s.internal) {
            val ms = (nanoTime() - started) / 1_000_000
            if (ms > KILL_MS) {
                end(s, "${s.name} reagiert zu langsam (${ms} ms) und wurde beendet")
                return
            }
            if (ms > SLOW_MS) appLog(s, "brauchte $ms ms für ein Ereignis (erlaubt: $SLOW_MS ms)")
        }
        if (!nested && s.closeRequested && !s.closed) stop(s)
    }

    // --- Commands from the app -------------------------------------------------------------------

    /** Carries out [command] for [s], or logs why not (02 §6.2). */
    internal fun execute(s: Session, command: AppCommand) {
        if (s.closed) return
        try {
            apply(s, command)
        } catch (e: CommandException) {
            appLog(s, "${command.name} abgelehnt (${e.error.code}): ${e.message}")
        }
    }

    private fun apply(s: Session, c: AppCommand) {
        when (c) {
            is AppCommand.DefinePages -> {
                PageCommands.checkDefine(s.pages, c.pages)
                for (page in c.pages) {
                    s.pages[page.id] = page
                    decodeImages(s, page.blocks)
                }
                invalidate()
            }
            is AppCommand.Show -> {
                requirePage(s, c.page)
                if (s.currentPageId != c.page) s.history += c.page
                s.notResponding = false
                invalidate(immediate = s === visible)
            }
            is AppCommand.Replace -> {
                requirePage(s, c.page)
                if (s.history.isEmpty()) s.history += c.page else s.history[s.history.lastIndex] = c.page
                s.notResponding = false
                invalidate(immediate = s === visible)
            }
            is AppCommand.Patch -> {
                val page = requirePage(s, c.page)
                val next = PageCommands.patch(page, c.changes, s.pages.keys)
                s.pages[c.page] = next
                // The picture is decoded for the block's size, so a new size decodes it again.
                decodeImages(s, next.blocks.filter { b -> c.changes[b.id]?.keys?.any { it == "src" || it == "w" || it == "h" } == true })
                invalidate()
            }
            is AppCommand.SetBlocks -> {
                PageCommands.checkSetBlocks(s.pages, c.page, c.blocks)
                s.pages[c.page] = s.pages.getValue(c.page).copy(blocks = c.blocks)
                decodeImages(s, c.blocks)
                invalidate()
            }
            is AppCommand.Toast -> {
                val ms = c.ms.coerceIn(500, 10_000)
                s.toast = Toast(c.text, clockMs() + ms)
                invalidate()
                scheduler.postDelayed(ms.toLong()) { invalidate() }
            }
            is AppCommand.Vibrate -> platform.vibrate(c.pattern)
            is AppCommand.Menu -> {
                if (c.items.size > MAX_MENU_ITEMS) bad("höchstens $MAX_MENU_ITEMS Einträge")
                if (c.items.map { it.id }.toSet().size != c.items.size) bad("Menü-Kennungen doppelt")
                for (item in c.items) {
                    if (item.id.isEmpty()) bad("Menüeintrag ohne Kennung")
                    if (item.text.toByteArray().size > MAX_MENU_TEXT) bad("„${item.text}“ ist länger als $MAX_MENU_TEXT Byte")
                }
                s.menuItems = c.items
                if (menu?.session === s) invalidate()
            }
            is AppCommand.Buzz -> {
                need(s, Permission.BUZZER)
                if (c.notes.size > MAX_BUZZ_STEPS) bad("höchstens $MAX_BUZZ_STEPS Töne")
                appLog(s, "Summer kommt mit M6; noch kein Ton")
            }
            is AppCommand.Timer -> setTimer(s, c)
            is AppCommand.CancelTimer -> s.timers.remove(c.tag)?.let { it.token++ }
            is AppCommand.Subscribe -> {
                c.sensor.permission?.let { need(s, it) }
                s.subscriptions += c.sensor
                if (c.sensor != Sensor.GESTURES) appLog(s, "Sensor „${c.sensor.json}“ kommt mit M6; noch keine Werte")
            }
            is AppCommand.Unsubscribe -> s.subscriptions -= c.sensor
            is AppCommand.Audio -> {
                need(s, Permission.MIC)
                if (c.on) appLog(s, "Mikrofon kommt mit M6; noch keine Töne")
            }
            AppCommand.Close -> if (s.inEvent) s.closeRequested = true else stop(s)
        }
    }

    private fun bad(message: String): Nothing = throw CommandException(CommandError.BAD_VALUE, message)

    private fun need(s: Session, p: Permission) {
        if (p !in s.granted) throw CommandException(CommandError.PERMISSION_DENIED, "Berechtigung „${p.label}“ fehlt")
    }

    private fun requirePage(s: Session, id: String): Page =
        s.pages[id] ?: throw CommandException(CommandError.UNKNOWN_PAGE, "Seite „$id“ gibt es nicht")

    /** Decodes the sources of image blocks into gray pixels; a broken picture stays black and is logged. */
    private fun decodeImages(s: Session, blocks: List<Block>) {
        for (b in blocks) {
            if (b !is Block.Image) continue
            val src = b.src
            if (src == null) {
                s.rasters.remove(b.id)
                continue
            }
            val bytes = when {
                src.startsWith("data:") -> try {
                    Base64.getDecoder().decode(src.substringAfter(";base64,"))
                } catch (e: IllegalArgumentException) {
                    null
                }
                else -> platform.readAsset("apps/${s.id}/" + src.removePrefix("asset:"))
            }
            val raster = bytes?.let { platform.decodeImage(it, b.w, b.h) }
            if (raster == null) {
                appLog(s, "Bild „${b.id}“ ließ sich nicht lesen")
                s.rasters.remove(b.id)
            } else {
                s.rasters[b.id] = raster
            }
        }
    }

    internal fun setRaster(s: Session, blockId: String, raster: GrayRaster) {
        if (!s.internal) {
            appLog(s, "setRaster ist nur für Plattform-Teile")
            return
        }
        val page = s.pageOfBlock(blockId)
        if (page?.block(blockId) !is Block.Image) {
            appLog(s, "setRaster: Bild-Baustein „$blockId“ gibt es nicht")
            return
        }
        s.rasters[blockId] = raster
        invalidate()
    }

    internal fun fetch(s: Session, request: HttpRequest, onResult: (HttpResult) -> Unit) {
        fun deliver(result: HttpResult) = scheduler.post {
            if (!s.closed) runApp(s) { onResult(result) }
        }
        if (Permission.NETWORK !in s.granted) {
            appLog(s, "fetch abgelehnt (permission_denied): Berechtigung „Internet“ fehlt")
            deliver(HttpResult(0, error = "Keine Berechtigung für das Internet"))
            return
        }
        if (!request.url.startsWith("https://")) {
            appLog(s, "fetch abgelehnt (bad_value): nur https://")
            deliver(HttpResult(0, error = "Nur https:// ist erlaubt"))
            return
        }
        platform.http(request) { deliver(it) }
    }

    internal fun postFor(s: Session, action: () -> Unit) = scheduler.post {
        if (!s.closed) runApp(s, action)
    }

    internal fun appLog(s: Session, message: String) = platform.log("${s.name}: $message")

    // --- Timers ----------------------------------------------------------------------------------

    private fun setTimer(s: Session, c: AppCommand.Timer) {
        if (c.tag.isEmpty() || c.tag.length > 40) bad("Timer-Name 1–40 Zeichen")
        if (c.ms !in 1..MAX_TIMER_MS) bad("Timer 1 ms bis 24 h")
        if (c.repeat && c.ms < MIN_REPEAT_MS) bad("wiederholte Timer frühestens alle $MIN_REPEAT_MS ms")
        s.timers.remove(c.tag)?.let { it.token++ }
        val t = AppTimer(c.tag, c.ms, c.repeat)
        s.timers[c.tag] = t
        if (s.onGlasses || Permission.BACKGROUND in s.granted) {
            t.due = clockMs() + c.ms
            schedule(s, t)
        } else {
            t.remaining = c.ms
        }
    }

    private fun schedule(s: Session, t: AppTimer) {
        val token = ++t.token
        scheduler.postDelayed((t.due - clockMs()).coerceAtLeast(0)) {
            if (t.token == token && s.timers[t.tag] === t && !s.closed) fire(s, t)
        }
    }

    private fun fire(s: Session, t: AppTimer) {
        if (t.repeat) {
            t.due += t.ms
            // Behind by more than one period (the watch slept): skip ahead instead of firing a burst.
            if (t.due <= clockMs()) t.due = clockMs() + t.ms
            schedule(s, t)
        } else {
            s.timers.remove(t.tag)
        }
        dispatch(s, AppEvent.Timer(t.tag))
    }

    private fun pauseTimers(s: Session) {
        val now = clockMs()
        for (t in s.timers.values) {
            if (t.remaining != null) continue
            t.token++
            t.remaining = (t.due - now).coerceAtLeast(0)
        }
    }

    private fun resumeTimers(s: Session) {
        val now = clockMs()
        for (t in s.timers.values) {
            val left = t.remaining ?: continue
            t.remaining = null
            t.due = now + left
            schedule(s, t)
        }
    }

    private fun cancelTimers(s: Session) {
        s.timers.values.forEach { it.token++ }
        s.timers.clear()
    }

    // --- What is shown ---------------------------------------------------------------------------

    private enum class Kind { LAUNCHER, MENU, PROMPT, APP, WAIT }

    private class Shown(
        val kind: Kind,
        val page: Page,
        val view: ViewState,
        val owner: Session?,
        val input: InputMode,
    ) {
        val fullscreen: Boolean get() = !page.statusBar
        val height: Int get() = if (fullscreen) PageMetrics.FULL_HEIGHT else PageMetrics.HEIGHT
    }

    private fun shown(): Shown? {
        if (!active) return null
        menu?.let { m -> return Shown(Kind.MENU, menuPage(m.session), m.view, m.session, InputMode.POINTER) }
        val s = visible ?: return Shown(
            Kind.LAUNCHER,
            Launcher.page(launcherEntries(), sessions.keys),
            launcherView,
            null,
            InputMode.POINTER,
        )
        if (s.prompting) return Shown(Kind.PROMPT, promptPage(s), s.promptView, s, InputMode.POINTER)
        val page = s.currentPage
        if (page != null) return Shown(Kind.APP, page, s.view(page.id), s, s.manifest.input)
        return Shown(Kind.WAIT, waitPage(s), s.waitView, s, InputMode.POINTER)
    }

    private fun menuPage(s: Session) = Page(
        id = "@menu",
        name = "Menü · ${s.name}",
        blocks = s.menuItems.map { Block.Button(ITEM_PREFIX + it.id, it.text) } + listOf(
            Block.Button(MENU_APPS, "Apps"),
            Block.Button(MENU_BACK, "Zurück"),
            Block.Button(MENU_CLOSE, "Schließen"),
        ),
    )

    private fun promptPage(s: Session) = Page(
        id = "@prompt",
        name = s.name,
        blocks = listOf(
            Block.Heading("@prompt.title", "${s.name} möchte:"),
            Block.Text("@prompt.list", (s.manifest.permissions.sortedBy { it.ordinal }).joinToString(", ") { it.label }),
            Block.Button(PROMPT_ALLOW, "Erlauben"),
            Block.Button(PROMPT_DENY, "Ablehnen"),
        ),
    )

    private fun waitPage(s: Session) = Page(
        id = "@wait",
        name = s.name,
        blocks = if (s.notResponding) {
            listOf(
                Block.Heading("@wait.title", "App antwortet nicht"),
                Block.Text("@wait.text", "${s.name} zeigt keine Seite und wird gleich beendet."),
                Block.Button(WAIT_CLOSE, "Schließen"),
            )
        } else {
            listOf(Block.Text("@wait.text", "Startet …"))
        },
    )

    private fun layoutOf(sh: Shown): PageLayout {
        val cached = cachedLayout
        // Host pages are built anew for every look; equal content means an equal layout.
        if (cached != null && cachedHeight == sh.height && (cachedPage === sh.page || cachedPage == sh.page)) return cached
        return renderer.layout(sh.page, PageMetrics.WIDTH, sh.height).also {
            cachedLayout = it
            cachedPage = sh.page
            cachedHeight = sh.height
        }
    }

    /** Keeps the focus on a target that exists; a page starts with the first target in view. */
    private fun settleFocus(sh: Shown, layout: PageLayout) {
        val v = sh.view
        val targets = layout.targets
        if (v.focus != null && v.focus !in targets) v.focus = null
        if (!v.placed) {
            v.placed = true
            if (v.focus == null && sh.input == InputMode.POINTER) {
                v.focus = targets.firstOrNull { t -> layout.rectOf(t)?.let { it.bottom <= v.scroll + layout.height } == true }
            }
        }
        v.scroll = v.scroll.coerceIn(0, layout.maxScroll)
    }

    /** What is shown and running, for tests; call on the app thread. */
    internal fun snapshot(): HostSnapshot {
        val sh = shown()
        return HostSnapshot(
            active = active,
            kind = sh?.kind?.name,
            page = sh?.page,
            focus = sh?.view?.focus,
            scroll = sh?.view?.scroll ?: 0,
            running = sessions.keys.toList(),
            visibleApp = visible?.id,
            history = visible?.history?.toList().orEmpty(),
        )
    }

    // --- Drawing ---------------------------------------------------------------------------------

    /**
     * Asks for a new picture: at once for input, otherwise at most every [RENDER_INTERVAL_MS], so
     * a burst of commands becomes one picture (02 §6.2) and the newest state wins.
     */
    private fun invalidate(immediate: Boolean = false) {
        dirty = true
        val now = clockMs()
        val due = if (immediate) now else maxOf(now, lastRenderAt + RENDER_INTERVAL_MS)
        if (renderPending && due >= renderDueAt) return
        renderPending = true
        renderDueAt = due
        val token = ++renderToken
        scheduler.postDelayed(due - now) {
            if (token != renderToken) return@postDelayed
            renderPending = false
            if (dirty) renderNow()
        }
    }

    private fun renderNow() {
        dirty = false
        lastRenderAt = clockMs()
        val sh = shown() ?: return
        val layout = layoutOf(sh)
        settleFocus(sh, layout)
        val raster = GrayRaster(PageMetrics.WIDTH, sh.height)
        val owner = sh.owner
        renderer.render(
            sh.page,
            layout,
            PageView(sh.view.scroll, sh.view.focus.takeIf { sh.input == InputMode.POINTER || sh.kind != Kind.APP }),
            raster,
            image = { id -> if (sh.kind == Kind.APP) owner?.rasters?.get(id) else null },
            toast = toastFor(sh),
        )
        screen?.showApps(AppFrame(sh.page.name, sh.fullscreen, raster, sh.input, hasMenu = sh.kind == Kind.APP))
    }

    private fun toastFor(sh: Shown): String? {
        val now = clockMs()
        val t = if (sh.kind == Kind.APP || sh.kind == Kind.WAIT) sh.owner?.toast else hostToast
        return t?.takeIf { it.until > now }?.text
    }

    private fun hostToast(text: String) {
        hostToast = Toast(text, clockMs() + HOST_TOAST_MS)
        invalidate()
        scheduler.postDelayed(HOST_TOAST_MS) { invalidate() }
    }

    // --- Input -----------------------------------------------------------------------------------

    private fun onPointer(x: Int, y: Int) {
        pointerX = x
        pointerY = y
        val sh = shown()
        if (sh == null || sh.input != InputMode.POINTER) {
            setEdge(0)
            return
        }
        val layout = layoutOf(sh)
        hoverAt(sh, layout)
        val direction = when {
            x !in 0 until layout.width || y !in 0 until layout.height -> 0
            y >= layout.height - EDGE && sh.view.scroll < layout.maxScroll -> 1
            y < EDGE && sh.view.scroll > 0 -> -1
            else -> 0
        }
        setEdge(direction)
    }

    /** The target under the pointer gets the focus. */
    private fun hoverAt(sh: Shown, layout: PageLayout) {
        if (pointerY !in 0 until layout.height) return
        val t = layout.targetAt(pointerX, pointerY + sh.view.scroll) ?: return
        if (t != sh.view.focus) {
            sh.view.focus = t
            invalidate(immediate = true)
        }
    }

    /** Resting the pointer at the top or bottom edge scrolls a long page, one step at a time. */
    private fun setEdge(direction: Int) {
        if (direction == edgeDirection) return
        edgeDirection = direction
        val token = ++edgeToken
        if (direction != 0) scheduler.postDelayed(EDGE_DWELL_MS) { edgeStep(token) }
    }

    private fun edgeStep(token: Int) {
        if (token != edgeToken) return
        val sh = shown() ?: return
        val layout = layoutOf(sh)
        val before = sh.view.scroll
        sh.view.scroll = (before + edgeDirection * (layout.height * 7 / 10)).coerceIn(0, layout.maxScroll)
        if (sh.view.scroll == before) return
        hoverAt(sh, layout)
        invalidate(immediate = true)
        scheduler.postDelayed(EDGE_REPEAT_MS) { edgeStep(token) }
    }

    private fun onClick(x: Int, y: Int) {
        val sh = shown() ?: return
        if (sh.input != InputMode.POINTER) return
        val layout = layoutOf(sh)
        if (y !in 0 until layout.height) return
        val t = layout.targetAt(x, y + sh.view.scroll)
        if (t != null) {
            sh.view.focus = t
            activate(sh, t)
        } else if (x >= layout.width - SCROLLBAR_HIT && layout.maxScroll > 0) {
            // A click on the scroll bar pages up or down.
            val step = if (y < layout.height / 2) -layout.height * 7 / 10 else layout.height * 7 / 10
            sh.view.scroll = (sh.view.scroll + step).coerceIn(0, layout.maxScroll)
            invalidate(immediate = true)
        }
    }

    private fun onGesture(kind: GestureKind, source: InputSource) {
        val sh = shown() ?: return
        val s = sh.owner.takeIf { sh.kind == Kind.APP }
        if (kind == GestureKind.SHORT_THEN_LONG_PRESS) {
            if (s != null) openMenuFor(s)
            return
        }
        if (s != null && s.manifest.input == InputMode.GESTURES) {
            val toApp = (s.program as? Program.Internal)?.app?.doubleTapToApp == true
            when {
                // 02 §7: the watch swipes right for Back; a watch double tap is the app's doubleClick,
                // a temple or ring double tap means Back (EvenHub apps get it as doubleClick).
                kind == GestureKind.SWIPE_RIGHT -> onBack()
                kind == GestureKind.DOUBLE_CLICK && source != InputSource.WATCH && !toApp -> onBack()
                else -> dispatch(s, AppEvent.Gesture(kind, source))
            }
            return
        }
        if (s != null && Sensor.GESTURES in s.subscriptions && kind != GestureKind.SWIPE_RIGHT) {
            dispatch(s, AppEvent.Gesture(kind, source))
            if (shown()?.page !== sh.page) return
        }
        when (kind) {
            GestureKind.CLICK -> sh.view.focus?.let { activate(sh, it) }
            GestureKind.DOUBLE_CLICK, GestureKind.SWIPE_RIGHT -> onBack()
            GestureKind.SCROLL_UP -> moveFocus(sh, forward = false)
            GestureKind.SCROLL_DOWN -> moveFocus(sh, forward = true)
            else -> Unit
        }
    }

    /**
     * Temple swipe: the focus goes to the previous or next target and the page scrolls with it. A
     * target more than most of a screen away is approached by scrolling first, so text in between
     * can be read; without targets the page just scrolls.
     */
    private fun moveFocus(sh: Shown, forward: Boolean) {
        val layout = layoutOf(sh)
        settleFocus(sh, layout)
        val v = sh.view
        val targets = layout.targets
        val step = layout.height * 7 / 10
        val current = targets.indexOf(v.focus)
        val next = when {
            current >= 0 -> current + if (forward) 1 else -1
            forward -> targets.indexOfFirst { t -> (layout.rectOf(t)?.y ?: 0) >= v.scroll }
            else -> targets.indexOfLast { t -> (layout.rectOf(t)?.bottom ?: 0) <= v.scroll + layout.height }
        }
        if (next !in targets.indices) {
            scrollBy(sh, layout, if (forward) step else -step)
            return
        }
        val target = targets[next]
        val wanted = layout.scrollToShow(target, v.scroll)
        if (kotlin.math.abs(wanted - v.scroll) > step) {
            scrollBy(sh, layout, if (wanted > v.scroll) step else -step)
            return
        }
        v.focus = target
        v.scroll = wanted
        invalidate(immediate = true)
    }

    private fun scrollBy(sh: Shown, layout: PageLayout, delta: Int) {
        val before = sh.view.scroll
        sh.view.scroll = (before + delta).coerceIn(0, layout.maxScroll)
        if (sh.view.scroll != before) invalidate(immediate = true)
    }

    private fun onBack() {
        edgeDirection = 0
        edgeToken++
        if (menu != null) {
            menu = null
            invalidate(immediate = true)
            return
        }
        val s = visible
        when {
            s == null -> leaveNow()
            s.prompting -> {
                // Back on the question starts nothing.
                s.closed = true
                remove(s)
            }
            s.currentPage == null -> stop(s)
            else -> appBack(s)
        }
    }

    /** 02 §5: the app hears `back` first, then the previous page shows; on the first page the app ends. */
    private fun appBack(s: Session) {
        val page = s.currentPageId ?: return stop(s)
        val depth = s.history.size
        dispatch(s, AppEvent.Back(page))
        if (s.closed) return
        // The app may have changed pages itself while handling back; then that stands.
        if (s.history.size != depth || s.currentPageId != page) {
            invalidate(immediate = true)
            return
        }
        if (depth > 1) {
            s.history.removeAt(s.history.lastIndex)
            invalidate(immediate = true)
        } else {
            stop(s)
        }
    }

    private fun openMenuFor(s: Session) {
        menu = MenuState(s)
        invalidate(immediate = true)
    }

    /** A click on [t]: host pages act themselves, app pages as in 02 §5 and §6. */
    private fun activate(sh: Shown, t: FocusTarget) {
        when (sh.kind) {
            Kind.LAUNCHER -> if (t.blockId.startsWith(Launcher.ENTRY_PREFIX)) launchEntry(t.blockId.removePrefix(Launcher.ENTRY_PREFIX))
            Kind.MENU -> menuChoice(menu?.session ?: return, t.blockId)
            Kind.PROMPT -> {
                val s = sh.owner ?: return
                val allow = t.blockId == PROMPT_ALLOW
                if (!allow && t.blockId != PROMPT_DENY) return
                s.granted = if (allow) s.manifest.permissions else emptySet()
                platform.saveGrantedPermissions(s.id, s.manifest.version, s.granted)
                begin(s)
            }
            Kind.WAIT -> if (t.blockId == WAIT_CLOSE) sh.owner?.let { stop(it) }
            Kind.APP -> activateBlock(sh.owner ?: return, sh.page, t)
        }
    }

    private fun menuChoice(s: Session, id: String) {
        menu = null
        when {
            id.startsWith(ITEM_PREFIX) -> dispatch(s, AppEvent.Menu(id.removePrefix(ITEM_PREFIX)))
            id == MENU_APPS -> {
                hide(s)
                visible = null
            }
            id == MENU_BACK -> appBack(s)
            id == MENU_CLOSE -> stop(s)
        }
        invalidate(immediate = true)
    }

    private fun activateBlock(s: Session, page: Page, t: FocusTarget) {
        when (val b = page.block(t.blockId)) {
            is Block.Button -> when (val target = b.target) {
                null -> dispatch(s, AppEvent.Click(page.id, b.id))
                Block.BACK -> onBack()
                else -> {
                    if (target !in s.pages) {
                        appLog(s, "Knopf „${b.id}“: Ziel „$target“ gibt es nicht")
                        return
                    }
                    s.history += target
                    invalidate(immediate = true)
                    dispatch(s, AppEvent.Navigate(page.id, target, b.id))
                }
            }
            is Block.Toggle -> {
                val on = !b.on
                s.pages[page.id] = page.copy(blocks = page.blocks.map { if (it.id == b.id) b.copy(on = on) else it })
                invalidate(immediate = true)
                dispatch(s, AppEvent.Toggle(page.id, b.id, on))
            }
            is Block.List -> {
                if (b.style != ListStyle.CHECKS || t.row !in b.items.indices) return
                val done = !b.items[t.row].done
                val items = b.items.toMutableList().also { it[t.row] = it[t.row].copy(done = done) }
                s.pages[page.id] = page.copy(blocks = page.blocks.map { if (it.id == b.id) b.copy(items = items) else it })
                invalidate(immediate = true)
                dispatch(s, AppEvent.Check(page.id, b.id, t.row, done))
            }
            else -> Unit
        }
    }

    companion object {
        const val THREAD_NAME = "G2Watch-apps"

        /** An app shows its first page within this time, or the host steps in (02 §3). */
        const val START_LIMIT_MS = 2_000L
        const val NOT_RESPONDING_END_MS = 10_000L
        const val SLOW_MS = 50L
        const val KILL_MS = 500L
        const val RENDER_INTERVAL_MS = 200L
        const val HOST_TOAST_MS = 3_000L
        const val MAX_MENU_ITEMS = 10
        const val MAX_MENU_TEXT = 32
        const val MAX_BUZZ_STEPS = 48
        const val MAX_TIMER_MS = 24L * 60 * 60 * 1000
        const val MIN_REPEAT_MS = 100L

        /** Pointer edge zones that scroll after resting there. */
        const val EDGE = 24
        const val EDGE_DWELL_MS = 600L
        const val EDGE_REPEAT_MS = 900L
        const val SCROLLBAR_HIT = 24

        private const val ITEM_PREFIX = "@item:"
        private const val MENU_APPS = "@menu.apps"
        private const val MENU_BACK = "@menu.back"
        private const val MENU_CLOSE = "@menu.close"
        private const val PROMPT_ALLOW = "@prompt.allow"
        private const val PROMPT_DENY = "@prompt.deny"
        private const val WAIT_CLOSE = "@wait.close"
    }
}

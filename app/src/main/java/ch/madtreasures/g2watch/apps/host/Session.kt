package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.launcher.Program
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.desktop.GrayRaster

/** Scroll and focus of one page as the wearer left it. */
internal class ViewState {
    var scroll = 0
    var focus: FocusTarget? = null

    /** The first focus was chosen (the first target visible without scrolling). */
    var placed = false
}

internal class AppTimer(val tag: String, val ms: Long, val repeat: Boolean) {
    var due = 0L

    /** Bumped on every (re)schedule and cancel; a firing callback with an older token does nothing. */
    var token = 0

    /** Time left while the timer rests (app hidden without [Permission.BACKGROUND]). */
    var remaining: Long? = null
}

internal class Toast(val text: String, val until: Long)

/**
 * One running app: its pages as the host knows them (the page state), the history, view states,
 * timers and permissions. Only touched on the app thread.
 */
internal class Session(val program: Program) {
    val manifest get() = program.manifest
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val internal: Boolean get() = program is Program.Internal

    val pages = LinkedHashMap<String, Page>()
    val history = ArrayList<String>()
    val views = HashMap<String, ViewState>()

    /** Pixels of image blocks, decoded from their source or set directly by a platform part. */
    val rasters = HashMap<String, GrayRaster>()
    val timers = LinkedHashMap<String, AppTimer>()
    val subscriptions = HashSet<Sensor>()
    var menuItems: List<MenuItem> = emptyList()
    var granted: Set<Permission> = emptySet()

    /** The Baukasten project from the manifest's `ui` (or [ch.madtreasures.g2watch.apps.AppContext.definePages]). */
    var project: BaukastenProject? = null
    var toast: Toast? = null

    /** Waits for the wearer's answer to the permission question before it starts. */
    var prompting = false
    var started = false

    /** Got `visible` and not `hidden` since. */
    var onGlasses = false
    var closed = false
    var closeRequested = false
    var inEvent = false

    /** Showed no page in time: "App antwortet nicht". */
    var notResponding = false
    val promptView = ViewState()
    val waitView = ViewState()
    lateinit var context: HostContext

    val currentPageId: String? get() = history.lastOrNull()
    val currentPage: Page? get() = currentPageId?.let { pages[it] }

    fun view(pageId: String): ViewState = views.getOrPut(pageId) { ViewState() }

    fun pageOfBlock(blockId: String): Page? = pages.values.firstOrNull { p -> p.blocks.any { it.id == blockId } }
}

/** The menu over an app (02 §5): the app's own entries, then "Apps", "Zurück", "Schließen". */
internal class MenuState(val session: Session) {
    val view = ViewState()
}


package ch.madtreasures.g2watch.apps.launcher

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.host.InternalContext
import ch.madtreasures.g2watch.apps.host.InternalSession
import ch.madtreasures.g2watch.apps.render.TileLook
import ch.madtreasures.g2watch.desktop.GrayRaster

/** Where an app of the launcher runs. */
enum class LaunchKind(val heading: String) {
    WATCH("Auf der Uhr"),
    EVEN_HUB("Even Hub"),
    REMOTE("Rechner"),
}

/**
 * One app in the launcher. [id] is the host's entry id ("watch:…", "evenhub:…"); [icon] is its launcher
 * icon, already scaled to the cell's icon size, or null for the initials.
 */
data class LaunchEntry(
    val id: String,
    val name: String,
    val kind: LaunchKind,
    val running: Boolean,
    val detail: String? = null,
    val icon: GrayRaster? = null,
)

/** What the launcher needs from the host. */
interface LauncherHost {
    fun entries(): List<LaunchEntry>

    fun launch(entryId: String)

    /** The order the wearer gave the apps by moving them, as entry ids; empty until they moved one. */
    fun order(): List<String> = emptyList()

    /** Keeps [ids] as the order of the apps. */
    fun saveOrder(ids: List<String>) = Unit
}

/**
 * The page "Apps" on the glasses (02 §1): every app as a button, which the host lays out as a cell with
 * the app's icon and name ([TileLook]); a dot marks the running ones. It is drawn by the host itself as
 * an internal session, not as an app: the installed watch apps now, Even Hub apps from M3 and remote
 * apps from M5, each kind under its own heading once there is more than one kind.
 *
 * The wearer orders the apps as on a phone (03 §5): the host [lift]s one after it was held and carries
 * it at the pointer, its cell turns into the gap where it will land; [moveTo] moves that gap to the place
 * of another app of its kind while the others make room, and [drop] puts the app into the gap and keeps
 * the order ([LauncherHost.saveOrder]). New apps come after the ordered ones.
 */
class Launcher(private val host: LauncherHost) : InternalSession {
    override val manifest = AppManifest(id = ID, name = "Apps", version = "1.0.0", description = "Alle Apps der Brille")

    /** Block id → entry id of the buttons on the page as last built. */
    private var buttons: Map<String, String> = emptyMap()

    /** Entry id → kind of the apps on the page as last built. */
    private var kinds: Map<String, LaunchKind> = emptyMap()

    /** Block id → icon and marks of the buttons on the page as last built, for the host to draw. */
    var tiles: Map<String, TileLook> = emptyMap()
        private set

    /** The entry id of the app being moved, or null. */
    var lifted: String? = null
        private set

    /** The order of all apps (entry ids) while one is moved. */
    private var moving: MutableList<String>? = null

    override fun onEvent(event: AppEvent, host: InternalContext) {
        when (event) {
            AppEvent.Start -> {
                host.definePages(listOf(page()))
                host.show(PAGE)
            }
            AppEvent.Visible -> refresh(host)
            is AppEvent.Click -> buttons[event.block]?.let { this.host.launch(it) }
            else -> Unit
        }
    }

    /** Rebuilds the list, e.g. after an app started or ended. */
    fun refresh(host: InternalContext) = host.setBlocks(PAGE, page().blocks)

    /** The entry id of the app on block [blockId], or null if the block is no app. */
    fun entryAt(blockId: String): String? = buttons[blockId]

    /** The block that shows app [entryId] now, or null. */
    fun blockOf(entryId: String): String? = buttons.entries.firstOrNull { it.value == entryId }?.key

    /** Lifts the app on block [blockId] to move it; false if the block is no app. */
    fun lift(blockId: String, host: InternalContext): Boolean {
        val entry = buttons[blockId] ?: return false
        moving = ordered(this.host.entries()).map { it.id }.toMutableList()
        lifted = entry
        refresh(host)
        return true
    }

    /** Whether the lifted app may take the place of the app on [blockId]: one of its own kind. */
    fun canTake(blockId: String): Boolean {
        val target = buttons[blockId] ?: return false
        val app = lifted ?: return false
        return kinds[target] == kinds[app]
    }

    /**
     * The lifted app takes the place of the app on [blockId]; those in between move up or down by one, as on
     * a phone. False if nothing changed.
     */
    fun moveTo(blockId: String, host: InternalContext): Boolean {
        val app = lifted ?: return false
        val order = moving ?: return false
        val target = buttons[blockId]?.takeIf { it != app && canTake(blockId) } ?: return false
        val to = order.indexOf(target)
        if (to < 0 || !order.remove(app)) return false
        order.add(to, app)
        refresh(host)
        return true
    }

    /** Puts the lifted app down where it is now and keeps that order. */
    fun drop(host: InternalContext) {
        val order = moving ?: return
        lifted = null
        moving = null
        this.host.saveOrder(order)
        refresh(host)
    }

    /** [entries] in the wearer's order: the ordered ones first, new ones after them as the host lists them. */
    private fun ordered(entries: List<LaunchEntry>): List<LaunchEntry> {
        val order = moving ?: host.order()
        if (order.isEmpty()) return entries
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, id -> rank.putIfAbsent(id, i) }
        return entries.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    private fun page(): Page {
        val entries = ordered(host.entries())
        // The app being moved went away meanwhile (uninstalled): nothing to move any more.
        if (lifted != null && entries.none { it.id == lifted }) {
            lifted = null
            moving = null
        }
        val kindsShown = entries.map { it.kind }.distinct()
        val blocks = ArrayList<Block>()
        val ids = LinkedHashMap<String, String>()
        val looks = HashMap<String, TileLook>()
        if (entries.isEmpty()) blocks += Block.Text("leer", EMPTY)
        for (kind in LaunchKind.entries) {
            val group = entries.filter { it.kind == kind }
            if (group.isEmpty()) continue
            if (kindsShown.size > 1) blocks += Block.Heading("h_${kind.name.lowercase()}", kind.heading)
            for (entry in group) {
                val id = "app${ids.size}"
                ids[id] = entry.id
                looks[id] = TileLook(entry.icon, TileLook.initials(entry.name), entry.running, lifted = entry.id == lifted)
                val label = entry.name + (entry.detail?.let { " · $it" } ?: "")
                blocks += Block.Button(id, label, action = "startet ${entry.name}")
            }
        }
        buttons = ids
        kinds = entries.associate { it.id to it.kind }
        tiles = looks
        return Page(PAGE, "Apps", blocks)
    }

    companion object {
        const val ID = "ch.madtreasures.g2watch.launcher"
        const val PAGE = "p_apps"

        /** What the launcher says without any app. */
        const val EMPTY = "Noch keine Apps"
    }
}

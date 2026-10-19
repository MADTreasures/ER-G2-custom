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
}

/**
 * The page "Apps" on the glasses (02 §1): every app as a button, which the host lays out as a cell with
 * the app's icon and name ([TileLook]); a dot marks the running ones. It is drawn by the host itself as
 * an internal session, not as an app: the installed watch apps now, Even Hub apps from M3 and remote
 * apps from M5, each kind under its own heading once there is more than one kind.
 */
class Launcher(private val host: LauncherHost) : InternalSession {
    override val manifest = AppManifest(id = ID, name = "Apps", version = "1.0.0", description = "Alle Apps der Brille")

    /** Block id → entry id of the buttons on the page as last built. */
    private var buttons: Map<String, String> = emptyMap()

    /** Block id → icon and marks of the buttons on the page as last built, for the host to draw. */
    var tiles: Map<String, TileLook> = emptyMap()
        private set

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

    private fun page(): Page {
        val entries = host.entries()
        val kinds = entries.map { it.kind }.distinct()
        val blocks = ArrayList<Block>()
        val ids = LinkedHashMap<String, String>()
        val looks = HashMap<String, TileLook>()
        if (entries.isEmpty()) blocks += Block.Text("leer", EMPTY)
        for (kind in LaunchKind.entries) {
            val group = entries.filter { it.kind == kind }
            if (group.isEmpty()) continue
            if (kinds.size > 1) blocks += Block.Heading("h_${kind.name.lowercase()}", kind.heading)
            for (entry in group) {
                val id = "app${ids.size}"
                ids[id] = entry.id
                looks[id] = TileLook(entry.icon, TileLook.initials(entry.name), entry.running)
                val label = entry.name + (entry.detail?.let { " · $it" } ?: "")
                blocks += Block.Button(id, label, action = "startet ${entry.name}")
            }
        }
        buttons = ids
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

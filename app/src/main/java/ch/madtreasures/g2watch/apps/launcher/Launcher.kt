package ch.madtreasures.g2watch.apps.launcher

import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.host.EvenHubRegistry
import ch.madtreasures.g2watch.apps.host.InternalApp

/** What a launcher entry starts: a watch app or a platform part with its own session kind. */
sealed interface Program {
    val manifest: AppManifest

    class Watch(val app: G2App) : Program {
        override val manifest: AppManifest get() = app.manifest
    }

    class Internal(val app: InternalApp) : Program {
        override val manifest: AppManifest get() = app.manifest
    }
}

/** One line of the launcher. */
class LauncherEntry(
    val id: String,
    val name: String,
    val kind: Kind,
    /** A fresh program for a new session; null if it cannot start (e.g. an EvenHub app was removed). */
    val create: () -> Program?,
) {
    enum class Kind { WATCH, INTERNAL, EVENHUB }
}

/**
 * The launcher ("Apps" on the glasses): drawn by the host like a page, not an app. Built-in watch
 * apps first, then platform parts, then EvenHub apps (M3); running apps are marked.
 */
object Launcher {
    const val PAGE_ID = "@launcher"
    const val ENTRY_PREFIX = "@launch:"

    fun entries(
        builtIn: List<() -> G2App>,
        internal: List<() -> InternalApp>,
        evenHub: EvenHubRegistry,
    ): List<LauncherEntry> {
        val out = ArrayList<LauncherEntry>()
        for (factory in builtIn) {
            val m = factory().manifest
            out += LauncherEntry(m.id, m.name, LauncherEntry.Kind.WATCH) { Program.Watch(factory()) }
        }
        for (factory in internal) {
            val m = factory().manifest
            out += LauncherEntry(m.id, m.name, LauncherEntry.Kind.INTERNAL) { Program.Internal(factory()) }
        }
        for (info in evenHub.installed()) {
            out += LauncherEntry(info.id, info.name, LauncherEntry.Kind.EVENHUB) { evenHub.create(info.id)?.let { Program.Internal(it) } }
        }
        return out.distinctBy { it.id }
    }

    fun page(entries: List<LauncherEntry>, running: Set<String>): Page = Page(
        id = PAGE_ID,
        name = "Apps",
        blocks = if (entries.isEmpty()) {
            listOf(Block.Text("@none", "Noch keine Apps installiert."))
        } else {
            entries.map { Block.Button(ENTRY_PREFIX + it.id, it.name, badge = if (it.id in running) "läuft" else null) }
        },
    )
}

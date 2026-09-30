package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import ch.madtreasures.g2watch.apps.host.InstalledApp
import ch.madtreasures.g2watch.apps.host.InstalledApps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executor

/** A package file waiting in the watch's folder for new apps; [problem] says why it cannot be installed. */
data class WaitingPackage(val file: File, val manifest: PackageManifest?, val problem: String?)

/**
 * The app packages of the watch (docs/app-entwicklung/09 §4): what is installed ([installed]), what waits
 * in the folder [inbox] (`Android/data/ch.madtreasures.g2watch/files/apps/` on the watch), installing and
 * removing. All file work runs on [io]; the lists are flows for the watch screen, and [InstalledApps] for
 * the app host, which [onChange] tells about every change.
 */
class AppPackages(
    private val store: PackageStore,
    private val inbox: File?,
    private val loader: PackageLoader,
    private val io: Executor,
    private val reserved: Set<String>,
    private val onChange: () -> Unit = {},
    private val log: (String) -> Unit = {},
) : InstalledApps {

    private val _installed = MutableStateFlow<List<PackageManifest>>(emptyList())
    val installed: StateFlow<List<PackageManifest>> = _installed.asStateFlow()

    private val _waiting = MutableStateFlow<List<WaitingPackage>>(emptyList())
    val waiting: StateFlow<List<WaitingPackage>> = _waiting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** The outcome of the last install or removal, for the watch screen. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Where new package files go, as the wearer sees the path. */
    val inboxPath: String? get() = inbox?.path

    /** Reads the installed packages and the waiting files again. */
    fun rescan() = io.execute {
        scanNow()
        onChange()
    }

    /** Installs [file]; a file from the inbox is deleted afterwards. */
    fun install(file: File) = io.execute {
        _busy.value = true
        try {
            val manifest = store.install(file)
            if (inbox != null && file.parentFile == inbox) file.delete()
            _message.value = "${manifest.app.name} ${manifest.app.version} installiert"
            log("App installiert: ${manifest.app.id} ${manifest.app.version}")
        } catch (e: PackageFormatException) {
            _message.value = e.message
            log("App-Paket ${file.name}: ${e.message}")
        } finally {
            scanNow()
            _busy.value = false
            onChange()
        }
    }

    /** Removes app [id] (its data stays until it is installed again or the watch app is removed). */
    fun remove(id: String) = io.execute {
        val name = _installed.value.firstOrNull { it.app.id == id }?.app?.name ?: id
        if (store.remove(id)) {
            _message.value = "$name entfernt"
            log("App entfernt: $id")
        }
        scanNow()
        onChange()
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun scanNow() {
        _installed.value = store.installed()
        _waiting.value = inbox?.listFiles { f -> f.isFile && f.name.endsWith(".${PackageManifest.EXTENSION}") }.orEmpty()
            .sortedBy { it.name }
            .map { file ->
                try {
                    WaitingPackage(file, PackageArchive.read(file, reserved).manifest, null)
                } catch (e: PackageFormatException) {
                    WaitingPackage(file, null, e.message)
                }
            }
    }

    // --- InstalledApps, for the app host ----------------------------------------------------------

    override val apps: List<AppManifest> get() = _installed.value.map { it.app }

    override fun open(id: String): InstalledApp? {
        val manifest = _installed.value.firstOrNull { it.app.id == id } ?: return null
        val app = loader.load(store.dir(id), manifest)
        return InstalledApp(app) { path -> store.asset(id, path) }
    }
}

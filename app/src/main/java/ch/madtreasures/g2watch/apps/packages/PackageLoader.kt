package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import dalvik.system.DexClassLoader
import java.io.File

/** Makes a [G2App] from an installed package folder (09 §4). */
fun interface PackageLoader {
    /** A new instance of the app in [dir]; throws [PackageFormatException] if it does not load. */
    fun load(dir: File, manifest: PackageManifest): G2App
}

/**
 * Loads a package's DEX files with its own class loader under [parent], the watch app's: so the package
 * sees Kotlin, kotlinx.serialization and the app interface of the watch app and nothing of another
 * package. One class loader per installed copy of an app, so a reinstall gets fresh code.
 */
class DexPackageLoader(private val parent: ClassLoader) : PackageLoader {
    private val loaders = HashMap<String, Pair<String, ClassLoader>>()

    @Synchronized
    override fun load(dir: File, manifest: PackageManifest): G2App {
        val id = manifest.app.id
        val copy = "${manifest.app.version}@${File(dir, "classes.dex").lastModified()}"
        val cached = loaders[id]?.takeIf { it.first == copy }?.second
        val loader = cached ?: run {
            val dex = dir.listFiles { f -> f.isFile && f.name.matches(Regex("classes\\d*\\.dex")) }.orEmpty().sortedBy { it.name }
            if (dex.isEmpty()) throw PackageFormatException("${manifest.app.name}: classes.dex fehlt")
            // Android 14 and newer only load code from files that cannot be written.
            dex.forEach { it.setReadOnly() }
            DexClassLoader(dex.joinToString(File.pathSeparator) { it.path }, null, null, parent).also {
                loaders[id] = Pair(copy, it)
            }
        }
        return instantiate(loader, manifest)
    }

    companion object {
        /** Creates [manifest]'s main class with [loader] and checks that it is the app the package names. */
        fun instantiate(loader: ClassLoader, manifest: PackageManifest): G2App {
            val app = try {
                loader.loadClass(manifest.main).getDeclaredConstructor().newInstance() as? G2App
            } catch (e: ReflectiveOperationException) {
                throw PackageFormatException("${manifest.app.name} lässt sich nicht laden: ${e.cause ?: e}")
            } catch (e: LinkageError) {
                throw PackageFormatException("${manifest.app.name} passt nicht zu dieser Uhr-App: $e")
            } ?: throw PackageFormatException("${manifest.main} ist keine G2App")
            if (app.manifest.id != manifest.app.id || app.manifest.version != manifest.app.version) {
                throw PackageFormatException("${manifest.app.name}: Code und g2app.json passen nicht zusammen")
            }
            return app
        }
    }
}

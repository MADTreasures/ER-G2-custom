@file:JvmName("PackageManifestTool")

package ch.madtreasures.g2watch.apps

import java.io.File
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/**
 * Build step of an app package (09 §3), run on the PC with the package's classes on the class path:
 * finds the one class implementing [G2App], asks it for its manifest and writes `g2app.json`.
 *
 * Arguments: `<classes dir>… --assets <assets dir> --out <g2app.json>`.
 */
fun main(args: Array<String>) {
    val out = args.valueAfter("--out") ?: fail("--out fehlt")
    val assets = args.valueAfter("--assets")?.let(::File)
    val dirs = args.takeWhile { !it.startsWith("--") }.map(::File).filter { it.isDirectory }
    val manifest = try {
        describe(dirs, assets)
    } catch (e: PackageFormatException) {
        fail(e.message.orEmpty())
    }
    File(out).apply { parentFile?.mkdirs() }.writeText(manifest.toJson())
}

/** The package manifest of the app in [classDirs]; its page file must be in [assets]. */
fun describe(classDirs: List<File>, assets: File?, loader: ClassLoader = G2App::class.java.classLoader): PackageManifest {
    val names = classDirs.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
    }
    val apps = names.mapNotNull { name ->
        val c = try {
            Class.forName(name, false, loader)
        } catch (e: LinkageError) {
            null
        }
        c?.takeIf { G2App::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
    }
    val main = when (apps.size) {
        0 -> throw PackageFormatException("Keine Klasse implementiert G2App")
        1 -> apps.single()
        else -> throw PackageFormatException("Mehr als eine App im Paket: ${apps.joinToString { it.name }}")
    }
    val app = try {
        main.getDeclaredConstructor().newInstance() as G2App
    } catch (e: NoSuchMethodException) {
        throw PackageFormatException("${main.name} braucht einen Konstruktor ohne Argumente")
    }
    val manifest = app.manifest
    manifest.ui?.let { ui ->
        if (assets == null || !File(assets, ui).isFile) throw PackageFormatException("Seiten $ui fehlen in src/main/assets")
    }
    return PackageManifest(G2AppApi.VERSION, main.name, manifest)
}

private fun Array<String>.valueAfter(flag: String): String? = indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

private fun fail(message: String): Nothing {
    System.err.println("App-Paket: $message")
    exitProcess(1)
}

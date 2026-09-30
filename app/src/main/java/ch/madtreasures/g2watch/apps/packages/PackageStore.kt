package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The installed app packages (09 §4): one folder per app, `<root>/<app id>/` with `g2app.json`, the DEX
 * files (read-only, as Android requires for loaded code) and `assets/`. Installing unpacks into a fresh
 * folder first and swaps it in only when everything is there, so a failed update keeps the old version.
 * Not thread-safe: [AppPackages] calls it from one thread.
 */
class PackageStore(private val root: File, private val reserved: Set<String>) {

    /** The installed packages, sorted by name; broken folders are skipped. */
    fun installed(): List<PackageManifest> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { dir ->
            try {
                PackageManifest.parse(File(dir, PackageManifest.FILE).readText())
            } catch (e: PackageFormatException) {
                null
            } catch (e: IOException) {
                null
            }
        }
        .sortedBy { it.app.name.lowercase() }

    /** Checks [file] and installs it, replacing an installed version of the same app. */
    fun install(file: File): PackageManifest {
        val archive = PackageArchive.read(file, reserved)
        val id = archive.manifest.app.id
        root.mkdirs()
        val fresh = File(root, ".neu-$id")
        val old = File(root, ".alt-$id")
        fresh.deleteRecursively()
        old.deleteRecursively()
        try {
            unpack(archive, fresh)
            val target = dir(id)
            if (target.exists() && !target.renameTo(old)) throw IOException("alte Version lässt sich nicht verschieben")
            if (!fresh.renameTo(target)) {
                old.renameTo(target)
                throw IOException("neue Version lässt sich nicht ablegen")
            }
        } catch (e: IOException) {
            throw PackageFormatException("Installation fehlgeschlagen: ${e.message}")
        } finally {
            fresh.deleteRecursively()
            old.deleteRecursively()
        }
        return archive.manifest
    }

    /** Removes app [id]; false if it was not installed. */
    fun remove(id: String): Boolean {
        val dir = dir(id)
        if (!dir.isDirectory) return false
        val gone = File(root, ".weg-$id")
        gone.deleteRecursively()
        if (!dir.renameTo(gone)) return dir.deleteRecursively()
        gone.deleteRecursively()
        return true
    }

    /** The folder of app [id]. */
    fun dir(id: String): File {
        require(PackageArchive.safe(id) && '/' !in id) { "bad app id $id" }
        return File(root, id)
    }

    /** File [path] of app [id]'s `assets/`, or null. */
    fun asset(id: String, path: String): ByteArray? {
        if (!PackageArchive.safe(path)) return null
        val file = File(File(dir(id), "assets"), path)
        return if (file.isFile) file.readBytes() else null
    }

    private fun unpack(archive: PackageArchive, into: File) {
        var written = 0L
        ZipFile(archive.file).use { zip ->
            for (name in archive.entries) {
                val entry = zip.getEntry(name) ?: continue
                val out = File(into, name)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            // The sizes in the ZIP directory can lie; count what really comes out.
                            if (written > PackageArchive.MAX_UNPACKED_BYTES) throw PackageFormatException("Paket entpackt zu groß")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (name.endsWith(".dex")) out.setReadOnly()
            }
        }
    }
}

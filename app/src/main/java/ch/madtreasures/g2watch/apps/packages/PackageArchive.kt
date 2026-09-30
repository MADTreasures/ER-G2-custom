package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.G2AppApi
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * An app package file (`.g2app`, docs/app-entwicklung/09 §2), checked before anything of it is unpacked
 * or run: a ZIP with `g2app.json`, `classes.dex` (plus `classes2.dex` …) and files under `assets/`.
 * Every problem ends in a [PackageFormatException] with a German message for the watch.
 */
class PackageArchive private constructor(val file: File, val manifest: PackageManifest, val entries: List<String>) {

    companion object {
        /** Largest package file. */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024

        /** Largest unpacked size, against ZIP bombs. */
        const val MAX_UNPACKED_BYTES = 50L * 1024 * 1024

        const val MAX_ENTRIES = 2000

        private val DEX = Regex("classes\\d*\\.dex")

        /** Whether [name] is a file the watch unpacks from a package. */
        fun isPackageFile(name: String): Boolean = name == PackageManifest.FILE || DEX.matches(name) || name.startsWith("assets/")

        /** Reads and checks [file]; [reserved] are app ids a package may not take (the built-in apps). */
        fun read(file: File, reserved: Set<String> = emptySet()): PackageArchive {
            if (!file.isFile) throw PackageFormatException("Datei ${file.name} fehlt")
            if (file.length() > MAX_FILE_BYTES) throw PackageFormatException("Paket zu groß (höchstens ${MAX_FILE_BYTES / 1024 / 1024} MB)")
            try {
                ZipFile(file).use { zip ->
                    val names = ArrayList<String>()
                    var unpacked = 0L
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (!safe(name)) throw PackageFormatException("Unzulässiger Pfad im Paket: $name")
                        if (!isPackageFile(name)) continue
                        if (names.size >= MAX_ENTRIES) throw PackageFormatException("Zu viele Dateien im Paket")
                        unpacked += entry.size.coerceAtLeast(0)
                        if (unpacked > MAX_UNPACKED_BYTES) throw PackageFormatException("Paket entpackt zu groß")
                        names += name
                    }
                    val json = zip.getEntry(PackageManifest.FILE)
                        ?: throw PackageFormatException("Kein App-Paket: ${PackageManifest.FILE} fehlt")
                    val manifest = PackageManifest.parse(zip.getInputStream(json).use { it.readBytes() }.toString(Charsets.UTF_8))
                    if (manifest.api > G2AppApi.VERSION) {
                        throw PackageFormatException("${manifest.app.name} braucht eine neuere Uhr-App (Schnittstelle ${manifest.api})")
                    }
                    if (manifest.app.id in reserved) {
                        throw PackageFormatException("${manifest.app.id} ist schon fest in die Uhr-App eingebaut")
                    }
                    val dex = zip.getEntry("classes.dex") ?: throw PackageFormatException("classes.dex fehlt")
                    val magic = zip.getInputStream(dex).use { it.readNBytes(4) }
                    if (!magic.contentEquals("dex\n".toByteArray())) throw PackageFormatException("classes.dex ist kein DEX")
                    manifest.app.ui?.let { ui ->
                        if ("assets/$ui" !in names) throw PackageFormatException("Seiten $ui fehlen im Paket")
                    }
                    return PackageArchive(file, manifest, names)
                }
            } catch (e: ZipException) {
                throw PackageFormatException("${file.name} ist keine ZIP-Datei")
            } catch (e: IOException) {
                throw PackageFormatException("${file.name} ließ sich nicht lesen: ${e.message}")
            }
        }

        /** A relative path inside the package, without `..`, drive letters or backslashes. */
        fun safe(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && ':' !in name &&
                name.split('/').none { it == ".." || it == "." || it.isEmpty() }
    }
}

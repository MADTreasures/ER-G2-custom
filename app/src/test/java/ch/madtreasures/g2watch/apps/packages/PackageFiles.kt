package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2AppApi
import ch.madtreasures.g2watch.apps.PackageManifest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A DEX file header; the tests never run package code as DEX. */
val DEX_BYTES = "dex\n039\u0000".toByteArray() + ByteArray(104)

fun manifestJson(
    id: String = "ch.test.paket",
    name: String = "Paket",
    version: String = "1.0.0",
    ui: String? = null,
    api: Int = G2AppApi.VERSION,
    main: String = "ch.test.paket.PaketApp",
): String = PackageManifest(api, main, AppManifest(id, name, version, ui = ui)).toJson()

/** Writes a `.g2app` with [files] (path → content) into [dir]. */
fun packageFile(dir: File, name: String = "paket.g2app", files: Map<String, ByteArray>): File {
    val file = File(dir, name)
    ZipOutputStream(file.outputStream()).use { zip ->
        for ((path, bytes) in files) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return file
}

/** A valid package: manifest, DEX header and, if [ui] is set, that page file. */
fun validPackage(dir: File, name: String = "paket.g2app", version: String = "1.0.0", ui: String? = null, extra: Map<String, ByteArray> = emptyMap()): File =
    packageFile(
        dir, name,
        mapOf(PackageManifest.FILE to manifestJson(version = version, ui = ui).toByteArray(), "classes.dex" to DEX_BYTES) +
            (ui?.let { mapOf("assets/$it" to "{}".toByteArray()) } ?: emptyMap()) + extra,
    )

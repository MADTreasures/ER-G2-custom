package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2AppApi
import ch.madtreasures.g2watch.apps.PackageManifest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** A DEX file header; the tests never run package code as DEX. */
val DEX_BYTES = "dex\n039\u0000".toByteArray() + ByteArray(104)

fun manifestJson(
    id: String = "ch.test.paket",
    name: String = "Paket",
    version: String = "1.0.0",
    ui: String? = null,
    api: Int = G2AppApi.VERSION,
    main: String = "ch.test.paket.PaketApp",
    icon: String? = null,
): String = PackageManifest(api, main, AppManifest(id, name, version, ui = ui), icon).toJson()

/** Where the test packages keep their launcher icon. */
const val ICON_PATH = "apps/ch.test.paket/icon.png"

/** A grey PNG of [w] × [h], every pixel [value]. */
fun pngBytes(w: Int = 64, h: Int = w, value: Int = 0): ByteArray {
    val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
    image.raster.apply { for (y in 0 until h) for (x in 0 until w) setSample(x, y, 0, value) }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

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

/** A valid package: manifest, DEX header and, if [ui] is set, that page file; with [icon], that icon at [ICON_PATH]. */
fun validPackage(
    dir: File,
    name: String = "paket.g2app",
    version: String = "1.0.0",
    ui: String? = null,
    extra: Map<String, ByteArray> = emptyMap(),
    icon: ByteArray? = null,
): File =
    packageFile(
        dir, name,
        mapOf(
            PackageManifest.FILE to manifestJson(version = version, ui = ui, icon = icon?.let { ICON_PATH }).toByteArray(),
            "classes.dex" to DEX_BYTES,
        ) +
            (ui?.let { mapOf("assets/$it" to "{}".toByteArray()) } ?: emptyMap()) +
            (icon?.let { mapOf("assets/$ICON_PATH" to it) } ?: emptyMap()) + extra,
    )

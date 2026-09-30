package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.G2AppApi
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/** Checking a package file before anything of it is unpacked (09 §2). */
class PackageArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun refused(message: String, files: Map<String, ByteArray>, reserved: Set<String> = emptySet()) {
        val file = packageFile(tmp.root, files = files)
        val e = runCatching { PackageArchive.read(file, reserved) }.exceptionOrNull()
        assertTrue("expected refusal, got $e", e is PackageFormatException)
        assertTrue("„${e!!.message}“ should mention „$message“", e.message!!.contains(message))
    }

    private val json = PackageManifest.FILE to manifestJson().toByteArray()
    private val dex = "classes.dex" to DEX_BYTES

    @Test
    fun `a valid package is read without unpacking`() {
        val archive = PackageArchive.read(validPackage(tmp.root, ui = "apps/ch.test.paket/ui.json", extra = mapOf("META-INF/x" to ByteArray(1))))
        assertEquals("ch.test.paket", archive.manifest.app.id)
        assertEquals(G2AppApi.VERSION, archive.manifest.api)
        assertEquals("ch.test.paket.PaketApp", archive.manifest.main)
        // Only what the watch uses is listed for unpacking.
        assertEquals(setOf(PackageManifest.FILE, "classes.dex", "assets/apps/ch.test.paket/ui.json"), archive.entries.toSet())
    }

    @Test
    fun `broken packages are refused with a reason`() {
        refused("g2app.json fehlt", mapOf(dex))
        refused("classes.dex fehlt", mapOf(json))
        refused("kein DEX", mapOf(json, "classes.dex" to "PK".toByteArray()))
        refused("Kein App-Paket", mapOf(PackageManifest.FILE to """{"format":"g2app@1"}""".toByteArray(), dex))
        refused("neuere Uhr-App", mapOf(PackageManifest.FILE to manifestJson(api = G2AppApi.VERSION + 1).toByteArray(), dex))
        refused("fest in die Uhr-App eingebaut", mapOf(json, dex), reserved = setOf("ch.test.paket"))
        refused("Seiten apps/x/ui.json fehlen", mapOf(PackageManifest.FILE to manifestJson(ui = "apps/x/ui.json").toByteArray(), dex))
        refused("Unzulässiger Pfad", mapOf(json, dex, "assets/../../evil" to ByteArray(1)))
        refused("Unzulässiger Pfad", mapOf(json, dex, "/etc/evil" to ByteArray(1)))
    }

    @Test
    fun `not a zip and too large files are refused`() {
        val text = tmp.newFile("text.g2app").apply { writeText("hallo") }
        assertTrue(runCatching { PackageArchive.read(text) }.exceptionOrNull()!!.message!!.contains("keine ZIP-Datei"))
        val big = tmp.newFile("big.g2app")
        RandomAccessFile(big, "rw").use { it.setLength(PackageArchive.MAX_FILE_BYTES + 1) }
        assertTrue(runCatching { PackageArchive.read(big) }.exceptionOrNull()!!.message!!.contains("zu groß"))
    }

    @Test
    fun `manifest fields are checked`() {
        fun bad(json: String) = runCatching { PackageManifest.parse(json) }.exceptionOrNull() is PackageFormatException
        assertTrue(bad("[]"))
        assertTrue(bad("""{"format":"g2app-paket@1","api":1,"main":"x.Y","id":"Bad Id","name":"N","version":"1.0.0"}"""))
        assertTrue(bad("""{"format":"g2app-paket@1","api":1,"main":"no class","id":"ch.a.b","name":"N","version":"1.0.0"}"""))
        assertTrue(bad("""{"format":"g2app-paket@1","api":1,"main":"x.Y","id":"ch.a.b","name":"N","version":"1.0.0","permissions":["root"]}"""))
        assertFalse(bad("""{"format":"g2app-paket@1","api":1,"main":"x.Y","id":"ch.a.b","name":"N","version":"1.0.0","permissions":["network"]}"""))
    }

    @Test
    fun `safe paths`() {
        assertTrue(PackageArchive.safe("assets/apps/a/ui.json"))
        listOf("", "/a", "a/../b", "..", "a\\b", "c:/x", "a//b", "./a").forEach { assertFalse(it, PackageArchive.safe(it)) }
    }
}

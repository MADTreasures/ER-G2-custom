package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.PackageManifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executor

/** Installing, updating and removing packages on the watch, and the list the watch screen shows (09 §4). */
class PackageStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("app-packages") }
    private val store by lazy { PackageStore(root, reserved = setOf("ch.madtreasures.youtube")) }

    @Test
    fun `install unpacks manifest, read-only DEX and assets`() {
        val m = store.install(validPackage(tmp.root, ui = "apps/ch.test.paket/ui.json"))
        assertEquals("ch.test.paket", m.app.id)
        val dir = File(root, "ch.test.paket")
        assertTrue(File(dir, PackageManifest.FILE).isFile)
        // Android 14+ loads code only from files nobody can write (canWrite() is always true for root).
        assertFalse(PosixFilePermission.OWNER_WRITE in Files.getPosixFilePermissions(File(dir, "classes.dex").toPath()))
        assertArrayEquals("{}".toByteArray(), store.asset("ch.test.paket", "apps/ch.test.paket/ui.json"))
        assertNull(store.asset("ch.test.paket", "../g2app.json"))
        assertEquals(listOf("ch.test.paket"), store.installed().map { it.app.id })
        // Nothing half-done is left next to it.
        assertEquals(listOf("ch.test.paket"), root.list()!!.toList())
    }

    @Test
    fun `an update replaces the old version, a broken one keeps it`() {
        store.install(validPackage(tmp.root, name = "v1.g2app", ui = "apps/ch.test.paket/ui.json"))
        store.install(validPackage(tmp.root, name = "v2.g2app", version = "1.1.0"))
        assertEquals("1.1.0", store.installed().single().app.version)
        assertNull("files of the old version are gone", store.asset("ch.test.paket", "apps/ch.test.paket/ui.json"))

        val broken = packageFile(tmp.root, "v3.g2app", mapOf(PackageManifest.FILE to manifestJson(version = "2.0.0").toByteArray()))
        assertTrue(runCatching { store.install(broken) }.exceptionOrNull() is PackageFormatException)
        assertEquals("1.1.0", store.installed().single().app.version)
    }

    @Test
    fun `remove deletes the app, built-in ids cannot be taken`() {
        store.install(validPackage(tmp.root))
        assertTrue(store.remove("ch.test.paket"))
        assertFalse(store.remove("ch.test.paket"))
        assertEquals(emptyList<PackageManifest>(), store.installed())

        val youtube = packageFile(
            tmp.root, "yt.g2app",
            mapOf(PackageManifest.FILE to manifestJson(id = "ch.madtreasures.youtube").toByteArray(), "classes.dex" to DEX_BYTES),
        )
        assertTrue(runCatching { store.install(youtube) }.exceptionOrNull()!!.message!!.contains("fest in die Uhr-App eingebaut"))
    }

    private class NoApp(override val manifest: AppManifest) : G2App {
        override fun onEvent(event: AppEvent, ui: AppContext) = Unit
    }

    @Test
    fun `the watch lists waiting files, installs them from the folder and removes apps`() {
        val inbox = tmp.newFolder("apps")
        val direct = Executor { it.run() }
        var changes = 0
        val loaded = mutableListOf<String>()
        val packages = AppPackages(
            store, inbox,
            loader = { dir, m -> loaded += dir.name; NoApp(m.app) },
            io = direct, reserved = emptySet(), onChange = { changes++ },
        )
        validPackage(inbox, name = "paket.g2app", ui = "apps/ch.test.paket/ui.json")
        File(inbox, "kaputt.g2app").writeText("kein zip")
        File(inbox, "notiz.txt").writeText("wird ignoriert")
        packages.rescan()
        val waiting = packages.waiting.value
        assertEquals(listOf("kaputt.g2app", "paket.g2app"), waiting.map { it.file.name })
        assertEquals("kaputt.g2app ist keine ZIP-Datei", waiting[0].problem)
        assertEquals("Paket", waiting[1].manifest?.app?.name)

        packages.install(waiting[1].file)
        assertEquals("Paket 1.0.0 installiert", packages.message.value)
        assertFalse("the installed file leaves the folder", File(inbox, "paket.g2app").exists())
        assertEquals(listOf("ch.test.paket"), packages.apps.map { it.id })
        assertEquals(listOf("kaputt.g2app"), packages.waiting.value.map { it.file.name })

        val opened = packages.open("ch.test.paket")!!
        assertEquals(listOf("ch.test.paket"), loaded)
        assertEquals("ch.test.paket", opened.app.manifest.id)
        assertArrayEquals("{}".toByteArray(), opened.asset("apps/ch.test.paket/ui.json"))
        assertNull(packages.open("ch.test.fehlt"))

        packages.install(File(inbox, "kaputt.g2app"))
        assertEquals("kaputt.g2app ist keine ZIP-Datei", packages.message.value)
        assertTrue("a refused file stays for the wearer to see", File(inbox, "kaputt.g2app").exists())

        packages.remove("ch.test.paket")
        assertEquals("Paket entfernt", packages.message.value)
        assertEquals(emptyList<AppManifest>(), packages.apps)
        assertEquals(4, changes)
    }
}

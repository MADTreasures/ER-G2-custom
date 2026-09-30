package ch.madtreasures.g2watch.apps.packages

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.G2AppApi
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PackageFormatException
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.host.HostPorts
import ch.madtreasures.g2watch.apps.host.InstalledApp
import ch.madtreasures.g2watch.apps.host.InstalledApps
import ch.madtreasures.g2watch.apps.launcher.Launcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * The package files that `./gradlew :packages:<name>:g2app` really builds (09 §3), installed and run in
 * the app host. Only the DEX step is left out: the JVM runs the same classes from the test class path.
 */
class InstalledPackagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val fakePorts = FakePorts()

    /** No APK assets: pages and pictures must come from the package itself. */
    private val ports: HostPorts = object : HostPorts by fakePorts {
        override fun asset(path: String): ByteArray? = null
    }

    /** The files of build/g2app/ of every app package, as app/build.gradle.kts passes them. */
    private val built: List<File> = System.getProperty("g2app.packages").orEmpty().split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .flatMap { File(it).listFiles { f -> f.name.endsWith(".g2app") }.orEmpty().toList() }

    private val classPath = PackageLoader { _, m -> DexPackageLoader.instantiate(javaClass.classLoader!!, m) }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.launcherButtons(): List<String> =
        currentPageOf(Launcher.ID)!!.blocks.filterIsInstance<Block.Button>().map { it.text }

    @Test
    fun `every package folder builds one valid package named after its app`() {
        val folders = File("../packages").listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile }
        assertTrue("stoppuhr and einkauf at least", folders.size >= 2)
        assertEquals(folders.size, built.size)
        for (file in built) {
            val archive = PackageArchive.read(file)
            val m = archive.manifest
            assertEquals(G2AppApi.VERSION, m.api)
            assertEquals("${m.app.id}-${m.app.version}.g2app", file.name)
            assertEquals(m.app, DexPackageLoader.instantiate(javaClass.classLoader!!, m).manifest)
        }
    }

    @Test
    fun `installed packages show in the launcher, run with their own pages and end when removed`() {
        val inbox = tmp.newFolder("apps")
        built.forEach { it.copyTo(File(inbox, it.name)) }
        var host: AppHost? = null
        val packages = AppPackages(
            PackageStore(tmp.newFolder("app-packages"), emptySet()), inbox, classPath,
            io = Executor { it.run() }, reserved = emptySet(), onChange = { host?.packagesChanged() },
        )
        host = AppHost(scheduler, screen, FakeText(), ports, builtIn = emptyList(), installed = packages, nowMs = { scheduler.now })
        packages.rescan()
        packages.waiting.value.forEach { packages.install(it.file) }
        assertEquals(emptyList<File>(), inbox.listFiles()!!.toList())

        host.openLauncher()
        settle()
        assertEquals(listOf("Einkauf", "Stoppuhr"), host.launcherButtons().filter { it == "Einkauf" || it == "Stoppuhr" })

        // Einkauf takes its page from assets/ of its package, not from the APK.
        host.launch("watch:ch.madtreasures.einkauf")
        settle()
        assertEquals("ch.madtreasures.einkauf", host.state.value.visible)
        assertEquals("p_liste", host.state.value.page)
        assertTrue(fakePorts.logs.none { it.contains("fehlen") })

        packages.remove("ch.madtreasures.einkauf")
        settle()
        assertFalse("ch.madtreasures.einkauf" in host.state.value.running)
        assertEquals(Launcher.ID, host.state.value.visible)
        assertFalse(host.launcherButtons().any { it.startsWith("Einkauf") })
    }

    private class Broken(override val manifest: AppManifest, private val error: Throwable) : G2App {
        override fun onEvent(event: AppEvent, ui: AppContext) {
            if (event == AppEvent.Start) {
                ui.definePages(listOf(Page("p", "P", listOf(Block.Button("b", "Los")))))
                ui.show("p")
            }
            if (event is AppEvent.Click) throw error
        }
    }

    @Test
    fun `a package that does not load, or calls what is not there, ends alone`() {
        val bad = AppManifest("ch.test.alt", "Alt", "1.0.0")
        val clicks = AppManifest("ch.test.klick", "Klick", "1.0.0")
        val installed = object : InstalledApps {
            override val apps = listOf(bad, clicks)

            override fun open(id: String): InstalledApp? = when (id) {
                bad.id -> throw PackageFormatException("Alt passt nicht zu dieser Uhr-App")
                clicks.id -> InstalledApp(Broken(clicks, NoSuchMethodError("AppContext.gibtsNicht"))) { null }
                else -> null
            }
        }
        val host = AppHost(scheduler, screen, FakeText(), ports, builtIn = emptyList(), installed = installed, nowMs = { scheduler.now })

        host.launch("watch:ch.test.alt")
        settle()
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue(fakePorts.logs.any { it.contains("Alt passt nicht zu dieser Uhr-App") })

        host.launch("watch:ch.test.klick")
        settle()
        assertEquals("ch.test.klick", host.state.value.visible)
        // A temple click on the focused button.
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertTrue(fakePorts.logs.any { it.contains("abgestürzt") && it.contains("gibtsNicht") })
        assertFalse("ch.test.klick" in host.state.value.running)
    }
}

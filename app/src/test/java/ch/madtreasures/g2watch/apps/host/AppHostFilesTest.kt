package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.DownloadState
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Large files, downloads and background work of the apps (interface 5) in the app host. */
class AppHostFilesTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val text = FakeText()
    private val ports = FakePorts()
    private val created = mutableListOf<ScriptedApp>()
    private val contexts = HashMap<String, AppContext>()

    private fun app(id: String = ID, permissions: Set<Permission> = setOf(Permission.NETWORK)): () -> G2App = {
        ScriptedApp(AppManifest(id, id.substringAfterLast('.'), "1.0.0", permissions = permissions)) { event, ui ->
            contexts[id] = ui
            if (event == AppEvent.Start) {
                ui.definePages(listOf(Page("start_$id".replace('.', '_'), "Start", listOf(Block.Text("t_${id.replace('.', '_')}", "Dateien")))))
                ui.show("start_$id".replace('.', '_'))
            }
        }.also { created += it }
    }

    private fun host(vararg apps: () -> G2App) = AppHost(
        scheduler, screen, text, ports, apps.toList(),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.start(id: String = ID, granted: Set<Permission> = setOf(Permission.NETWORK)): ScriptedApp {
        ports.answers["$id@1.0.0"] = granted
        launch("watch:$id")
        settle()
        return created.last()
    }

    private fun asApp(id: String = ID, block: AppContext.() -> Unit) {
        scheduler.post { contexts[id]!!.block() }
        settle()
    }

    private fun ScriptedApp.downloads() = events.filterIsInstance<AppEvent.Download>()

    private fun refused(what: String) = ports.logs.any { "abgelehnt" in it && what in it }

    @Test
    fun `every app has a folder of its own, and the free space is that of the watch`() {
        val host = host(app(), app(OTHER))
        host.start()
        var dir: File? = null
        var free = 0L
        asApp { dir = files.dir; free = files.freeBytes }
        assertEquals(File(ports.filesRoot, ID), dir)
        assertTrue(dir!!.isDirectory)
        assertTrue(free > 0)
        host.start(OTHER)
        var other: File? = null
        asApp(OTHER) { other = files.dir }
        assertNotEquals(dir, other)
    }

    @Test
    fun `a download lands in the app's folder and reports progress and its end`() {
        val host = host(app())
        val app = host.start()
        asApp { files.download("schweiz", "https://download.example/maps/switzerland.map", "maps/switzerland.map") }
        val d = ports.downloads.started.single()
        assertEquals("https://download.example/maps/switzerland.map", d.request.url)
        assertEquals(File(File(ports.filesRoot, ID), "maps/switzerland.map"), d.request.target)
        assertTrue(d.request.wifiOnly)

        d.listener.onProgress(1_000, 4_000)
        settle()
        d.listener.onEnd(DownloadState.DONE, 4_000, 4_000, null)
        settle()
        assertEquals(
            listOf(
                AppEvent.Download("schweiz", DownloadState.RUNNING, 1_000, 4_000),
                AppEvent.Download("schweiz", DownloadState.DONE, 4_000, 4_000),
            ),
            app.downloads(),
        )
    }

    @Test
    fun `LTE only when the app allows it, and a failure reaches app and log`() {
        val host = host(app())
        val app = host.start()
        asApp { files.download("x", "https://example.org/x.bin", "x.bin", wifiOnly = false) }
        val d = ports.downloads.started.single()
        assertFalse(d.request.wifiOnly)
        d.listener.onEnd(DownloadState.FAILED, 10, 100, "kein WLAN erreichbar")
        settle()
        assertEquals(AppEvent.Download("x", DownloadState.FAILED, 10, 100, "kein WLAN erreichbar"), app.downloads().single())
        assertTrue(ports.logs.any { "x.bin" in it && "kein WLAN erreichbar" in it })
    }

    @Test
    fun `downloads need the network permission, https and a plain file name`() {
        val host = host(app())
        host.start(granted = emptySet())
        asApp { files.download("a", "https://example.org/a", "a.bin") }
        assertTrue(refused("Keine Berechtigung: Internet"))
        assertTrue(ports.downloads.started.isEmpty())
    }

    @Test
    fun `addresses without https and names that leave the folder are refused`() {
        val host = host(app())
        host.start()
        asApp { files.download("a", "http://example.org/a", "a.bin") }
        assertTrue(refused("Nur https://"))
        for (name in listOf("../a.bin", "maps/../../a", "/etc/a", "a b", "", "maps//a")) {
            asApp { files.download("a", "https://example.org/a", name) }
        }
        assertTrue(ports.downloads.started.isEmpty())
        assertEquals(6, ports.logs.count { "Ungültiger Dateiname" in it })
    }

    @Test
    fun `cancelling reports it, and the same tag again replaces the running download quietly`() {
        val host = host(app())
        val app = host.start()
        asApp { files.download("k", "https://example.org/1", "k.bin") }
        val first = ports.downloads.started.last()
        asApp { files.download("k", "https://example.org/2", "k.bin") }
        val second = ports.downloads.started.last()
        assertTrue(first.cancelled)
        first.listener.onEnd(DownloadState.CANCELLED, 5, 10, null)
        settle()
        assertTrue(app.downloads().isEmpty()) // the replaced one says nothing

        asApp { files.cancelDownload("k") }
        assertTrue(second.cancelled)
        second.listener.onEnd(DownloadState.CANCELLED, 7, 10, null)
        settle()
        assertEquals(listOf(AppEvent.Download("k", DownloadState.CANCELLED, 7, 10)), app.downloads())
    }

    @Test
    fun `downloads end with the app`() {
        val host = host(app())
        val app = host.start()
        asApp { files.download("k", "https://example.org/1", "k.bin") }
        val d = ports.downloads.started.single()
        asApp { close() }
        assertTrue(d.cancelled)
        d.listener.onEnd(DownloadState.CANCELLED, 1, 2, null)
        settle()
        assertTrue(app.downloads().isEmpty())
    }

    @Test
    fun `an app reads the files it ships with`() {
        ports.assets["apps/$ID/tabelle.json"] = """{"a":1}""".toByteArray()
        val host = host(app())
        host.start()
        var found: ByteArray? = null
        var missing: ByteArray? = byteArrayOf(1)
        asApp {
            found = asset("apps/$ID/tabelle.json")
            missing = asset("apps/$ID/fehlt.json")
        }
        assertEquals("""{"a":1}""", found!!.decodeToString())
        assertNull(missing)
    }

    @Test
    fun `work runs off the app thread and its result comes back to the app`() {
        val host = host(app())
        host.start()
        val results = mutableListOf<Result<Int>>()
        var ranOn: Thread? = null
        asApp { work("route", { ranOn = Thread.currentThread(); 6 * 7 }) { results += it } }
        assertTrue(results.isEmpty()) // not before the task ran
        ports.runWork()
        settle()
        assertEquals(listOf(Result.success(42)), results)
        assertTrue(ranOn != null)
    }

    @Test
    fun `a failure of the task arrives as a failure, and the app keeps running`() {
        val host = host(app())
        host.start()
        var result: Result<String>? = null
        asApp { work("kaputt", { error("Karte unlesbar") }) { result = it } }
        ports.runWork()
        settle()
        assertEquals("Karte unlesbar", result!!.exceptionOrNull()!!.message)
        assertTrue(ID in host.state.value.running)
    }

    @Test
    fun `one tag at a time, a waiting task gives way to a newer one, and two tags run side by side`() {
        val host = host(app())
        host.start()
        val done = mutableListOf<String>()
        asApp {
            work("bild", { "bild 1" }) { done += it.getOrThrow() }
            work("bild", { "bild 2" }) { done += it.getOrThrow() }
            work("bild", { "bild 3" }) { done += it.getOrThrow() }
            work("route", { "route" }) { done += it.getOrThrow() }
            work("suche", { "suche" }) { done += it.getOrThrow() }
        }
        // "bild 1" and "route" run; "bild 3" waits behind "bild 1" ("bild 2" gave way), "suche" behind both.
        assertEquals(2, ports.work.size)
        ports.runWork()
        settle()
        ports.runWork()
        settle()
        assertEquals(listOf("bild 1", "route", "bild 3", "suche"), done)
    }

    @Test
    fun `work ends with the app, no result reaches it any more`() {
        val host = host(app())
        host.start()
        var result: Result<Int>? = null
        asApp { work("lang", { 1 }) { result = it } }
        asApp { close() }
        ports.runWork()
        settle()
        assertNull(result)
    }

    private companion object {
        const val ID = "ch.test.karte"
        const val OTHER = "ch.test.andere"
    }
}

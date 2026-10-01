package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.WebAction
import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebField
import ch.madtreasures.g2watch.apps.WebState
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.browser.BrowserApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web pages in image blocks (05 §10, M7) in the app host, with a fake engine and virtual time. */
class AppHostWebTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val ports = FakePorts()
    private val created = mutableListOf<ScriptedApp>()

    private val list = Page("liste", "Liste", listOf(Block.Button("zur_seite", "Seite", target = "web")))
    private val web = Page("web", "Seite", listOf(Block.Image("seite", null, 576, 260, bleed = true)))

    private var context: AppContext? = null

    private val pages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        context = ui
        if (event == AppEvent.Start) {
            ui.definePages(listOf(list, web))
            ui.show("liste")
        }
    }

    private fun app(id: String = "ch.test.web", permissions: Set<Permission> = setOf(Permission.NETWORK)): () -> G2App = {
        ScriptedApp(AppManifest(id, id.substringAfterLast('.'), "1.0.0", permissions = permissions), pages).also { created += it }
    }

    private fun host(vararg apps: () -> G2App) = AppHost(
        scheduler, screen, FakeText(), ports, apps.toList(),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.start(id: String = "ch.test.web", granted: Set<Permission> = setOf(Permission.NETWORK)): ScriptedApp {
        ports.answers["$id@1.0.0"] = granted
        launch("watch:$id")
        settle()
        return created.last()
    }

    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    /** Starts the app and opens [url] on its page with the image block. */
    private fun AppHost.withPage(url: String = "https://www.srf.ch/news"): ScriptedApp {
        val app = start()
        asApp {
            show("web")
            web("seite", WebAction.Open(url, reader = true))
        }
        return app
    }

    private val page get() = ports.web.pages.last()

    private fun ScriptedApp.webEvents() = events.filterIsInstance<AppEvent.Web>()

    private fun ScriptedApp.inputEvents() = events.filter { it is AppEvent.ImageClick || it is AppEvent.ImageScroll }

    @Test
    fun `a page opens at the size of its block, reports to the app and fills the block`() {
        val host = host(app())
        val app = host.withPage()
        assertEquals(1, ports.web.pages.size)
        val request = page.request
        assertEquals("https://www.srf.ch/news", request.url)
        assertEquals(576, request.width)
        assertEquals(260, request.height)
        assertTrue(request.reader)
        assertTrue(page.active)

        page.state(WebState.LOADING)
        page.state(WebState.READY, title = "SRF News", canBack = false)
        settle()
        assertEquals(listOf(WebState.LOADING, WebState.READY), app.webEvents().map { it.state })
        assertEquals("SRF News", app.webEvents().last().title)

        // The engine's picture goes into the block, which fills the app area.
        val picture = GrayRaster(576, 260).apply { fillRect(100, 50, 200, 20, 255) }
        page.listener.onFrame(picture)
        settle()
        val shown = screen.last
        assertEquals(255, shown.pixels[60 * 576 + 150].toInt() and 0xFF)
        assertEquals(0, shown.pixels[150 * 576 + 400].toInt() and 0xFF)
        // A picture of another size does not fit the block and is dropped.
        page.listener.onFrame(GrayRaster(100, 100).apply { clear(255) })
        settle()
        assertEquals(0, screen.last.pixels[150 * 576 + 400].toInt() and 0xFF)
    }

    @Test
    fun `addresses need http and the network permission, a page needs a large enough image block`() {
        val host = host(app(), app("ch.test.offline", permissions = emptySet()))
        host.start()
        asApp {
            web("seite", WebAction.Open("file:///data/data/ch.madtreasures.g2watch/files/x"))
            web("seite", WebAction.Open("javascript:alert(1)"))
            web("liste", WebAction.Open("https://example.org/"))
            web("seite", WebAction.Tap(1, 1))
        }
        assertTrue(ports.web.pages.isEmpty())
        assertEquals(4, ports.logs.count { it.contains("abgelehnt") })
        assertTrue(ports.logs.any { it.contains("keine Web-Seite offen") })

        val small = Page("klein", "Klein", listOf(Block.Image("mini", null, 120, 80)))
        asApp {
            definePages(listOf(small))
            web("mini", WebAction.Open("https://example.org/"))
        }
        assertTrue(ports.logs.last().contains("zu klein"))

        host.start("ch.test.offline", granted = emptySet())
        asApp { web("seite", WebAction.Open("https://example.org/")) }
        assertTrue(ports.logs.last().contains("Keine Berechtigung: Internet"))
        assertTrue(ports.web.pages.isEmpty())
    }

    @Test
    fun `clicks on the page reach the app at that spot of the block`() {
        val host = host(app())
        val app = host.withPage()
        // Double tap on the watch with the pointer on the page.
        host.clickAt(120, 80)
        settle()
        assertEquals(AppEvent.ImageClick("web", "seite", 120, 80), app.events.last())
        // A temple tap clicks where the pointer is.
        host.pointerAt(300, 140)
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals(AppEvent.ImageClick("web", "seite", 300, 140), app.events.last())
        // Without the pointer on the page a temple tap clicks nothing.
        host.pointerAt(-1, -1)
        host.gesture(Gesture(GestureKind.CLICK, InputSource.LEFT))
        settle()
        assertEquals(2, app.inputEvents().size)
    }

    @Test
    fun `temple swipes and the pointer pushed past the edge scroll the page`() {
        val host = host(app())
        val app = host.withPage()
        host.pointerAt(-1, -1)
        host.gesture(Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT))
        host.gesture(Gesture(GestureKind.SCROLL_UP, InputSource.RIGHT))
        settle()
        assertEquals(
            listOf(AppEvent.ImageScroll("web", "seite", 195), AppEvent.ImageScroll("web", "seite", -195)),
            app.inputEvents(),
        )
        // Pushes come in small steps; the host gathers them.
        host.pointerAt(200, 250)
        host.scrollBy(12)
        host.scrollBy(9)
        host.scrollBy(4)
        scheduler.runPending()
        scheduler.advanceBy(AppHost.PUSH_SCROLL_MS)
        assertEquals(AppEvent.ImageScroll("web", "seite", 25), app.inputEvents().last())
        assertEquals(3, app.inputEvents().size)
    }

    @Test
    fun `back goes back through the page's history first, then leaves the page`() {
        val host = host(app())
        val app = host.start()
        asApp { show("liste") }
        // The button shows the page; the app opens the address there.
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals("web", host.state.value.page)
        asApp { web("seite", WebAction.Open("https://de.m.wikipedia.org/wiki/Brille")) }
        page.state(WebState.READY, canBack = true)
        settle()

        host.back()
        settle()
        assertEquals("back", page.calls.last())
        assertEquals("web", host.state.value.page)
        assertTrue(app.events.none { it is AppEvent.Back })

        page.state(WebState.READY, canBack = false)
        settle()
        host.gesture(Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()
        assertEquals(AppEvent.Back("web"), app.events.filterIsInstance<AppEvent.Back>().single())
        assertEquals("liste", host.state.value.page)
    }

    @Test
    fun `the page rests while its app is hidden or the glasses are gone, and ends with the app`() {
        val host = host(app())
        host.updateGlasses(GlassesStatus(connected = true))
        host.withPage()
        assertTrue(page.active)
        host.openLauncher()
        settle()
        assertFalse(page.active)
        host.launch("watch:ch.test.web")
        settle()
        assertTrue(page.active)

        host.updateGlasses(GlassesStatus(connected = false))
        settle()
        assertFalse(page.active)
        host.updateGlasses(GlassesStatus(connected = true))
        settle()
        assertTrue(page.active)

        asApp { close() }
        assertTrue(page.released)
    }

    @Test
    fun `actions reach the page, and a second address joins its history`() {
        val host = host(app())
        val app = host.withPage()
        asApp {
            web("seite", WebAction.Scroll(-120))
            web("seite", WebAction.Tap(575, 259))
            web("seite", WebAction.Type("Brille", enter = true))
            web("seite", WebAction.Reader(false))
            web("seite", WebAction.Contrast(WebContrast.PLATE))
            web("seite", WebAction.Back)
            web("seite", WebAction.Forward)
            web("seite", WebAction.Reload)
            web("seite", WebAction.Open("https://lite.duckduckgo.com/lite/?q=brille"))
        }
        assertEquals(1, ports.web.pages.size)
        assertEquals(
            listOf(
                "scroll -120", "tap 575 259", "type Brille enter", "reader false", "contrast plate",
                "back", "forward", "reload", "open https://lite.duckduckgo.com/lite/?q=brille",
            ),
            page.calls,
        )
        // Outside the block, or too much at once: refused.
        asApp {
            web("seite", WebAction.Tap(576, 10))
            web("seite", WebAction.Scroll(20_000))
            web("seite", WebAction.Type("x".repeat(2_001)))
        }
        assertEquals(9, page.calls.size)
        assertEquals(3, ports.logs.count { it.contains("abgelehnt") })

        // A field with the cursor reaches the app; the app types into it.
        page.state(WebState.READY, field = WebField("Suche"))
        settle()
        assertEquals(WebField("Suche"), app.webEvents().last().field)

        asApp { web("seite", WebAction.Stop) }
        assertTrue(page.released)
        asApp { web("seite", WebAction.Reload) }
        assertTrue(ports.logs.last().contains("keine Web-Seite offen"))
    }

    @Test
    fun `an error is logged and reported, the page stays for reload`() {
        val host = host(app())
        val app = host.withPage("https://nirgends.example/")
        page.state(WebState.ERROR, message = "Adresse nicht gefunden")
        settle()
        assertEquals("Adresse nicht gefunden", app.webEvents().last().message)
        assertTrue(ports.logs.any { it.contains("Adresse nicht gefunden") })
        asApp { web("seite", WebAction.Reload) }
        assertEquals("reload", page.calls.last())
        assertFalse(page.released)
    }

    @Test
    fun `the browser package in the host with address, pointer, temple and back`() {
        val host = AppHost(scheduler, screen, FakeText(), ports, listOf({ BrowserApp() }), nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L })
        ports.answers["ch.madtreasures.browser@1.0.0"] = setOf(Permission.NETWORK)
        host.launch("watch:ch.madtreasures.browser")
        settle()
        // A temple tap on the focused "Adresse oder Suche": the watch asks, the wearer says an address.
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        ports.questions.last().third("srf.ch")
        settle()
        val page = ports.web.pages.single()
        assertEquals("https://srf.ch", page.request.url)
        assertTrue(page.request.reader)
        assertEquals(BrowserApp.WEB, host.state.value.page)
        page.state(WebState.READY, "https://www.srf.ch/", "SRF")
        settle()

        // The pointer on the page is the finger; temple swipes scroll.
        host.pointerAt(200, 100)
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        host.gesture(Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT))
        host.clickAt(30, 240)
        settle()
        assertEquals(listOf("tap 200 100", "scroll 195", "tap 30 240"), page.calls)

        // Back: first the page's own history, then the start page; the page ends.
        page.state(WebState.READY, "https://www.srf.ch/meteo", "Meteo", canBack = true)
        settle()
        host.back()
        settle()
        assertEquals("back", page.calls.last())
        page.state(WebState.READY, "https://www.srf.ch/", "SRF", canBack = false)
        settle()
        host.back()
        settle()
        assertTrue(page.released)
        assertEquals(BrowserApp.START, host.state.value.page)
    }
}

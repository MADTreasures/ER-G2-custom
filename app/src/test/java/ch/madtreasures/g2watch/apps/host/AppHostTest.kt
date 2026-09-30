package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** The app host against fake apps, a fake desktop and virtual time (03 §7). */
class AppHostTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val ports = FakePorts()
    private val text = FakeText()

    /** Extra time an app "spends" in its current event, for the 50/500 ms rule. */
    private var busyNanos = 0L

    private val created = mutableListOf<ScriptedApp>()

    private val pageA = Page(
        "a", "Seite A",
        listOf(
            Block.Heading("titel", "A"),
            Block.Button("zu_b", "Zu B", target = "b"),
            Block.Button("klick", "Klick"),
            Block.Toggle("schalter", "Schalter"),
            Block.List("liste", listOf(ListItem("x"), ListItem("y")), ListStyle.CHECKS),
            Block.Button("zurueck", "Zurück", target = Block.BACK),
        ),
    )
    private val pageB = Page("b", "Seite B", listOf(Block.Text("text_b", "B"), Block.Button("hin", "Hin")))

    /** Defines A and B and shows A on start. */
    private val twoPages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        if (event == AppEvent.Start) {
            ui.definePages(listOf(pageA, pageB))
            ui.show("a")
        }
    }

    private fun app(
        id: String = "ch.test.eins",
        name: String = "Eins",
        input: InputMode = InputMode.POINTER,
        permissions: Set<Permission> = emptySet(),
        ui: String? = null,
        handler: ScriptedApp.(AppEvent, AppContext) -> Unit = twoPages,
    ): () -> G2App = {
        ScriptedApp(AppManifest(id, name, "1.0.0", input, permissions, ui), handler).also { created += it }
    }

    private fun host(vararg apps: () -> G2App, evenHub: EvenHubRegistry = EvenHubRegistry.NONE) = AppHost(
        scheduler, screen, text, ports, apps.toList(), evenHub,
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L + busyNanos },
    )

    /** Runs what is due and lets one render interval pass. */
    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.start(entry: String = "watch:ch.test.eins"): ScriptedApp {
        launch(entry)
        settle()
        return created.last()
    }

    private fun AppHost.tap(kind: GestureKind = GestureKind.CLICK, source: InputSource = InputSource.RIGHT) {
        gesture(Gesture(kind, source))
        settle()
    }

    private fun AppHost.clickOn(page: Page, block: String, row: Int = -1) {
        val target = PageLayout.of(page, text).focusables.first { it.target == FocusTarget(block, row) }.rect
        val scroll = state.value.scroll
        clickAt(target.x + target.w / 2, target.y + target.h / 2 - scroll)
        settle()
    }

    private val AppHost.page get() = state.value.page

    @Test
    fun `an app gets start, then visible, and its page shows`() {
        val host = host(app())
        val app = host.start()
        assertEquals(listOf(AppEvent.Start, AppEvent.Visible), app.events)
        assertEquals("a", host.page)
        assertEquals("ch.test.eins", host.state.value.visible)
        assertEquals(listOf("ch.test.eins"), host.state.value.running)
        assertEquals("Eins", screen.last.title)
        assertEquals("Seite A", screen.last.subtitle)
        assertFalse(screen.last.fullScreen)
        assertEquals(576 * 260, screen.last.pixels.size)
        assertTrue(screen.last.pointer)
        assertTrue("A" in text.drawn)
    }

    @Test
    fun `without apps the launcher says so`() {
        val host = host()
        host.openLauncher()
        settle()
        assertEquals(Launcher.PAGE, host.page)
        assertTrue(Launcher.EMPTY in text.drawn)
    }

    @Test
    fun `the launcher lists the apps and marks the running ones`() {
        val host = host(app(), app(id = "ch.test.zwei", name = "Zwei"))
        host.openLauncher()
        settle()
        assertEquals(Launcher.ID, host.state.value.visible)
        assertEquals(Launcher.PAGE, host.page)
        assertEquals("Apps", screen.last.title)
        assertTrue("Eins" in text.drawn && "Zwei" in text.drawn)
        // The first button has the focus: a tap on the temple starts it.
        host.tap()
        assertEquals("ch.test.eins", host.state.value.visible)
        host.openMenu()
        settle()
        host.clickOn(menuPage(host), "host_apps")
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue("Eins · läuft" in text.drawn)
    }

    /** The app menu as the host builds it, looked up through the focus order. */
    private fun menuPage(host: AppHost): Page {
        assertEquals("host.menu", host.page)
        return Page(
            "host.menu", "Menü",
            listOf(Block.Button("host_apps", "Apps"), Block.Button("host_back", "Zurück"), Block.Button("host_close", "Schließen")),
        )
    }

    @Test
    fun `back on the first page ends the app and shows the launcher`() {
        val host = host(app())
        val app = host.start()
        host.back()
        settle()
        assertEquals(listOf(AppEvent.Start, AppEvent.Visible, AppEvent.Back("a"), AppEvent.Stop), app.events)
        assertEquals(Launcher.ID, host.state.value.visible)
        assertEquals(emptyList<String>(), host.state.value.running)
        // Back on the launcher: the desktop again.
        host.back()
        settle()
        assertNull(host.state.value.visible)
        assertEquals(1, screen.closed)
    }

    @Test
    fun `a button with a target shows the page at once and then tells the app`() {
        var shownWhenTold: String? = null
        lateinit var host: AppHost
        host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Navigate) shownWhenTold = host.currentPageOf("ch.test.eins")?.id
            },
        )
        val app = host.start()
        host.clickOn(pageA, "zu_b")
        assertEquals("b", host.page)
        assertEquals("b", shownWhenTold)
        assertEquals(AppEvent.Navigate("a", "b", "zu_b"), app.events.last())
        host.back()
        settle()
        assertEquals("a", host.page)
        assertEquals(AppEvent.Back("b"), app.events.last())
    }

    @Test
    fun `plain buttons click, back buttons only go back`() {
        val host = host(app())
        val app = host.start()
        host.clickOn(pageA, "klick")
        assertEquals(AppEvent.Click("a", "klick"), app.events.last())
        // "Zurück" is below the fold: push the page up first.
        host.scrollBy(100)
        settle()
        host.clickOn(pageA, "zurueck")
        assertEquals(listOf(AppEvent.Back("a"), AppEvent.Stop), app.events.takeLast(2))
        assertTrue(app.events.none { it is AppEvent.Click && it.block == "zurueck" })
    }

    @Test
    fun `toggles and ticks change at once, then the app hears of it`() {
        var onWhenTold: Boolean? = null
        lateinit var host: AppHost
        host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Toggle) onWhenTold = (host.currentPageOf("ch.test.eins")?.block("schalter") as Block.Toggle).on
            },
        )
        val app = host.start()
        host.clickOn(pageA, "schalter")
        assertEquals(AppEvent.Toggle("a", "schalter", true), app.events.last())
        assertEquals(true, onWhenTold)
        host.clickOn(pageA, "liste", row = 1)
        assertEquals(AppEvent.Check("a", "liste", 1, true), app.events.last())
        scheduler.runPending()
        assertEquals(listOf(ListItem("x"), ListItem("y", true)), (host.currentPageOf("ch.test.eins")!!.block("liste") as Block.List).items)
    }

    @Test
    fun `the host draws at most every 200 ms however often the app patches`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event == AppEvent.Start) ui.timer("fast", 50, repeat = true)
                if (event is AppEvent.Timer) ui.patch("b") { text("text_b", "t${scheduler.now}") }
            },
        )
        host.start()
        val before = screen.views.size
        scheduler.advanceBy(2_000)
        val drawn = screen.views.size - before
        assertTrue("drawn $drawn times", drawn <= 11)
    }

    @Test
    fun `timers rest while the app is hidden, unless it may run in the background`() {
        fun ticker(id: String, permissions: Set<Permission>) = app(id = id, name = id.takeLast(4), permissions = permissions) { event, ui ->
            twoPages(event, ui)
            if (event == AppEvent.Start) ui.timer("tick", 1_000, repeat = true)
        }
        ports.answers["ch.test.back@1.0.0"] = setOf(Permission.BACKGROUND)
        val host = host(ticker("ch.test.ruhe", emptySet()), ticker("ch.test.back", setOf(Permission.BACKGROUND)))
        val quiet = host.start("watch:ch.test.ruhe")
        host.openLauncher()
        settle()
        val busy = host.start("watch:ch.test.back")
        host.openLauncher()
        settle()
        scheduler.advanceBy(5_000)
        fun ticks(app: ScriptedApp) = app.events.count { it is AppEvent.Timer }
        assertEquals(0, ticks(quiet))
        assertEquals(5, ticks(busy))
        assertEquals(AppEvent.Hidden, quiet.events.last())
        // Visible again: the timer goes on.
        host.launch("watch:ch.test.ruhe")
        settle()
        assertEquals(AppEvent.Visible, quiet.events.last())
        scheduler.advanceBy(1_000)
        assertEquals(1, ticks(quiet))
    }

    @Test
    fun `an app with Baukasten pages that shows nothing gets its start page after 2 s`() {
        // The Baukasten example project as the app's pages.
        ports.assets["apps/ch.test.seiten/ui.json"] = File("../designs/beispiel.json").readBytes()
        val host = host(app(id = "ch.test.seiten", name = "Seiten", ui = "apps/ch.test.seiten/ui.json") { _, _ -> })
        host.start("watch:ch.test.seiten")
        assertEquals(AppHost.PLACEHOLDER, host.page)
        scheduler.advanceBy(AppHost.FIRST_PAGE_MS)
        settle()
        assertEquals("p_start", host.page)
    }

    @Test
    fun `an app without page and without Baukasten pages is ended after 10 s`() {
        val host = host(app { _, _ -> })
        val app = host.start()
        scheduler.advanceBy(AppHost.FIRST_PAGE_MS)
        settle()
        assertEquals("host.notResponding", host.page)
        assertTrue("App antwortet nicht" in text.drawn)
        scheduler.advanceBy(AppHost.GIVE_UP_MS)
        settle()
        assertEquals(AppEvent.Stop, app.events.last())
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue(text.drawn.any { it.contains("Eins antwortet nicht") })
    }

    @Test
    fun `slow apps are noted over 50 ms and ended over 500 ms`() {
        var spend = 0L
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) busyNanos += spend * 1_000_000L
            },
        )
        val app = host.start()
        spend = 60
        host.clickOn(pageA, "klick")
        assertTrue(ports.logs.any { it.contains("dauerte 60 ms") })
        assertEquals("ch.test.eins", host.state.value.visible)
        spend = 600
        host.clickOn(pageA, "klick")
        assertEquals(AppEvent.Stop, app.events.last())
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue(text.drawn.any { it.contains("reagiert zu langsam") })
    }

    @Test
    fun `an app that throws is ended, not the watch`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) error("kaputt")
            },
        )
        host.start()
        host.clickOn(pageA, "klick")
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue(ports.logs.any { it.contains("abgestürzt") && it.contains("kaputt") })
    }

    @Test
    fun `the first start asks for permissions once per version`() {
        val host = host(app(permissions = setOf(Permission.MIC, Permission.NETWORK)))
        host.launch("watch:ch.test.eins")
        settle()
        assertEquals("host.permission", host.page)
        assertTrue("Eins möchte:" in text.drawn)
        assertTrue(text.drawn.any { it.contains("Mikrofon der Brille") })
        // "Erlauben" has the focus.
        host.tap()
        val app = created.last()
        assertEquals(AppEvent.Start, app.events.first())
        assertEquals(setOf(Permission.MIC, Permission.NETWORK), ports.answers["ch.test.eins@1.0.0"])
        host.back()
        settle()
        host.launch("watch:ch.test.eins")
        settle()
        assertEquals("a", host.page)
    }

    @Test
    fun `a refused permission makes its commands fail loudly`() {
        val host = host(
            app(permissions = setOf(Permission.MIC)) { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) ui.audio(true)
            },
        )
        host.launch("watch:ch.test.eins")
        settle()
        host.tap(GestureKind.SCROLL_DOWN)
        host.tap()
        assertEquals(emptySet<Permission>(), ports.answers["ch.test.eins@1.0.0"])
        host.clickOn(pageA, "klick")
        assertTrue(ports.logs.any { it.contains("abgelehnt") && it.contains("Keine Berechtigung: Mikrofon") })
    }

    @Test
    fun `the app menu has the app's entries, then apps, back and close`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event == AppEvent.Start) ui.menu(listOf(MenuItem("sort", "Sortieren")))
            },
        )
        val app = host.start()
        host.tap(GestureKind.SHORT_THEN_LONG_PRESS)
        assertEquals("host.menu", host.page)
        assertEquals("Menü", screen.last.subtitle)
        assertTrue(listOf("Sortieren", "Apps", "Zurück", "Schließen").all { it in text.drawn })
        host.tap()
        assertEquals(AppEvent.Menu("sort"), app.events.last())
        assertEquals("a", host.page)
        // "Apps" hides the app; the launcher brings it back where it was.
        host.tap(GestureKind.SHORT_THEN_LONG_PRESS)
        host.tap(GestureKind.SCROLL_DOWN)
        host.tap()
        assertEquals(AppEvent.Hidden, app.events.last())
        assertEquals(Launcher.ID, host.state.value.visible)
        host.tap()
        assertEquals(AppEvent.Visible, app.events.last())
        assertEquals("a", host.page)
        // "Schließen" ends it.
        host.openMenu()
        settle()
        repeat(3) { host.tap(GestureKind.SCROLL_DOWN) }
        host.tap()
        assertEquals(AppEvent.Stop, app.events.last())
    }

    @Test
    fun `temple swipes move the focus, a tap clicks it, a double tap goes back`() {
        val host = host(app())
        val app = host.start()
        assertEquals(FocusTarget("zu_b"), host.state.value.focus)
        host.tap(GestureKind.SCROLL_DOWN)
        assertEquals(FocusTarget("klick"), host.state.value.focus)
        host.tap(GestureKind.SCROLL_DOWN)
        host.tap(GestureKind.SCROLL_DOWN)
        assertEquals(FocusTarget("liste", 0), host.state.value.focus)
        host.tap()
        assertEquals(AppEvent.Check("a", "liste", 0, true), app.events.last())
        host.tap(GestureKind.SCROLL_UP)
        assertEquals(FocusTarget("schalter"), host.state.value.focus)
        host.tap(GestureKind.DOUBLE_CLICK, InputSource.LEFT)
        assertEquals(AppEvent.Stop, app.events.last())
    }

    @Test
    fun `a long page scrolls along with the focus and when pushed`() {
        val long = Page("lang", "Lang", (1..12).map { Block.Button("k$it", "Knopf $it") })
        val host = host(
            app { event, ui ->
                if (event == AppEvent.Start) {
                    ui.definePages(listOf(long))
                    ui.show("lang")
                }
            },
        )
        host.start()
        assertEquals(0, host.state.value.scroll)
        repeat(8) { host.tap(GestureKind.SCROLL_DOWN) }
        assertEquals(FocusTarget("k9"), host.state.value.focus)
        val layout = PageLayout.of(long, text)
        val rect = layout.focusable(FocusTarget("k9"))!!.rect
        val scroll = host.state.value.scroll
        assertTrue("k9 at ${rect.y}..${rect.bottom}, view $scroll", rect.y >= scroll && rect.bottom <= scroll + layout.height)
        host.scrollBy(-10_000)
        settle()
        assertEquals(0, host.state.value.scroll)
        host.scrollBy(40)
        settle()
        assertEquals(40, host.state.value.scroll)
    }

    @Test
    fun `the pointer focuses what it is over, and a click there activates it`() {
        val host = host(app())
        val app = host.start()
        val klick = PageLayout.of(pageA, text).focusable(FocusTarget("klick"))!!.rect
        host.pointerAt(klick.x + 5, klick.y + 5)
        settle()
        assertEquals(FocusTarget("klick"), host.state.value.focus)
        // Over plain content the focus stays where it is.
        host.pointerAt(2, 2)
        settle()
        assertEquals(FocusTarget("klick"), host.state.value.focus)
        host.clickAt(klick.x + 5, klick.y + 5)
        settle()
        assertEquals(AppEvent.Click("a", "klick"), app.events.last())
    }

    @Test
    fun `apps in gesture mode get the raw gestures`() {
        val host = host(app(input = InputMode.GESTURES))
        val app = host.start()
        assertEquals(InputMode.GESTURES, host.inputMode.value)
        assertFalse(screen.last.pointer)
        host.tap(GestureKind.SCROLL_DOWN, InputSource.RIGHT)
        host.tap(GestureKind.CLICK, InputSource.WATCH)
        host.tap(GestureKind.DOUBLE_CLICK, InputSource.WATCH)
        host.tap(GestureKind.SWIPE_LEFT, InputSource.WATCH)
        assertEquals(
            listOf(
                AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT),
                AppEvent.Gesture(GestureKind.CLICK, InputSource.WATCH),
                AppEvent.Gesture(GestureKind.DOUBLE_CLICK, InputSource.WATCH),
                AppEvent.Gesture(GestureKind.SWIPE_LEFT, InputSource.WATCH),
            ),
            app.events.filterIsInstance<AppEvent.Gesture>(),
        )
        // Tap-then-hold is the app menu, even here; the menu is worked with the same gestures.
        host.tap(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RING)
        assertEquals("host.menu", host.page)
        host.tap(GestureKind.SWIPE_RIGHT, InputSource.WATCH)
        assertEquals("a", host.page)
        // Swiping right on the watch is back; on the first page that ends the app.
        host.tap(GestureKind.SWIPE_RIGHT, InputSource.WATCH)
        assertEquals(AppEvent.Stop, app.events.last())
        assertEquals(InputMode.POINTER, host.inputMode.value)
    }

    @Test
    fun `a temple double tap is back in gesture mode, too`() {
        val host = host(app(input = InputMode.GESTURES))
        val app = host.start()
        host.tap(GestureKind.DOUBLE_CLICK, InputSource.RIGHT)
        assertEquals(listOf(AppEvent.Back("a"), AppEvent.Stop), app.events.takeLast(2))
    }

    @Test
    fun `pointer apps can subscribe to the gestures the host does not use`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event == AppEvent.Start) ui.subscribe(Sensor.GESTURES)
            },
        )
        val app = host.start()
        host.tap(GestureKind.LONG_PRESS, InputSource.RING)
        host.tap(GestureKind.HEAD_UP, InputSource.UNKNOWN)
        host.tap(GestureKind.SCROLL_DOWN)
        assertEquals(
            listOf(AppEvent.Gesture(GestureKind.LONG_PRESS, InputSource.RING), AppEvent.Gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN)),
            app.events.filterIsInstance<AppEvent.Gesture>(),
        )
    }

    @Test
    fun `without an app on the glasses the temples work the desktop`() {
        val host = host(app())
        host.tap(GestureKind.CLICK)
        host.tap(GestureKind.DOUBLE_CLICK)
        host.tap(GestureKind.SCROLL_DOWN)
        assertEquals(1, screen.desktopClicks)
        assertEquals(1, screen.desktopBacks)
        assertTrue(screen.views.isEmpty())
    }

    @Test
    fun `refused commands go to the log instead of vanishing`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) {
                    ui.show("fehlt")
                    ui.patch("a") { text("gibtsnicht", "x") }
                    ui.timer("zu_schnell", 5, repeat = true)
                }
            },
        )
        host.start()
        host.clickOn(pageA, "klick")
        assertTrue(ports.logs.any { it.contains("„show“ abgelehnt") && it.contains("Unbekannte Seite „fehlt“") })
        assertTrue(ports.logs.any { it.contains("„patch“ abgelehnt") && it.contains("gibtsnicht") })
        assertTrue(ports.logs.any { it.contains("„timer“ abgelehnt") })
        assertEquals("a", host.page)
    }

    @Test
    fun `back always leaves the page, whatever the app does meanwhile`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Back) ui.show("b")
            },
        )
        val app = host.start()
        host.clickOn(pageA, "zu_b")
        host.back()
        settle()
        assertEquals("a", host.page)
        host.back()
        settle()
        assertEquals(AppEvent.Stop, app.events.last())
    }

    @Test
    fun `storage outlives the session, toasts come and go`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) {
                    ui.storage.set("zahl", JsonPrimitive(42))
                    ui.toast("Gespeichert", 1_000)
                }
                if (event == AppEvent.Start) ui.storage.get("zahl")?.let { ui.log("gelesen: $it") }
            },
        )
        host.start()
        host.clickOn(pageA, "klick")
        assertTrue("Gespeichert" in text.drawn)
        text.drawn.clear()
        scheduler.advanceBy(1_000)
        settle()
        assertFalse("Gespeichert" in text.drawn)
        host.back()
        settle()
        host.start()
        assertTrue(ports.logs.contains("Eins: gelesen: 42"))
    }

    @Test
    fun `fetch needs the network permission and https, and answers on the app thread`() {
        val results = mutableListOf<HttpResult>()
        ports.answers["ch.test.eins@1.0.0"] = setOf(Permission.NETWORK)
        val host = host(
            app(permissions = setOf(Permission.NETWORK)) { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) {
                    ui.fetch(HttpRequest("http://example.com")) { results += it }
                    ui.fetch(HttpRequest("https://example.com/wetter")) { results += it }
                }
            },
        )
        host.start()
        host.clickOn(pageA, "klick")
        assertEquals(listOf("https://example.com/wetter"), ports.requests.map { it.first.url })
        assertTrue(ports.logs.any { it.contains("Nur https://") })
        ports.requests.single().second(HttpResult(200, "sonnig"))
        assertTrue(results.isEmpty())
        scheduler.runPending()
        assertEquals(listOf(HttpResult(200, "sonnig")), results)
    }

    @Test
    fun `an app can close itself`() {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) ui.close()
            },
        )
        val app = host.start()
        host.clickOn(pageA, "klick")
        assertEquals(AppEvent.Stop, app.events.last())
        assertEquals(emptyList<String>(), host.state.value.running)
    }

    @Test
    fun `pictures of image blocks are decoded, scaled and drawn`() {
        val png = ByteArrayOutputStream().also { out ->
            val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 2) for (x in 0 until 4) image.setRGB(x, y, 0xFFFFFF)
            ImageIO.write(image, "png", out)
        }.toByteArray()
        val src = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val host = host(
            app { event, ui ->
                if (event == AppEvent.Start) {
                    ui.definePages(listOf(Page("p", "Bild", listOf(Block.Image("bild", src, 40, 20, bleed = true)), statusBar = false)))
                    ui.show("p")
                }
            },
        )
        host.start()
        val view = screen.last
        assertTrue(view.fullScreen)
        assertEquals(576 * 288, view.pixels.size)
        // Centred, flush with the top: white where the picture is, black around it.
        assertEquals(255, view.pixels[10 * 576 + 288].toInt() and 0xFF)
        assertEquals(0, view.pixels[10 * 576 + 10].toInt() and 0xFF)
        assertEquals(0, view.pixels[30 * 576 + 288].toInt() and 0xFF)
    }

    // --- Internal sessions and Even Hub hooks (03 §5.2) --------------------------------------------

    private class HubApp : InternalSession {
        override val manifest = AppManifest("com.example.hub", "Hub-App", "0.1.0", input = InputMode.GESTURES)
        override val startTimeoutMs = 20_000L
        override val startingText = "Startet …"
        override val ownsDoubleClick = true
        val events = mutableListOf<AppEvent>()
        val statuses = mutableListOf<GlassesStatus>()
        lateinit var host: InternalContext

        override fun onEvent(event: AppEvent, host: InternalContext) {
            this.host = host
            events += event
        }

        override fun onGlassesStatus(status: GlassesStatus, host: InternalContext) {
            statuses += status
        }

        fun ready() {
            host.definePages(listOf(Page("leinwand", "", listOf(Block.Image("bild", null, 576, 288, bleed = true)), statusBar = false)))
            host.show("leinwand")
            host.menu(listOf(MenuItem("m1", "Neu laden")))
        }
    }

    private class Hub(val app: HubApp) : EvenHubRegistry {
        override val apps = listOf(EvenHubEntry("com.example.hub", "Hub-App", "0.1.0", EvenHubLocation.WATCH))

        override fun open(id: String): InternalSession? = app.takeIf { id == "com.example.hub" }
    }

    @Test
    fun `Even Hub apps appear in the launcher and run as internal sessions`() {
        val hub = HubApp()
        val host = host(app(), evenHub = Hub(hub))
        host.openLauncher()
        settle()
        assertTrue("Auf der Uhr" in text.drawn && "Even Hub" in text.drawn)
        assertTrue("Hub-App · Uhr" in text.drawn)

        host.launch("evenhub:com.example.hub")
        settle()
        assertEquals(listOf(AppEvent.Start, AppEvent.Visible), hub.events)
        assertTrue("Startet …" in text.drawn)
        // 20 s to start instead of 2.
        scheduler.advanceBy(5_000)
        assertEquals(AppHost.PLACEHOLDER, host.page)

        hub.ready()
        val raster = GrayRaster(576, 288).also { it.fillRect(100, 50, 10, 10, 200) }
        hub.host.setRaster("bild", raster)
        settle()
        assertEquals("leinwand", host.page)
        assertEquals(200, screen.last.pixels[55 * 576 + 105].toInt() and 0xFF)
        assertFalse(screen.last.pointer)
        // A raster of the wrong size is refused.
        hub.host.setRaster("bild", GrayRaster(10, 10))
        assertTrue(ports.logs.any { it.contains("„setRaster“ abgelehnt") })

        // The double tap belongs to the app; the menu has its own entries.
        host.tap(GestureKind.DOUBLE_CLICK, InputSource.RING)
        assertEquals(AppEvent.Gesture(GestureKind.DOUBLE_CLICK, InputSource.RING), hub.events.last())
        host.tap(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RING)
        assertTrue("Neu laden" in text.drawn)
        host.tap()
        assertEquals(AppEvent.Menu("m1"), hub.events.last())

        host.updateGlasses(GlassesStatus(connected = true, battery = 81, wearing = true))
        settle()
        assertEquals(listOf(GlassesStatus(connected = true, battery = 81, wearing = true)), hub.statuses)
        assertEquals(81, hub.host.glasses.battery)
        assertEquals(true, host.glassesStatus.value.wearing)
    }

    @Test
    fun `an internal session without a page gets its own start timeout`() {
        val hub = HubApp()
        val host = host(evenHub = Hub(hub))
        host.launch("evenhub:com.example.hub")
        settle()
        scheduler.advanceBy(20_000)
        settle()
        assertEquals("host.notResponding", host.page)
        scheduler.advanceBy(AppHost.GIVE_UP_MS)
        settle()
        assertEquals(AppEvent.Stop, hub.events.last())
    }

    @Test
    fun `M6 features are accepted but noted as not wired up yet`() {
        ports.answers["ch.test.eins@1.0.0"] = setOf(Permission.COMPASS)
        val host = host(
            app(permissions = setOf(Permission.COMPASS)) { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) {
                    ui.subscribe(Sensor.COMPASS)
                    ui.subscribe(Sensor.COMPASS)
                    ui.subscribe(Sensor.IMU)
                }
            },
        )
        host.start()
        host.clickOn(pageA, "klick")
        assertEquals(1, ports.logs.count { it.contains("compass ist noch nicht angeschlossen") })
        assertTrue(ports.logs.any { it.contains("Keine Berechtigung: Bewegungssensor") })
        assertNotNull(host.state.value.visible)
    }
}

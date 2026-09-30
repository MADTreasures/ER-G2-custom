package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.BuzzNote
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
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.PageMetrics
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHostTest {
    private val scheduler = FakeScheduler()
    private val platform = FakeAppPlatform()
    private val screen = FakeScreen()
    private val text = FakeText()

    /** Nanoseconds as the host measures app calls; test apps move it forward to "take time". */
    private var nanos = 0L

    private fun host(vararg apps: G2App, internal: List<() -> InternalApp> = emptyList(), evenHub: EvenHubRegistry = NoEvenHubApps) =
        AppHost(
            platform = platform,
            renderer = PageRenderer(text),
            scheduler = scheduler,
            builtIn = apps.map { app -> { app } },
            internalApps = internal,
            evenHub = evenHub,
            clockMs = { scheduler.now },
            nanoTime = { nanos },
        ).also { it.screen = screen }

    /** An app that records its events; [react] runs after the default start. */
    private open inner class TestApp(
        id: String = "ch.test.app",
        name: String = "Test",
        input: InputMode = InputMode.POINTER,
        permissions: Set<Permission> = emptySet(),
        ui: String? = null,
        val pages: List<Page> = listOf(PAGE_A, PAGE_B),
        val showOnStart: String? = "p_a",
        val react: (AppEvent, AppContext) -> Unit = { _, _ -> },
    ) : G2App {
        val events = mutableListOf<AppEvent>()
        override val manifest = AppManifest(id, name, "1.0.0", input, permissions, ui)

        override fun onEvent(event: AppEvent, ui: AppContext) {
            events += event
            if (event == AppEvent.Start) {
                if (pages.isNotEmpty()) ui.definePages(pages)
                showOnStart?.let { ui.show(it) }
            }
            react(event, ui)
        }
    }

    private fun AppHost.state() = snapshot()

    private fun run() = scheduler.runPending()

    private fun layoutOf(page: Page, height: Int = PageMetrics.HEIGHT) = PageLayout.of(page, PageMetrics.WIDTH, height, text)

    /** Clicks the middle of [blockId] (row [row] of a list) with the pointer, as the watch would. */
    private fun AppHost.clickOn(page: Page, blockId: String, row: Int = -1) {
        val layout = layoutOf(page)
        val r = layout.rectOf(FocusTarget(blockId, row))!!
        val y = r.y + r.h / 2 - snapshot().scroll
        pointerAt(r.x + r.w / 2, y)
        click(r.x + r.w / 2, y)
        run()
    }

    @Test
    fun `start is followed by visible, and the first page shows`() {
        val app = TestApp()
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        assertEquals(listOf(AppEvent.Start, AppEvent.Visible), app.events)
        assertEquals("APP", host.state().kind)
        assertEquals("Seite A", screen.last.title)
        assertEquals(InputMode.POINTER, screen.last.input)
        assertTrue(screen.last.hasMenu)
        assertEquals(PageMetrics.HEIGHT, screen.last.pixels.height)
    }

    @Test
    fun `the launcher lists the apps and marks running ones`() {
        val a = TestApp("ch.test.a", "Alpha")
        val b = TestApp("ch.test.b", "Beta")
        val host = host(a, b)
        host.open()
        run()
        val launcher = host.state().page!!
        assertEquals("Apps", launcher.name)
        assertEquals(listOf("Alpha", "Beta"), launcher.blocks.map { (it as Block.Button).text })
        assertTrue(launcher.blocks.all { (it as Block.Button).badge == null })

        host.clickOn(launcher, launcher.blocks[1].id)
        assertEquals("ch.test.b", host.state().visibleApp)
        host.gesture(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RIGHT)
        run()
        val menu = host.state().page!!
        host.clickOn(menu, menu.blocks.first { (it as Block.Button).text == "Apps" }.id)
        val again = host.state().page!!
        assertEquals("Apps", again.name)
        assertEquals("läuft", (again.blocks[1] as Block.Button).badge)
        assertNull((again.blocks[0] as Block.Button).badge)
    }

    @Test
    fun `back goes through the history, the app hears it first, and on the first page it ends`() {
        val app = TestApp()
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "to_b")
        assertEquals(listOf("p_a", "p_b"), host.state().history)
        assertEquals(AppEvent.Navigate("p_a", "p_b", "to_b"), app.events.last())

        host.back()
        run()
        assertEquals(AppEvent.Back("p_b"), app.events.last())
        assertEquals(listOf("p_a"), host.state().history)

        host.back()
        run()
        assertEquals(listOf(AppEvent.Back("p_a"), AppEvent.Stop), app.events.takeLast(2))
        assertEquals("LAUNCHER", host.state().kind)
        assertTrue(host.state().running.isEmpty())

        // Back on the launcher leaves the apps.
        host.back()
        run()
        assertFalse(host.state().active)
        assertEquals(1, screen.closed)
    }

    @Test
    fun `a button with back as target goes back and sends only back`() {
        val app = TestApp()
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "to_b")
        host.clickOn(PAGE_B, "back_btn")
        assertEquals(AppEvent.Back("p_b"), app.events.last())
        assertFalse(app.events.any { it is AppEvent.Click && it.block == "back_btn" })
        assertEquals(listOf("p_a"), host.state().history)
    }

    @Test
    fun `toggles and checks flip on the glasses at once and are reported`() {
        val app = TestApp()
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "sound")
        assertEquals(AppEvent.Toggle("p_a", "sound", true), app.events.last())
        assertEquals(true, (host.state().page!!.block("sound") as Block.Toggle).on)

        host.clickOn(PAGE_A, "shop", row = 1)
        assertEquals(AppEvent.Check("p_a", "shop", 1, true), app.events.last())
        assertTrue((host.state().page!!.block("shop") as Block.List).items[1].done)
    }

    @Test
    fun `temple swipes move the focus, a temple tap clicks it`() {
        val app = TestApp()
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        // A page starts with the first target in view.
        assertEquals(FocusTarget("to_b"), host.state().focus)
        host.gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)
        run()
        assertEquals(FocusTarget("sound"), host.state().focus)
        host.gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)
        run()
        assertEquals(FocusTarget("shop", 0), host.state().focus)
        host.gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN)
        run()
        assertEquals(FocusTarget("sound"), host.state().focus)
        host.gesture(GestureKind.CLICK, InputSource.RIGHT)
        run()
        assertEquals(AppEvent.Toggle("p_a", "sound", true), app.events.last())
    }

    @Test
    fun `swiping through a long page scrolls it and keeps the focus in view`() {
        val long = Page("p_long", "Lang", blocks = (1..12).map { Block.Button("b$it", "Knopf $it") })
        val app = TestApp(pages = listOf(long), showOnStart = "p_long")
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        repeat(9) { host.gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN) }
        run()
        val s = host.state()
        assertEquals(FocusTarget("b10"), s.focus)
        val r = layoutOf(long).rectOf(s.focus!!)!!
        assertTrue("scrolled to ${s.scroll}", s.scroll > 0 && r.y >= s.scroll && r.bottom <= s.scroll + PageMetrics.HEIGHT)
    }

    @Test
    fun `resting the pointer at the bottom edge scrolls a long page`() {
        val long = Page("p_long", "Lang", blocks = (1..12).map { Block.Button("b$it", "Knopf $it") })
        val app = TestApp(pages = listOf(long), showOnStart = "p_long")
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.pointerAt(200, PageMetrics.HEIGHT - 5)
        run()
        assertEquals(0, host.state().scroll)
        scheduler.advanceBy(AppHost.EDGE_DWELL_MS)
        assertTrue(host.state().scroll > 0)
        // Moving away stops it.
        val first = host.state().scroll
        host.pointerAt(200, 100)
        scheduler.advanceBy(3 * AppHost.EDGE_REPEAT_MS)
        assertEquals(first, host.state().scroll)
    }

    @Test
    fun `an app taking over 500 ms is ended, over 50 ms it is logged`() {
        val slow = TestApp(react = { e, _ -> if (e is AppEvent.Click) nanos += 120_000_000 })
        val host = host(slow)
        host.launch(slow.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        assertTrue(platform.logs.any { "brauchte 120 ms" in it })
        assertEquals(listOf("ch.test.app"), host.state().running)

        val stuck = TestApp(react = { e, _ -> if (e is AppEvent.Click) nanos += 600_000_000 })
        val host2 = host(stuck)
        host2.launch(stuck.manifest.id)
        run()
        host2.clickOn(PAGE_A, "plain")
        assertTrue(host2.state().running.isEmpty())
        assertTrue(platform.logs.any { "reagiert zu langsam" in it })
        assertEquals("LAUNCHER", host2.state().kind)
    }

    @Test
    fun `a crashing app is ended with a note`() {
        val app = TestApp(react = { e, _ -> if (e is AppEvent.Click) error("kaputt") })
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        assertTrue(host.state().running.isEmpty())
        assertTrue(platform.logs.any { "IllegalStateException: kaputt" in it })
        assertTrue(platform.logs.any { "ist abgestürzt" in it })
    }

    @Test
    fun `timers rest while the app is hidden, unless it may run in the background`() {
        val app = TestApp(react = { e, ui -> if (e == AppEvent.Start) ui.timer("tick", 1_000, repeat = true) })
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        scheduler.advanceBy(2_500)
        assertEquals(2, app.events.count { it is AppEvent.Timer })
        host.leave()
        run()
        assertEquals(AppEvent.Hidden, app.events.last())
        scheduler.advanceBy(10_000)
        assertEquals(2, app.events.count { it is AppEvent.Timer })
        host.launch(app.manifest.id)
        run()
        assertEquals(AppEvent.Visible, app.events.last())
        // It continues with the time that was left (0.5 s), then every second.
        scheduler.advanceBy(600)
        assertEquals(3, app.events.count { it is AppEvent.Timer })

        val background = TestApp(
            id = "ch.test.bg",
            permissions = setOf(Permission.BACKGROUND),
            react = { e, ui -> if (e == AppEvent.Start) ui.timer("tick", 1_000, repeat = true) },
        )
        platform.permissions["ch.test.bg@1.0.0"] = setOf(Permission.BACKGROUND)
        val host2 = host(background)
        host2.launch(background.manifest.id)
        run()
        host2.leave()
        run()
        scheduler.advanceBy(3_000)
        assertEquals(3, background.events.count { it is AppEvent.Timer })
    }

    @Test
    fun `the permission question comes before the start and is remembered per version`() {
        val app = TestApp(permissions = setOf(Permission.NETWORK, Permission.BUZZER))
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        assertEquals("PROMPT", host.state().kind)
        assertTrue(app.events.isEmpty())
        val prompt = host.state().page!!
        assertEquals("Internet, Summer", (prompt.blocks[1] as Block.Text).text)
        host.clickOn(prompt, "@prompt.allow")
        assertEquals(listOf(AppEvent.Start, AppEvent.Visible), app.events)
        assertEquals(setOf(Permission.NETWORK, Permission.BUZZER), platform.permissions["ch.test.app@1.0.0"])

        host.gesture(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.LEFT)
        run()
        val menu = host.state().page!!
        host.clickOn(menu, menu.blocks.first { (it as Block.Button).text == "Schließen" }.id)
        host.launch(app.manifest.id)
        run()
        assertEquals("APP", host.state().kind)
    }

    @Test
    fun `a refused permission makes commands fail loudly, not silently`() {
        val app = TestApp(
            permissions = setOf(Permission.BUZZER),
            react = { e, ui -> if (e is AppEvent.Click) ui.buzz(listOf(BuzzNote(880, 50, 100))) },
        )
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(host.state().page!!, "@prompt.deny")
        host.clickOn(PAGE_A, "plain")
        assertTrue(platform.logs.any { "buzz abgelehnt (permission_denied)" in it })
    }

    @Test
    fun `invalid commands are logged and change nothing`() {
        val app = TestApp(
            react = { e, ui ->
                if (e is AppEvent.Click) {
                    ui.show("p_nirgends")
                    ui.patch("p_a") { text("gibt_es_nicht", "x") }
                    ui.patch("p_a") { on("title", true) }
                    ui.definePages(listOf(Page("p_c", "C", blocks = listOf(Block.Text("title", "doppelt")))))
                }
            },
        )
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        val log = platform.logs.joinToString("\n")
        assertTrue(log, "show abgelehnt (unknown_page)" in log)
        assertTrue(log, "patch abgelehnt (unknown_block)" in log)
        assertTrue(log, "patch abgelehnt (bad_value)" in log)
        assertTrue(log, "definePages abgelehnt (bad_value): Kennung „title“ ist schon vergeben" in log)
        assertEquals(listOf("p_a"), host.state().history)
    }

    @Test
    fun `the app menu shows own entries first and reports the choice`() {
        val app = TestApp(react = { e, ui -> if (e == AppEvent.Start) ui.menu(listOf(MenuItem("sort", "Sortieren"))) })
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.openMenu()
        run()
        val menu = host.state().page!!
        assertEquals("Menü · Test", menu.name)
        assertEquals(listOf("Sortieren", "Apps", "Zurück", "Schließen"), menu.blocks.map { (it as Block.Button).text })
        host.clickOn(menu, menu.blocks[0].id)
        assertEquals(AppEvent.Menu("sort"), app.events.last())
        assertEquals("APP", host.state().kind)
        // A double tap closes the menu without going back.
        host.gesture(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RING)
        host.gesture(GestureKind.DOUBLE_CLICK, InputSource.RING)
        run()
        assertEquals("APP", host.state().kind)
        assertEquals(listOf("p_a"), host.state().history)
    }

    @Test
    fun `without a page after 2 s the Baukasten start page shows, without one the app is ended`() {
        platform.assets["apps/ch.test.ui/ui.json"] = """{"format":"g2-baukasten@1","name":"UI","start":"p_2","pages":[
            {"id":"p_1","name":"Eins","blocks":[]},{"id":"p_2","name":"Zwei","blocks":[{"id":"t","type":"text","text":"Hallo"}]}]}""".toByteArray()
        val withUi = TestApp(id = "ch.test.ui", ui = "apps/ch.test.ui/ui.json", pages = emptyList(), showOnStart = null)
        val host = host(withUi)
        host.launch(withUi.manifest.id)
        run()
        assertEquals("WAIT", host.state().kind)
        scheduler.advanceBy(AppHost.START_LIMIT_MS)
        assertEquals("p_2", host.state().page!!.id)

        val silent = TestApp(id = "ch.test.silent", pages = emptyList(), showOnStart = null)
        val host2 = host(silent)
        host2.launch(silent.manifest.id)
        scheduler.advanceBy(AppHost.START_LIMIT_MS)
        assertEquals("App antwortet nicht", (host2.state().page!!.blocks[0] as Block.Heading).text)
        scheduler.advanceBy(AppHost.NOT_RESPONDING_END_MS)
        assertTrue(host2.state().running.isEmpty())
    }

    @Test
    fun `app commands become one picture at most every 200 ms`() {
        val app = TestApp(
            react = { e, ui ->
                if (e is AppEvent.Timer) repeat(10) { i -> ui.patch("p_a") { text("title", "Wert $i") } }
                if (e == AppEvent.Start) ui.timer("t", 100, repeat = true)
            },
        )
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        val before = screen.frames.size
        scheduler.advanceBy(1_000)
        val frames = screen.frames.size - before
        assertTrue("$frames pictures in one second", frames in 4..6)
        assertEquals("Wert 9", (host.state().page!!.block("title") as Block.Heading).text)
    }

    @Test
    fun `in gesture mode the app gets the gestures, back stays with the host`() {
        val app = TestApp(input = InputMode.GESTURES)
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        assertEquals(InputMode.GESTURES, screen.last.input)
        host.gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)
        host.gesture(GestureKind.DOUBLE_CLICK, InputSource.WATCH)
        host.gesture(GestureKind.LONG_PRESS, InputSource.RING)
        run()
        assertEquals(
            listOf(
                AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN),
                AppEvent.Gesture(GestureKind.DOUBLE_CLICK, InputSource.WATCH),
                AppEvent.Gesture(GestureKind.LONG_PRESS, InputSource.RING),
            ),
            app.events.drop(2),
        )
        // A temple double tap and a watch swipe to the right mean Back.
        host.clickOn(PAGE_A, "to_b") // no pointer in gesture mode: nothing happens
        assertEquals(listOf("p_a"), host.state().history)
        host.gesture(GestureKind.SWIPE_RIGHT, InputSource.WATCH)
        run()
        assertEquals(listOf(AppEvent.Back("p_a"), AppEvent.Stop), app.events.takeLast(2))
    }

    @Test
    fun `apps in pointer mode get gestures only when they subscribed`() {
        val app = TestApp(react = { e, ui -> if (e == AppEvent.Start) ui.subscribe(ch.madtreasures.g2watch.apps.Sensor.GESTURES) })
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN)
        run()
        assertEquals(AppEvent.Gesture(GestureKind.HEAD_UP, InputSource.UNKNOWN), app.events.last())
    }

    @Test
    fun `fetch needs the network permission and https`() {
        val results = mutableListOf<HttpResult>()
        val app = TestApp(
            permissions = setOf(Permission.NETWORK),
            react = { e, ui ->
                if (e is AppEvent.Click) {
                    ui.fetch(HttpRequest("https://example.org/")) { results += it }
                    ui.fetch(HttpRequest("http://example.org/")) { results += it }
                }
            },
        )
        platform.permissions["ch.test.app@1.0.0"] = setOf(Permission.NETWORK)
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        run()
        assertEquals(listOf("https://example.org/"), platform.requests.map { it.url })
        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertEquals("Nur https:// ist erlaubt", results[1].error)

        platform.permissions["ch.test.app@1.0.0"] = emptySet()
        val host2 = host(app)
        host2.launch(app.manifest.id)
        run()
        host2.clickOn(PAGE_A, "plain")
        run()
        assertEquals("Keine Berechtigung für das Internet", results[2].error)
    }

    @Test
    fun `the store keeps json values per app and refuses more than 256 KiB`() {
        val app = TestApp(
            react = { e, ui ->
                if (e is AppEvent.Click) {
                    ui.storage.set("a", kotlinx.serialization.json.JsonPrimitive("x".repeat(200 * 1024)))
                    ui.storage.set("b", kotlinx.serialization.json.JsonPrimitive("y".repeat(100 * 1024)))
                }
            },
        )
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        assertEquals(setOf("a"), platform.stores["ch.test.app"]!!.keys)
        assertTrue(platform.logs.any { "Speicher voll" in it })
    }

    @Test
    fun `images decode from data urls, and platform parts can set pixels directly`() {
        val png = java.io.ByteArrayOutputStream().also { out ->
            val img = java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 8) for (y in 0 until 4) img.setRGB(x, y, 0xFFFFFF)
            javax.imageio.ImageIO.write(img, "png", out)
        }.toByteArray()
        val src = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        val page = Page("p_img", "Bild", statusBar = false, blocks = listOf(Block.Image("pic", src, w = 80, h = 40, bleed = true)))
        val app = TestApp(pages = listOf(page), showOnStart = "p_img")
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        assertTrue(screen.last.fullscreen)
        assertEquals(PageMetrics.FULL_HEIGHT, screen.last.pixels.height)
        // The 8 × 4 white picture scaled into 80 × 40, centred in a full-width bleed block.
        val x = (PageMetrics.WIDTH - 80) / 2
        assertEquals(255, screen.last.pixels[x + 40, 20])
        assertEquals(0, screen.last.pixels[x - 2, 20])

        val part = object : InternalApp {
            override val manifest = AppManifest("ch.test.part", "Teil", "1.0.0", input = InputMode.GESTURES)
            override val doubleTapToApp = true
            val events = mutableListOf<AppEvent>()

            override fun onEvent(event: AppEvent, host: InternalContext) {
                events += event
                if (event == AppEvent.Start) {
                    host.definePages(listOf(Page("p_view", "Ansicht", statusBar = false, blocks = listOf(Block.Image("view", null, 576, 288, bleed = true)))))
                    host.show("p_view")
                    host.setRaster("view", GrayRaster(576, 288).also { it.fillRect(0, 0, 10, 10, 200) })
                }
            }
        }
        val host2 = host(internal = listOf({ part }))
        host2.launch("ch.test.part")
        run()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
        assertEquals(200, screen.last.pixels[5, 5])
        // For platform parts like EvenHub a temple double tap belongs to the app.
        host2.gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT)
        run()
        assertEquals(AppEvent.Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT), part.events.last())
    }

    @Test
    fun `watch apps cannot set pixels directly`() {
        val app = TestApp(react = { e, ui -> if (e is AppEvent.Click) (ui as InternalContext).setRaster("title", GrayRaster(1, 1)) })
        val host = host(app)
        host.launch(app.manifest.id)
        run()
        host.clickOn(PAGE_A, "plain")
        assertTrue(platform.logs.any { "setRaster ist nur für Plattform-Teile" in it })
    }

    @Test
    fun `a platform part shows its start page and gets the longer start limit`() {
        val part = object : InternalApp {
            override val manifest = AppManifest("ch.test.slow", "Langsam", "1.0.0")
            override val startTimeoutMs = 20_000L

            override fun onEvent(event: AppEvent, host: InternalContext) = Unit
        }
        val host = host(internal = listOf({ part }))
        host.launch("ch.test.slow")
        run()
        assertEquals("Startet …", (host.state().page!!.blocks[0] as Block.Text).text)
        scheduler.advanceBy(AppHost.START_LIMIT_MS)
        assertEquals("Startet …", (host.state().page!!.blocks[0] as Block.Text).text)
        scheduler.advanceBy(18_000)
        assertEquals("App antwortet nicht", (host.state().page!!.blocks[0] as Block.Heading).text)
    }

    @Test
    fun `installed EvenHub apps appear in the launcher`() {
        val registry = object : EvenHubRegistry {
            override fun installed() = listOf(EvenHubAppInfo("com.example.hub", "Hub-App", "1.2.0", EvenHubAppInfo.Location.WATCH))

            override fun create(id: String): InternalApp? = null
        }
        val host = host(TestApp(), evenHub = registry)
        host.open()
        run()
        assertEquals(listOf("Test", "Hub-App"), host.state().page!!.blocks.map { (it as Block.Button).text })
        host.launch("com.example.hub")
        run()
        assertTrue(platform.logs.none { it.contains("Hub-App") })
        assertNotNull(host.state().page)
        assertTrue(host.state().running.isEmpty())
    }

    @Test
    fun `the glasses status is kept for platform parts`() {
        val host = host(TestApp())
        host.updateGlasses(GlassesStatus(connected = true, battery = 80, charging = false, wearing = true))
        assertEquals(true, host.glasses.value.wearing)
        assertEquals(80, host.glasses.value.battery)
    }

    private companion object {
        val PAGE_A = Page(
            "p_a",
            "Seite A",
            blocks = listOf(
                Block.Heading("title", "Titel"),
                Block.Button("to_b", "Zu B", target = "p_b"),
                Block.Toggle("sound", "Ton"),
                Block.List("shop", ListStyle.CHECKS, listOf(ListItem("Milch"), ListItem("Brot"))),
                Block.Button("plain", "Tu was"),
            ),
        )
        val PAGE_B = Page("p_b", "Seite B", blocks = listOf(Block.Text("b_text", "Hier ist B"), Block.Button("back_btn", "Zurück", target = Block.BACK)))
    }
}

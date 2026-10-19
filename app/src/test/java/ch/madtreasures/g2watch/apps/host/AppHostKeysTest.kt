package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Keys
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.input.KeyPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Bluetooth keyboard and mouse wheel in the app host (03 §5.3), against fake apps and virtual time. */
class AppHostKeysTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val ports = FakePorts()
    private val text = FakeText()
    private val created = mutableListOf<ScriptedApp>()
    private var context: AppContext? = null

    private val pageA = Page(
        "a", "Seite A",
        listOf(
            Block.Heading("titel", "A"),
            Block.Button("zu_b", "Zu B", target = "b"),
            Block.Button("klick", "Klick"),
            Block.Toggle("schalter", "Schalter"),
            Block.List("liste", listOf(ListItem("x"), ListItem("y")), ListStyle.CHECKS),
        ),
    )
    private val pageB = Page("b", "Seite B", listOf(Block.Text("text_b", "B"), Block.Button("hin", "Hin")))
    private val long = Page("lang", "Lang", (1..14).map { Block.Text("t$it", "Zeile $it") })

    private val twoPages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        context = ui
        if (event == AppEvent.Start) {
            ui.definePages(listOf(pageA, pageB, long))
            ui.show("a")
        }
    }

    private fun app(input: InputMode = InputMode.POINTER, handler: ScriptedApp.(AppEvent, AppContext) -> Unit = twoPages): () -> G2App = {
        ScriptedApp(AppManifest("ch.test.eins", "Eins", "1.0.0", input), handler).also { created += it }
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

    private fun AppHost.start(): ScriptedApp {
        launch("watch:ch.test.eins")
        settle()
        return created.last()
    }

    private fun AppHost.press(key: String, text: String = "", shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false) {
        key(KeyPress(key, text, shift = shift, ctrl = ctrl, alt = alt))
        settle()
    }

    /** Types [s] as a keyboard would, one character per key. */
    private fun AppHost.type(s: String) = s.forEach { press(it.toString(), it.toString(), shift = it.isUpperCase()) }

    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    private val AppHost.page get() = state.value.page
    private val AppHost.focus get() = state.value.focus

    @Test
    fun `the arrows and Tab move the focus, Enter clicks it`() {
        val host = host(app())
        val app = host.start()
        assertEquals(FocusTarget("zu_b"), host.focus)
        host.press(Keys.ARROW_DOWN)
        assertEquals(FocusTarget("klick"), host.focus)
        host.press(Keys.TAB)
        assertEquals(FocusTarget("schalter"), host.focus)
        host.press(Keys.TAB, shift = true)
        host.press(Keys.ARROW_RIGHT)
        host.press(Keys.ARROW_LEFT)
        host.press(Keys.ARROW_UP)
        assertEquals(FocusTarget("zu_b"), host.focus)
        host.press(Keys.ARROW_DOWN)
        host.press(Keys.ENTER)
        assertEquals(AppEvent.Click("a", "klick"), app.events.last())
        host.press(Keys.ARROW_DOWN)
        host.press(Keys.ENTER)
        assertEquals(AppEvent.Toggle("a", "schalter", true), app.events.last())
    }

    @Test
    fun `Escape, Backspace and Alt+Left go back, the menu key and F10 open the app menu`() {
        val host = host(app())
        val app = host.start()
        host.press(Keys.ENTER)
        assertEquals("b", host.page)
        host.press(Keys.ESCAPE)
        assertEquals("a", host.page)
        host.press(Keys.ENTER)
        host.press(Keys.BACKSPACE)
        assertEquals("a", host.page)
        host.press(Keys.ENTER)
        host.press(Keys.ARROW_LEFT, alt = true)
        assertEquals("a", host.page)
        host.press(Keys.CONTEXT_MENU)
        assertEquals("host.menu", host.page)
        // The menu key again, or Escape, closes it.
        host.press(Keys.CONTEXT_MENU)
        assertEquals("a", host.page)
        host.press(Keys.F10)
        assertEquals("host.menu", host.page)
        host.press(Keys.ESCAPE)
        assertEquals("a", host.page)
        // Back on the first page ends the app.
        host.press(Keys.ESCAPE)
        assertEquals(AppEvent.Stop, app.events.last())
        assertTrue(app.events.none { it is AppEvent.Key })
    }

    @Test
    fun `Space and the page keys scroll a long page, the focus leaves with it`() {
        val host = host(app())
        host.start()
        asApp { show("lang") }
        val layout = PageLayout.of(long, text)
        val step = layout.height * 3 / 4
        host.press(Keys.SPACE, " ")
        assertEquals(step, host.state.value.scroll)
        host.press(Keys.PAGE_DOWN)
        assertEquals(minOf(2 * step, layout.maxScroll), host.state.value.scroll)
        host.press(Keys.SPACE, " ", shift = true)
        host.press(Keys.PAGE_UP)
        assertEquals(0, host.state.value.scroll)
    }

    @Test
    fun `the mouse wheel scrolls the page in steps`() {
        val host = host(app())
        host.start()
        asApp { show("lang") }
        host.wheel(2)
        settle()
        assertEquals(2 * AppHost.WHEEL_STEP, host.state.value.scroll)
        host.wheel(-5)
        settle()
        assertEquals(0, host.state.value.scroll)
    }

    @Test
    fun `keys reach an app only after it subscribed, and back and the menu stay the host's`() {
        val host = host(app())
        val app = host.start()
        asApp { subscribe(Sensor.KEYS) }
        assertTrue(ports.logs.none { it.contains("noch nicht angeschlossen") })
        host.press(Keys.ARROW_DOWN)
        host.press("ä", "ä")
        host.press("l", ctrl = true)
        host.press(Keys.ENTER)
        host.press(Keys.BACKSPACE)
        assertEquals(
            listOf(AppEvent.Key(Keys.ARROW_DOWN), AppEvent.Key("ä", "ä"), AppEvent.Key("l", ctrl = true), AppEvent.Key(Keys.ENTER), AppEvent.Key(Keys.BACKSPACE)),
            app.events.filterIsInstance<AppEvent.Key>(),
        )
        // The focus did not move, nothing was clicked.
        assertEquals(FocusTarget("zu_b"), host.focus)
        assertEquals("a", host.page)
        host.press(Keys.F10)
        assertEquals("host.menu", host.page)
        // Over a host page the keys work the page, not the app.
        host.press(Keys.ARROW_DOWN)
        assertEquals(5, app.events.count { it is AppEvent.Key })
        host.press(Keys.ESCAPE)
        assertEquals("a", host.page)
        asApp { unsubscribe(Sensor.KEYS) }
        host.press(Keys.ARROW_DOWN)
        assertEquals(FocusTarget("klick"), host.focus)
        assertEquals(5, app.events.count { it is AppEvent.Key })
    }

    @Test
    fun `on a page with gestures the keys of a remote become gestures`() {
        val host = host(app(input = InputMode.GESTURES))
        val app = host.start()
        host.press(Keys.SPACE, " ")
        host.press(Keys.MEDIA_PLAY_PAUSE)
        host.press(Keys.ARROW_RIGHT)
        host.press(Keys.ARROW_LEFT)
        host.press(Keys.PAGE_DOWN)
        host.press("x", "x")
        assertEquals(
            listOf(GestureKind.CLICK, GestureKind.CLICK, GestureKind.SCROLL_DOWN, GestureKind.SCROLL_UP, GestureKind.SCROLL_DOWN),
            app.events.filterIsInstance<AppEvent.Gesture>().map { it.gesture },
        )
        assertTrue(app.events.filterIsInstance<AppEvent.Gesture>().all { it.source == InputSource.WATCH })
        // A subscribing app gets the keys themselves; Escape still goes back.
        asApp { subscribe(Sensor.KEYS) }
        host.press(Keys.SPACE, " ")
        assertEquals(AppEvent.Key(" ", " "), app.events.last())
        host.press(Keys.ESCAPE)
        assertEquals(AppEvent.Stop, app.events.last())
    }

    @Test
    fun `on the bare desktop the arrows move the pointer, Enter clicks, Escape closes the window`() {
        val host = host(app())
        host.press(Keys.ARROW_RIGHT)
        host.press(Keys.ARROW_UP, shift = true)
        host.press(Keys.ENTER)
        host.press(Keys.ESCAPE)
        assertEquals(listOf(Pair(AppHost.DESKTOP_KEY_STEP, 0f), Pair(0f, -AppHost.DESKTOP_KEY_STEP_FAST)), screen.pointerMoves)
        assertEquals(1, screen.desktopClicks)
        assertEquals(1, screen.desktopBacks)
        assertTrue(screen.views.isEmpty())
        // The wheel does nothing there.
        host.wheel(3)
        settle()
        assertTrue(screen.views.isEmpty())
    }

    // --- Questions with a keyboard -----------------------------------------------------------------

    private fun asking(suggestions: List<String> = emptyList()): Pair<AppHost, ScriptedApp> {
        val host = host(
            app { event, ui ->
                twoPages(event, ui)
                if (event is AppEvent.Click) ui.askText("suche", "Wonach suchen?", suggestions)
            },
        )
        val app = host.start()
        host.press(Keys.ARROW_DOWN)
        host.press(Keys.ENTER)
        return host to app
    }

    private fun AppHost.line(): String = text.drawn.last { it.contains(AppHost.EDITOR_CURSOR) }

    @Test
    fun `with a keyboard a question shows on the glasses and is typed there`() {
        ports.hardwareKeyboard = true
        val (host, app) = asking()
        assertEquals(AppHost.EDITOR_KIND, host.page)
        assertTrue(ports.questions.isEmpty())
        assertTrue("Wonach suchen?" in text.drawn)
        // The touchpad works the pointer over the question, also above a page with gestures.
        assertEquals(InputMode.POINTER, host.inputMode.value)
        host.type("Katzn")
        assertEquals("Katzn|", host.line())
        host.press(Keys.ARROW_LEFT)
        host.type("e")
        assertEquals("Katze|n", host.line())
        host.press(Keys.END)
        host.press(Keys.BACKSPACE)
        assertEquals("Katze|", host.line())
        host.press(Keys.HOME)
        host.press(Keys.DELETE)
        host.type("k")
        assertEquals("k|atze", host.line())
        // Shortcuts type nothing.
        host.press("v", ctrl = true)
        assertEquals("k|atze", host.line())
        host.press(Keys.ENTER)
        assertEquals(AppEvent.TextInput("suche", "katze"), app.events.last())
        assertEquals("a", host.page)
        assertTrue(screen.last.title == "Eins")
    }

    @Test
    fun `Escape cancels the question on the glasses, and an empty line is no answer`() {
        ports.hardwareKeyboard = true
        val (host, app) = asking()
        host.type("weg")
        host.press(Keys.ESCAPE)
        assertEquals(AppEvent.TextInput("suche", null), app.events.last())
        assertEquals("a", host.page)
        host.press(Keys.ENTER)
        host.press("  ", "  ")
        host.press(Keys.ENTER)
        assertEquals(AppEvent.TextInput("suche", null), app.events.last())
        // Back on the temple cancels too.
        host.press(Keys.ENTER)
        host.gesture(Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()
        assertEquals(3, app.events.count { it == AppEvent.TextInput("suche", null) })
        assertEquals("a", host.page)
    }

    @Test
    fun `the arrows put the suggestions into the line, and a click on one answers with it`() {
        ports.hardwareKeyboard = true
        val (host, app) = asking(listOf("Katzen", "Hunde"))
        host.press(Keys.ARROW_DOWN)
        assertEquals("Katzen|", host.line())
        host.press(Keys.ARROW_DOWN)
        assertEquals("Hunde|", host.line())
        host.press(Keys.ARROW_DOWN)
        assertEquals("Katzen|", host.line())
        host.press(Keys.TAB, shift = true)
        assertEquals("Hunde|", host.line())
        host.type("!")
        host.press(Keys.ENTER)
        assertEquals(AppEvent.TextInput("suche", "Hunde!"), app.events.last())
        // Nothing is focused at first; a temple swipe reaches the first suggestion, a tap takes it.
        host.press(Keys.ENTER)
        assertNull(host.focus)
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals(AppHost.EDITOR_KIND, host.page)
        host.gesture(Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT))
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals(AppEvent.TextInput("suche", "Katzen"), app.events.last())
    }

    @Test
    fun `the watch's own input can take over the question`() {
        ports.hardwareKeyboard = true
        val (host, app) = asking(listOf("Katzen"))
        host.press(Keys.ARROW_DOWN)
        // Pointer on "Auf der Uhr eingeben".
        val editor = PageLayout.of(Page(AppHost.EDITOR_KIND, "", listOf(Block.Text("host_h", "Wonach suchen?"), Block.Heading("host_line", "Katzen|"), Block.Button("host_s0", "Katzen"), Block.Button(AppHost.EDITOR_WATCH, "Auf der Uhr eingeben"))), text)
        val r = editor.focusable(FocusTarget(AppHost.EDITOR_WATCH))!!.rect
        host.clickAt(r.x + 5, r.y + 5)
        settle()
        assertEquals("a", host.page)
        assertEquals("Wonach suchen?", ports.questions.single().first)
        assertEquals(listOf("Katzen"), ports.questions.single().second)
        ports.questions.single().third("Mäuse")
        settle()
        assertEquals(AppEvent.TextInput("suche", "Mäuse"), app.events.last())
    }

    @Test
    fun `a new question replaces the open one, and an app that ends takes its question along`() {
        ports.hardwareKeyboard = true
        val (host, app) = asking()
        host.type("alt")
        asApp { askText("zwei", "Noch eine") }
        assertTrue("Noch eine" in text.drawn)
        assertEquals(AppEvent.TextInput("suche", null), app.events.last())
        host.type("neu")
        host.press(Keys.ENTER)
        assertEquals(AppEvent.TextInput("zwei", "neu"), app.events.last())
        host.press(Keys.ENTER)
        asApp { close() }
        assertNull(host.state.value.visible?.takeIf { it == "ch.test.eins" })
        host.type("x")
        assertFalse(app.events.any { it == AppEvent.TextInput("suche", "x") })
    }

    @Test
    fun `apps learn whether a keyboard is there`() {
        val host = host(app())
        host.start()
        var seen: Boolean? = null
        asApp { seen = keyboard }
        assertEquals(false, seen)
        ports.hardwareKeyboard = true
        asApp { seen = keyboard }
        assertEquals(true, seen)
    }

    @Test
    fun `without a keyboard the watch asks as before`() {
        val (host, app) = asking()
        assertEquals("a", host.page)
        assertEquals(1, ports.questions.size)
        assertEquals(AppHost.TEXT_HINT, text.drawn.last { it == AppHost.TEXT_HINT })
        ports.questions.single().third(" Katzen ")
        settle()
        assertEquals(AppEvent.TextInput("suche", "Katzen"), app.events.last())
    }
}

package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.PageMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering the apps of the launcher as on a phone (03 §5): a finger or a mouse button held two seconds on an
 * app lifts it, the pointer takes it to another place while the others make room, letting go puts it down,
 * and the order stays. With the host's fakes and virtual time.
 */
class AppHostLauncherMoveTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val ports = FakePorts()
    private val text = FakeText()

    private fun app(name: String): () -> G2App = {
        ScriptedApp(AppManifest("ch.test.${name.lowercase()}", name, "1.0.0")) { event, ui ->
            if (event == AppEvent.Start) {
                ui.definePages(listOf(Page("p", name, listOf(Block.Text("t", name)))))
                ui.show("p")
            }
        }
    }

    private fun host(names: List<String> = NAMES): AppHost =
        AppHost(scheduler, screen, text, ports, names.map { app(it) }, nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L })
            .also {
                it.openLauncher()
                settle()
            }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    /** The middle of cell [i] of the grid, in the app area (the page is not scrolled). */
    private fun cell(i: Int) = Pair(
        PageMetrics.MARGIN + (i % PageMetrics.TILE_COLUMNS) * PageMetrics.TILE_W + PageMetrics.TILE_W / 2,
        PageMetrics.PAD + (i / PageMetrics.TILE_COLUMNS) * PageMetrics.TILE_H + PageMetrics.TILE_H / 2,
    )

    /** The apps of the launcher, in their order. */
    private fun AppHost.shownOrder(): List<String> = currentPageOf(Launcher.ID)!!.blocks.filterIsInstance<Block.Button>().map { it.text }

    /** The pointer goes to cell [i] and a finger (or, with [click], the mouse's button) comes down there. */
    private fun AppHost.pressOn(i: Int, click: Boolean = false) {
        val (x, y) = cell(i)
        pointerAt(x, y)
        pressAt(x, y, click)
        scheduler.runPending()
    }

    /** Holds as long as it takes to lift the app. */
    private fun hold() = scheduler.advanceBy(AppHost.HOLD_MS)

    private fun AppHost.pointTo(i: Int) {
        val (x, y) = cell(i)
        pointerAt(x, y)
        settle()
    }

    private fun AppHost.letGo() {
        release()
        settle()
    }

    private fun saved(): List<String>? = (ports.storage(Launcher.ID).get(AppHost.ORDER_KEY) as? JsonArray)?.map { (it as JsonPrimitive).content }

    @Test
    fun `held two seconds an app lifts, follows the pointer, lands where it is let go, and the order stays`() {
        val host = host()
        host.pressOn(0)
        scheduler.advanceBy(AppHost.HOLD_MS - 1)
        assertNull(host.state.value.moving)
        scheduler.advanceBy(1)
        assertEquals("watch:ch.test.alpha", host.state.value.moving)
        // The watch ticks, as a phone does when an app lifts.
        assertEquals(listOf(Vibration.TICK), ports.vibrations)

        // Over the third app it takes that place; the two before move up by one.
        host.pointTo(2)
        assertEquals(listOf("Beta", "Gamma", "Alpha", "Delta", "Epsilon"), host.shownOrder())
        assertEquals(FocusTarget("app2"), host.state.value.focus)
        // Back over the first place, then to the second row.
        host.pointTo(0)
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon"), host.shownOrder())
        host.pointTo(4)
        assertEquals(listOf("Beta", "Gamma", "Delta", "Epsilon", "Alpha"), host.shownOrder())
        host.letGo()
        assertNull(host.state.value.moving)
        assertEquals(Launcher.ID, host.state.value.visible)
        assertEquals(FocusTarget("app4"), host.state.value.focus)
        assertEquals(NAMES.drop(1).map { "watch:ch.test.${it.lowercase()}" } + "watch:ch.test.alpha", saved())

        // Another start of the watch app: the launcher keeps the order.
        val later = FakeScheduler()
        val again = AppHost(later, FakeScreen(), text, ports, NAMES.map { app(it) }, nowMs = { later.now }, nanoTime = { later.now * 1_000_000L })
        later.advanceBy(AppHost.RENDER_INTERVAL_MS)
        assertEquals(listOf("Beta", "Gamma", "Delta", "Epsilon", "Alpha"), again.shownOrder())
    }

    @Test
    fun `a place right of the last app or below it means the end, and the apps keep their kind`() {
        val host = host()
        host.pressOn(1)
        hold()
        // The empty cells right of the last app in the second row.
        val (x, y) = cell(7)
        host.pointerAt(x, y)
        settle()
        assertEquals(listOf("Alpha", "Gamma", "Delta", "Epsilon", "Beta"), host.shownOrder())
        host.letGo()
    }

    @Test
    fun `a finger that moves before two seconds, or lifts sooner, moves nothing and starts nothing`() {
        val host = host()
        host.pressOn(0)
        scheduler.advanceBy(500)
        // On to another app while the finger rests: aiming, not holding.
        host.pointTo(1)
        scheduler.advanceBy(AppHost.HOLD_MS)
        assertNull(host.state.value.moving)
        host.letGo()
        // A short touch: nothing (a click on the touchpad is a double tap).
        host.pressOn(2)
        scheduler.advanceBy(300)
        host.letGo()
        scheduler.advanceBy(AppHost.HOLD_MS)
        assertNull(host.state.value.moving)
        assertEquals(Launcher.ID, host.state.value.visible)
        assertTrue(ports.vibrations.isEmpty())
        assertEquals(NAMES, host.shownOrder())
    }

    @Test
    fun `the mouse clicks an app when its button comes up, and lifts it when held`() {
        val host = host()
        host.pressOn(1, click = true)
        // Down alone starts nothing yet: the button might be held.
        assertEquals(Launcher.ID, host.state.value.visible)
        host.letGo()
        assertEquals("ch.test.beta", host.state.value.visible)

        host.openLauncher()
        settle()
        host.pressOn(3, click = true)
        hold()
        assertEquals("watch:ch.test.delta", host.state.value.moving)
        host.pointTo(0)
        host.letGo()
        // Letting go after moving is no click.
        assertEquals(Launcher.ID, host.state.value.visible)
        assertEquals(listOf("Delta", "Alpha", "Beta", "Gamma", "Epsilon"), host.shownOrder())
    }

    @Test
    fun `back, a temple, a key or another app put a lifted app down where it is`() {
        val host = host()
        host.pressOn(0)
        hold()
        host.pointTo(1)
        host.back()
        settle()
        assertNull(host.state.value.moving)
        // Back only put it down: the launcher is still there, the order kept.
        assertEquals(Launcher.ID, host.state.value.visible)
        assertEquals(listOf("Beta", "Alpha", "Gamma", "Delta", "Epsilon"), host.shownOrder())

        host.pressOn(0)
        hold()
        host.gesture(Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT))
        settle()
        assertNull(host.state.value.moving)

        host.pressOn(0)
        hold()
        host.key(ch.madtreasures.g2watch.input.KeyPress("a", "a"))
        settle()
        assertNull(host.state.value.moving)

        host.pressOn(2)
        hold()
        host.launch("watch:ch.test.epsilon")
        settle()
        assertNull(host.state.value.moving)
        assertEquals("ch.test.epsilon", host.state.value.visible)
        // A release that comes late does nothing more.
        host.letGo()
        assertEquals("ch.test.epsilon", host.state.value.visible)
    }

    @Test
    fun `new apps come after the ordered ones, and in an app the mouse still clicks when its button goes down`() {
        ports.storage(Launcher.ID).set(AppHost.ORDER_KEY, JsonArray(listOf("watch:ch.test.gamma", "watch:ch.test.alpha").map { JsonPrimitive(it) }))
        val events = mutableListOf<AppEvent>()
        val button: () -> G2App = {
            ScriptedApp(AppManifest("ch.test.knopf", "Knopf", "1.0.0")) { event, ui ->
                events += event
                if (event == AppEvent.Start) {
                    ui.definePages(listOf(Page("p", "Knopf", listOf(Block.Button("k", "Drück mich")))))
                    ui.show("p")
                }
            }
        }
        val host = AppHost(scheduler, screen, text, ports, NAMES.map { app(it) } + button, nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L })
        settle()
        assertEquals(listOf("Gamma", "Alpha", "Beta", "Delta", "Epsilon", "Knopf"), host.shownOrder())

        host.launch("watch:ch.test.knopf")
        settle()
        host.pressAt(100, PageMetrics.PAD + 20, click = true)
        scheduler.runPending()
        assertEquals(AppEvent.Click("p", "k"), events.last())
        host.letGo()
        scheduler.advanceBy(AppHost.HOLD_MS)
        assertNull(host.state.value.moving)
        assertEquals(1, events.count { it is AppEvent.Click })
    }

    @Test
    fun `pushed past the edge a long launcher scrolls, and the lifted app goes on along`() {
        val names = (1..10).map { "App$it" }
        val host = host(names)
        host.pressOn(0)
        hold()
        host.pointTo(4)
        assertEquals(listOf("App2", "App3", "App4", "App5", "App1"), host.shownOrder().take(5))
        // The pointer pushes against the bottom edge: the third row comes up under it.
        host.scrollBy(500)
        settle()
        assertEquals(listOf("App2", "App3", "App4", "App5", "App6", "App7", "App8", "App9", "App1", "App10"), host.shownOrder())
        host.letGo()
        assertNull(host.state.value.moving)
    }

    @Test
    fun `an app moves among the apps of its kind only`() {
        val hub = object : EvenHubRegistry {
            override val apps = listOf(EvenHubEntry("hub.app", "Hub", "1.0.0", EvenHubLocation.WATCH))

            override fun open(id: String): InternalSession? = null
        }
        val host = AppHost(scheduler, screen, text, ports, NAMES.map { app(it) }, hub, nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L })
        settle()
        fun middle(block: String): Pair<Int, Int> {
            val r = PageLayout.of(host.currentPageOf(Launcher.ID)!!, text, tiles = true).focusables.first { it.target.block == block }.rect
            return Pair(r.x + r.w / 2, r.y + r.h / 2 - host.state.value.scroll)
        }
        val (ax, ay) = middle("app0")
        host.pointerAt(ax, ay)
        host.pressAt(ax, ay, click = false)
        scheduler.runPending()
        hold()
        // Over the Even Hub app, below its own group: the watch app goes to the end of its own group.
        host.scrollBy(500)
        settle()
        val (hx, hy) = middle("app5")
        host.pointerAt(hx, hy)
        settle()
        assertEquals(listOf("Beta", "Gamma", "Delta", "Epsilon", "Alpha", "Hub · Uhr"), host.shownOrder())
        host.letGo()
        // The Even Hub app has no other place to go.
        host.pointerAt(hx, hy)
        host.pressAt(hx, hy, click = false)
        scheduler.runPending()
        hold()
        assertEquals("evenhub:hub.app", host.state.value.moving)
        host.scrollBy(-500)
        settle()
        val (bx, by) = middle("app0")
        host.pointerAt(bx, by)
        settle()
        host.letGo()
        assertEquals(listOf("Beta", "Gamma", "Delta", "Epsilon", "Alpha", "Hub · Uhr"), host.shownOrder())
    }

    private companion object {
        val NAMES = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")
    }
}

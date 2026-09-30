package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakeAppPlatform
import ch.madtreasures.g2watch.apps.host.GlassesInput
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.desktop.AppId
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The app host inside the desktop: tile, header, pointer, gestures (03 §5, "Einbau in den Desktop"). */
class AppsOnDesktopTest {
    private val desktopScheduler = FakeScheduler()
    private val appScheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val desktopText = FakeText()
    private val controller = DesktopController(
        desktopText,
        desktopScheduler,
        nowMs = { desktopScheduler.now },
        now = { LocalDateTime.of(2026, 9, 30, 9, 41) },
    )
    private val platform = FakeAppPlatform().withRealAssets()

    private fun host(apps: List<() -> G2App> = AppRegistry.builtInApps) = AppHost(
        platform,
        PageRenderer(FakeText()),
        appScheduler,
        builtIn = apps,
        clockMs = { appScheduler.now },
    ).also {
        controller.connectApps(it)
        controller.attach(display)
        settle()
    }

    private fun settle() = repeat(6) {
        desktopScheduler.runPending()
        appScheduler.runPending()
    }

    private fun moveTo(x: Int, y: Int) {
        val frame = controller.frame.value
        controller.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
        desktopScheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        settle()
    }

    private fun openApps() {
        val tile = controller.layout.tiles.first { it.first == AppId.APPS }.second
        moveTo(tile.x + tile.w / 2, tile.y + tile.h / 2)
        controller.click()
        settle()
    }

    @Test
    fun `the apps tile shows the launcher with header in the window area`() {
        val host = host()
        openApps()
        assertEquals("LAUNCHER", host.snapshot().kind)
        assertTrue("Apps" in desktopText.drawn)
        // The launcher's pixels are in the app area of the desktop surface.
        val sent = display.submitsOf(DesktopController.DESKTOP).last().pixels
        val area = controller.layout.appArea
        val lit = (area.y until area.bottom).sumOf { y -> (area.x until area.right).count { x -> sent[y * DesktopLayout.SCREEN_WIDTH + x] != 0.toByte() } }
        assertTrue("launcher drawn ($lit lit pixels)", lit > 1000)
    }

    @Test
    fun `temple gestures drive the launcher and the app`() {
        val host = host()
        openApps()
        // Stoppuhr has the focus; one swipe on to Einkauf, a tap starts it.
        controller.glassesGesture(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        controller.glassesGesture(GlassesInput(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals("ch.madtreasures.einkauf", host.snapshot().visibleApp)
        assertEquals("Einkauf", host.snapshot().page?.name)
        // Double tap: back on the first page ends the app, the launcher shows again.
        controller.glassesGesture(GlassesInput(GestureKind.DOUBLE_CLICK, InputSource.LEFT))
        settle()
        assertEquals("LAUNCHER", host.snapshot().kind)
        controller.glassesGesture(GlassesInput(GestureKind.DOUBLE_CLICK, InputSource.LEFT))
        settle()
        assertFalse(host.snapshot().active)
    }

    @Test
    fun `the header arrow goes back, the title opens the app menu`() {
        val host = host()
        openApps()
        host.launch("ch.madtreasures.stoppuhr")
        settle()
        val title = controller.layout.appTitle
        moveTo(title.x + 10, title.y + title.h / 2)
        controller.click()
        settle()
        assertEquals("MENU", host.snapshot().kind)
        val back = controller.layout.appBack
        moveTo(back.x + 20, back.y + back.h / 2)
        controller.click()
        settle()
        assertEquals("APP", host.snapshot().kind)
        controller.click()
        settle()
        assertEquals("LAUNCHER", host.snapshot().kind)
    }

    @Test
    fun `gesture mode hides the pointer and switches the watch touchpad`() {
        val game = object : G2App {
            override val manifest = AppManifest("ch.test.spiel", "Spiel", "1.0.0", input = InputMode.GESTURES)
            val events = mutableListOf<AppEvent>()

            override fun onEvent(event: AppEvent, ui: AppContext) {
                events += event
                if (event == AppEvent.Start) {
                    ui.definePages(listOf(Page("p", "Spiel", blocks = listOf(Block.Text("t", "Wischen!")))))
                    ui.show("p")
                }
            }
        }
        val host = host(listOf({ game }))
        openApps()
        host.launch(game.manifest.id)
        settle()
        assertEquals(InputMode.GESTURES, controller.touchMode.value)
        assertEquals(false, display.visible[DesktopController.POINTER])
        controller.watchGesture(GestureKind.SWIPE_LEFT)
        controller.watchGesture(GestureKind.CLICK)
        settle()
        assertEquals(
            listOf(AppEvent.Gesture(GestureKind.SWIPE_LEFT, InputSource.WATCH), AppEvent.Gesture(GestureKind.CLICK, InputSource.WATCH)),
            game.events.filterIsInstance<AppEvent.Gesture>(),
        )
        // Swiping right on the watch is Back: the app ends, the pointer returns.
        controller.watchGesture(GestureKind.SWIPE_RIGHT)
        settle()
        assertEquals(AppEvent.Stop, game.events.last())
        assertEquals(InputMode.POINTER, controller.touchMode.value)
        assertEquals(true, display.visible[DesktopController.POINTER])
    }

    @Test
    fun `closing the window from the watch settings leaves the apps running`() {
        val host = host()
        openApps()
        host.launch("ch.madtreasures.stoppuhr")
        settle()
        controller.closeWindow()
        settle()
        assertFalse(host.snapshot().active)
        assertEquals(listOf("ch.madtreasures.stoppuhr"), host.snapshot().running)
        // Tiles again: a tap on the temple clicks the desktop, not the app.
        openApps()
        assertEquals("LAUNCHER", host.snapshot().kind)
    }
}

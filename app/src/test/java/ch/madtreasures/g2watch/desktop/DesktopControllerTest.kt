package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.CompositorDisplay
import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.host.AppHost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class DesktopControllerTest {
    private val scheduler = FakeScheduler()
    private val text = FakeText()
    private val display = FakeDisplay()
    private val controller = DesktopController(
        text,
        scheduler,
        nowMs = { scheduler.now },
        now = { LocalDateTime.of(2026, 9, 25, 14, 5) },
    )

    private fun tileCenter(app: AppId): Pair<Int, Int> {
        val r = controller.layout.tiles.first { it.first == app }.second
        return Pair(r.x + r.w / 2, r.y + r.h / 2)
    }

    private fun buttonCenter(app: AppId, id: ButtonId): Pair<Int, Int> {
        val r = controller.layout.buttons(app).first { it.first == id }.second
        return Pair(r.x + r.w / 2, r.y + r.h / 2)
    }

    /** Moves the pointer onto ([x], [y]) and lets the pointer throttle pass. */
    private fun moveTo(x: Int, y: Int) {
        val frame = controller.frame.value
        controller.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
    }

    private fun attached() {
        controller.attach(display)
        scheduler.runPending()
    }

    private val stage get() = controller.layout.stage

    /** What the desktop surface should hold now: the rendered desktop with the pointer drawn in. */
    private fun expectedScreen(of: DesktopController = controller): GrayRaster {
        val frame = of.frame.value
        val screen = desktopOnly(of)
        PointerSprite.drawInto(screen, frame.pointerPixels, frame.pointerX, frame.pointerY, stage)
        return screen
    }

    /** The desktop without the pointer, as last rendered. */
    private fun desktopOnly(of: DesktopController = controller): GrayRaster {
        val frame = of.frame.value
        val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
        for (row in 0 until frame.band.h) {
            System.arraycopy(frame.pixels, row * frame.band.w, raster.pixels, (frame.band.y + row) * raster.width + frame.band.x, frame.band.w)
        }
        return raster
    }

    private fun assertShowsExpected() = assertArrayEquals(expectedScreen().pixels, display.content("desktop").pixels)

    private fun isFullScreen(submit: FakeDisplay.Submit) = submit.x == 0 && submit.y == 0 && submit.w == 640 && submit.h == 480

    private fun tileOf(app: AppId) = controller.layout.tiles.first { it.first == app }.second

    /** Lit pixels of [raster] outside the stage, as "x,y" (empty when everything lies inside). */
    private fun litOutsideStage(raster: GrayRaster): List<String> {
        val out = mutableListOf<String>()
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            if (!stage.contains(x, y) && raster[x, y] != 0) out += "$x,$y"
        }
        return out
    }

    /** An empty spot between the two rows of tiles, for a pointer over nothing lit. */
    private val empty get() = Pair(stage.x + 1, tileOf(AppId.APPS).bottom + 2)

    @Test
    fun `renders the frame before any glasses are attached`() {
        scheduler.runPending()
        val frame = controller.frame.value
        assertEquals(1L, frame.version)
        assertEquals(640 * 288, frame.pixels.size)
        assertTrue(frame.pixels.any { it.toInt() != 0 })
        assertEquals(320, frame.pointerX)
        assertEquals(240, frame.pointerY)
    }

    @Test
    fun `attach configures one opaque full-screen surface and sends it with the pointer drawn in`() {
        attached()
        assertEquals(listOf(FakeDisplay.Config("desktop", 0, 0, 640, 480, 0, colorKey = false)), display.configs)
        val desktop = display.submits.single()
        assertEquals("desktop", desktop.id)
        assertTrue(isFullScreen(desktop))
        assertTrue(desktop.fingerprint.startsWith("desktop:"))
        assertShowsExpected()
        // The tip of the arrow is lit right in the middle of the screen.
        assertEquals(PointerSprite.BRIGHT, display.content("desktop")[320, 240])
        assertEquals(listOf("desktop" to 0), display.depths)
    }

    @Test
    fun `pointer moves are coalesced and sent as the small block around it`() {
        attached()
        moveTo(empty.first, empty.second)
        display.clear()
        repeat(5) {
            controller.moveBy(1f, 0f)
            scheduler.runPending()
        }
        assertTrue(display.submits.isEmpty())
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        val sent = display.submits.single()
        // Old and new place of the arrow, 5 px apart: one block, not the whole screen.
        assertEquals(Rect(empty.first, empty.second, PointerSprite.width + 5, PointerSprite.height), Rect(sent.x, sent.y, sent.w, sent.h))
        assertEquals(empty.first + 5, controller.frame.value.pointerX)
        assertShowsExpected()
    }

    @Test
    fun `over lit pixels the pointer turns negative with the same move`() {
        attached()
        // Tip on the corner of the tile; hovering lights the tile's frame there.
        val tile = tileOf(AppId.CLOCK)
        moveTo(tile.x, tile.y)
        val pointer = controller.frame.value.pointerPixels
        assertEquals(PointerSprite.DARK, pointer[0].toInt() and 0xFF)
        assertArrayEquals(PointerSprite.render(desktopOnly(), tile.x, tile.y), pointer)
        // On the glasses the dark tip is a black pixel in the bright frame.
        assertEquals(0, display.content("desktop")[tile.x, tile.y])
        assertShowsExpected()

        moveTo(empty.first, empty.second)
        assertArrayEquals(PointerSprite.normal, controller.frame.value.pointerPixels)
        assertShowsExpected()
    }

    @Test
    fun `when the desktop changes under a resting pointer it follows without waiting`() {
        attached()
        val tile = tileOf(AppId.CLOCK)
        moveTo(tile.x, tile.y)
        val before = controller.frame.value.pointerPixels
        val submits = display.submits.size
        val at = scheduler.now
        // The window opens where the tile was; what lies under the pointer changes.
        controller.click()
        scheduler.runPending()
        assertEquals(at, scheduler.now)
        assertEquals(submits + 1, display.submits.size)
        assertTrue(isFullScreen(display.submits.last()))
        assertFalse(controller.frame.value.pointerPixels.contentEquals(before))
        assertShowsExpected()
    }

    @Test
    fun `a resting pointer is not sent again`() {
        attached()
        moveTo(empty.first, empty.second)
        val count = display.submits.size
        // A status change redraws the desktop but nothing under the pointer: one frame, then nothing.
        controller.updateStatus { it.copy(glassesBattery = 50) }
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        assertEquals(count + 1, display.submits.size)
        scheduler.advanceBy(10 * DesktopController.POINTER_INTERVAL_MS)
        assertEquals(count + 1, display.submits.size)
        assertShowsExpected()
    }

    @Test
    fun `the desktop is sent again only when its pixels change`() {
        attached()
        val (x, y) = tileCenter(AppId.CLOCK)
        moveTo(x, y)
        // Hovering highlights the tile: one new full frame.
        assertEquals(2, display.submits.count { isFullScreen(it) })
        moveTo(x + 3, y + 2)
        assertEquals(2, display.submits.count { isFullScreen(it) })
        assertFalse(isFullScreen(display.submits.last()))
        assertShowsExpected()
    }

    @Test
    fun `click opens a window and back closes it`() {
        controller.startClock()
        attached()
        val (x, y) = tileCenter(AppId.CLOCK)
        moveTo(x, y)
        val hovered = display.submitsOf("desktop").last().fingerprint
        controller.click()
        scheduler.runPending()
        val window = display.submitsOf("desktop").last().fingerprint
        assertNotEquals(hovered, window)
        assertTrue("14:05" in text.drawn)
        assertTrue("Freitag, 25. September" in text.drawn)

        controller.back()
        scheduler.runPending()
        // Same pixels as before the click, so the same fingerprint.
        assertEquals(hovered, display.submitsOf("desktop").last().fingerprint)
    }

    @Test
    fun `status changes redraw only when something changed`() {
        attached()
        controller.updateStatus { it }
        scheduler.runPending()
        assertEquals(1, display.submitsOf("desktop").size)
        controller.updateStatus { it.copy(glassesBattery = 50) }
        scheduler.runPending()
        assertEquals(2, display.submitsOf("desktop").size)
        assertTrue(text.drawn.any { it.contains("Brille 50 %") })
    }

    @Test
    fun `after detach nothing is sent but the frame goes on`() {
        attached()
        controller.detach()
        scheduler.runPending()
        display.clear()
        val version = controller.frame.value.version
        val (x, y) = tileCenter(AppId.HELP)
        moveTo(x, y)
        controller.click()
        scheduler.runPending()
        assertTrue(display.submits.isEmpty())
        assertTrue(controller.frame.value.version > version)
    }

    @Test
    fun `speed stays in range, and centring brings the pointer back to the middle`() {
        controller.setSpeed(10f)
        scheduler.runPending()
        assertEquals(PointerMotion.MAX_SPEED, controller.speed.value)
        controller.setSpeed(0f)
        scheduler.runPending()
        assertEquals(PointerMotion.MIN_SPEED, controller.speed.value)
        controller.setSpeed(1.2f)
        scheduler.runPending()
        assertEquals(1.2f, controller.speed.value, 0.001f)

        moveTo(40, 120)
        controller.centerPointer()
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        assertEquals(320, controller.frame.value.pointerX)
        assertEquals(240, controller.frame.value.pointerY)
    }

    @Test
    fun `the arrow keys of a keyboard move the pointer on the desktop and hover the tiles`() {
        attached()
        val (x, y) = tileCenter(AppId.CLOCK)
        moveTo(x - 40, y)
        controller.movePointer(AppHost.DESKTOP_KEY_STEP * 5, 0f)
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        assertEquals(x, controller.frame.value.pointerX)
        controller.movePointer(0f, -10_000f)
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        // It stays on the visible band, like the finger's pointer.
        assertEquals(controller.layout.band.y, controller.frame.value.pointerY)
    }

    /** Records what the desktop hands to the app host. */
    private class Apps : AppInput {
        val calls = mutableListOf<String>()

        override fun openLauncher() {
            calls += "launcher"
        }

        override fun pointerAt(x: Int, y: Int) {
            calls += "pointer $x,$y"
        }

        override fun clickAt(x: Int, y: Int) {
            calls += "click $x,$y"
        }

        override fun scrollBy(dy: Int) {
            calls += "scroll $dy"
        }

        override fun back() {
            calls += "back"
        }

        override fun openMenu() {
            calls += "menu"
        }
    }

    private fun appView(pointer: Boolean = true, fullScreen: Boolean = false, value: Int = 90): AppView {
        val h = if (fullScreen) 288 else 260
        return AppView("YouTube", "Liste", fullScreen, ByteArray(576 * h) { value.toByte() }, 576, h, pointer)
    }

    @Test
    fun `the apps tile asks the app host for the launcher`() {
        val apps = Apps()
        controller.connectApps(apps)
        attached()
        val (x, y) = tileCenter(AppId.APPS)
        moveTo(x, y)
        controller.click()
        scheduler.runPending()
        assertEquals(listOf("launcher"), apps.calls)
    }

    @Test
    fun `an app replaces the tiles, under a header with its name`() {
        controller.startClock()
        attached()
        text.drawn.clear()
        controller.showApp(appView())
        scheduler.runPending()
        val desktop = display.content("desktop")
        // The app area as the host drew it; the band's edges stay dark.
        assertEquals(90, desktop[300, 200])
        assertEquals(0, desktop[10, 200])
        assertEquals(0, desktop[630, 200])
        assertTrue("YouTube" in text.drawn)
        assertTrue(" · Liste" in text.drawn)
        assertTrue("14:05" in text.drawn)
        assertFalse("Uhr" in text.drawn)

        controller.closeApp()
        scheduler.runPending()
        assertTrue("Uhr" in text.drawn)
    }

    @Test
    fun `over an app, clicks and the pointer go to the app host in app coordinates`() {
        val apps = Apps()
        controller.connectApps(apps)
        attached()
        controller.showApp(appView())
        scheduler.runPending()
        apps.calls.clear()
        moveTo(132, 224)
        controller.click()
        scheduler.runPending()
        assertEquals(listOf("pointer 100,100", "click 100,100"), apps.calls)

        apps.calls.clear()
        val back = controller.layout.appBack
        moveTo(back.x + back.w / 2, back.y + back.h / 2)
        controller.click()
        val title = controller.layout.appTitle
        moveTo(title.x + 20, title.y + 10)
        controller.click()
        controller.back()
        scheduler.runPending()
        assertEquals(listOf("pointer -1,-1", "back", "menu", "back"), apps.calls)
    }

    @Test
    fun `pushing the pointer past the bottom edge scrolls the app`() {
        val apps = Apps()
        controller.connectApps(apps)
        attached()
        controller.showApp(appView())
        scheduler.runPending()
        moveTo(300, 383)
        apps.calls.clear()
        controller.moveBy(0f, 30f)
        scheduler.runPending()
        assertEquals(listOf("scroll 30"), apps.calls)
    }

    @Test
    fun `in gesture mode the pointer is hidden, and shown again afterwards`() {
        attached()
        controller.showApp(appView(pointer = false, fullScreen = true, value = 0))
        scheduler.runPending()
        // Nothing but the black app: no arrow in the frame.
        assertTrue(display.content("desktop").pixels.all { it.toInt() == 0 })
        assertFalse(display.submits.last().fingerprint.contains(":p"))
        // A new connection keeps it hidden.
        val again = FakeDisplay()
        controller.attach(again)
        scheduler.runPending()
        assertTrue(again.content("desktop").pixels.all { it.toInt() == 0 })
        controller.closeApp()
        scheduler.runPending()
        assertArrayEquals(expectedScreen().pixels, again.content("desktop").pixels)
        assertFalse(again.content("desktop").pixels.contentEquals(desktopOnly().pixels))
    }

    @Test
    fun `the pointer's tip reaches every pixel of the stage and no further`() {
        controller.moveBy(-10_000f, -10_000f)
        scheduler.runPending()
        assertEquals(Pair(stage.x, stage.y), Pair(controller.frame.value.pointerX, controller.frame.value.pointerY))
        controller.moveBy(10_000f, 10_000f)
        scheduler.runPending()
        assertEquals(Pair(stage.right - 1, stage.bottom - 1), Pair(controller.frame.value.pointerX, controller.frame.value.pointerY))
        assertEquals(Rect(32, 96, 576, 288), stage)
    }

    @Test
    fun `in any order of events the glasses end up with the desktop and the arrow where it is`() {
        // Moves, clicks, apps in both input modes, status changes, new sessions and depths, with time passing in
        // between. After each pause the surface holds exactly the desktop plus the arrow (none in gesture mode):
        // the small pointer blocks leave nothing behind.
        for (seed in 0 until 40) {
            val rnd = Random(seed)
            val clock = FakeScheduler()
            val desktop = DesktopController(text, clock, nowMs = { clock.now }, now = { LocalDateTime.of(2026, 9, 25, 14, 5) })
            desktop.connectApps(Apps())
            var glasses: FakeDisplay? = FakeDisplay().also { desktop.attach(it) }
            var pointerShown = true
            repeat(150) { step ->
                when (rnd.nextInt(11)) {
                    in 0..3 -> desktop.moveBy(rnd.nextInt(-80, 80).toFloat(), rnd.nextInt(-60, 60).toFloat())
                    4 -> desktop.click()
                    5 -> desktop.back()
                    6 -> {
                        pointerShown = rnd.nextBoolean()
                        desktop.showApp(appView(pointer = pointerShown, fullScreen = rnd.nextBoolean(), value = rnd.nextInt(256)))
                    }
                    7 -> {
                        desktop.closeApp()
                        pointerShown = true
                    }
                    8 -> desktop.updateStatus { it.copy(glassesBattery = rnd.nextInt(101)) }
                    9 -> if (rnd.nextInt(3) == 0) {
                        desktop.detach()
                        glasses = null
                    } else {
                        glasses = FakeDisplay().also { desktop.attach(it) }
                    }
                    else -> desktop.setDepth(Depth.entries.random(rnd))
                }
                clock.advanceBy(rnd.nextLong(0, 40))
                val shown = glasses
                if (shown == null || rnd.nextInt(3) != 0) return@repeat
                clock.advanceBy(10 * DesktopController.POINTER_INTERVAL_MS)
                val want = if (pointerShown) expectedScreen(desktop) else desktopOnly(desktop)
                assertArrayEquals("seed $seed, step $step", want.pixels, shown.content("desktop").pixels)
                assertEquals("seed $seed, step $step", desktop.depth.value.disparity, shown.depths.last().second)
            }
        }
    }

    @Test
    fun `nothing is lit outside the stage, wherever the pointer is and whatever is open`() {
        val apps = Apps()
        controller.connectApps(apps)
        controller.startClock()
        controller.updateStatus {
            it.copy(watchBattery = 100, glassesBattery = 100, glassesCharging = true, connection = "Verbunden", firmware = "Faceclaw/35 · Basis 2.3.0.24")
        }
        attached()
        val corners = listOf(
            Pair(stage.x, stage.y), Pair(stage.right - 1, stage.y), Pair(stage.x, stage.bottom - 1), Pair(stage.right - 1, stage.bottom - 1),
        )
        fun check(what: String) {
            for ((x, y) in corners) {
                moveTo(x, y)
                assertEquals("$what, pointer at $x,$y", emptyList<String>(), litOutsideStage(display.content("desktop")))
            }
        }
        check("tiles")
        for (app in AppId.entries - AppId.APPS) {
            val (x, y) = tileCenter(app)
            moveTo(x, y)
            controller.click()
            scheduler.runPending()
            check("window ${app.title}")
            controller.back()
            scheduler.runPending()
        }
        controller.showApp(AppView("Eine App mit einem sehr langen Namen", "und einer langen Seite", false, ByteArray(576 * 260) { -1 }, 576, 260, true))
        scheduler.runPending()
        check("app with header")
        controller.showApp(appView(fullScreen = true, value = 255))
        scheduler.runPending()
        check("full-screen app")
    }

    @Test
    fun `the depth is set on every attach`() {
        val near = DesktopController(text, scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 9, 25, 14, 5) }, depth = Depth.NEAR)
        near.attach(display)
        scheduler.runPending()
        assertEquals(listOf("desktop" to 16), display.depths)
        // A new session has a new compositor at depth 0: it gets the depth again.
        val again = FakeDisplay()
        near.attach(again)
        scheduler.runPending()
        assertEquals(listOf("desktop" to 16), again.depths)
        assertEquals(Depth.NEAR, near.depth.value)
    }

    @Test
    fun `a new depth applies at once, and only when it is new`() {
        controller.setDepth(Depth.FAR)
        scheduler.runPending()
        assertEquals(Depth.FAR, controller.depth.value)
        // Not attached yet: it waits for the glasses.
        attached()
        assertEquals(listOf("desktop" to -16), display.depths)
        controller.setDepth(Depth.NEAR)
        controller.setDepth(Depth.NEAR)
        scheduler.runPending()
        assertEquals(listOf("desktop" to -16, "desktop" to 16), display.depths)
        assertEquals(Depth.NEAR, controller.depth.value)
    }

    @Test
    fun `with Faceclaw's compositor the depth reaches the glasses, with and without the pointer`() {
        val glasses = CompositorDisplay()
        controller.setDepth(Depth.NEAR)
        controller.attach(glasses)
        scheduler.runPending()
        assertEquals(16, glasses.screenDepth)
        // Pointer mode: the arrow is in the one surface, so it cannot hide the depth.
        moveTo(200, 200)
        assertEquals(16, glasses.screenDepth)
        controller.setDepth(Depth.FAR)
        scheduler.runPending()
        assertEquals(-16, glasses.screenDepth)
        controller.showApp(appView(pointer = false, fullScreen = true))
        scheduler.runPending()
        assertEquals(-16, glasses.screenDepth)
        controller.setDepth(Depth.MIDDLE)
        scheduler.runPending()
        assertEquals(0, glasses.screenDepth)
        // The frame on the glasses is the same as on the fake display: desktop plus arrow.
        controller.closeApp()
        scheduler.runPending()
        moveTo(100, 300)
        assertArrayEquals(expectedScreen().pixels, glasses.screen)
    }

    @Test
    fun `at every depth each lens shows the whole picture, shifted by half the depth`() {
        val glasses = CompositorDisplay()
        controller.startClock()
        controller.attach(glasses)
        scheduler.runPending()
        moveTo(stage.right - 1, stage.bottom - 1)
        fun level(v: Int) = minOf(15, (v + 8) shr 4)
        for (depth in Depth.entries) {
            controller.setDepth(depth)
            scheduler.runPending()
            val screen = glasses.screen
            val lit = screen.count { level(it.toInt() and 0xFF) > 0 }
            for (right in listOf(false, true)) {
                val lens = glasses.lens(right)
                val shift = if (right) -depth.disparity / 2 else depth.disparity / 2
                var wrong = 0
                for (y in 0 until 480) for (x in 0 until 640) {
                    val from = x - shift
                    val want = if (from in 0 until 640) level(screen[y * 640 + from].toInt() and 0xFF) else 0
                    if (want != (lens[y * 640 + x].toInt() and 0xFF) / 16) wrong++
                }
                val which = "$depth, ${if (right) "right" else "left"} lens"
                assertEquals(which, 0, wrong)
                // Nothing fell off the edge.
                assertEquals(which, lit, lens.count { it.toInt() != 0 })
            }
        }
    }

    @Test
    fun `the clock ticks every ten seconds`() {
        controller.startClock()
        scheduler.runPending()
        assertTrue("14:05" in text.drawn)
        val versions = controller.frame.value.version
        scheduler.advanceBy(DesktopController.CLOCK_INTERVAL_MS)
        // Same minute: nothing to redraw.
        assertEquals(versions, controller.frame.value.version)
    }
}

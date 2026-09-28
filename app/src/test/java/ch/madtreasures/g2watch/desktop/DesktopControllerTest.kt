package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

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

    /** The pointer drawn against the desktop the glasses were last sent, with its tip at ([x], [y]). */
    private fun pointerOverSentDesktop(x: Int, y: Int): ByteArray {
        val desktop = display.submitsOf("desktop").last()
        val raster = GrayRaster(desktop.w, desktop.h)
        desktop.pixels.copyInto(raster.pixels)
        return PointerSprite.render(raster, x, y)
    }

    private fun tileOf(app: AppId) = controller.layout.tiles.first { it.first == app }.second

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
    fun `attach configures both surfaces and sends both`() {
        attached()
        assertEquals(
            listOf(
                FakeDisplay.Config("desktop", 0, 0, 640, 480, 0, colorKey = false),
                FakeDisplay.Config("pointer", 320, 240, 11, 17, 100, colorKey = true),
                FakeDisplay.Config("pointer", 320, 240, 11, 17, 100, colorKey = true),
            ),
            display.configs,
        )
        val desktop = display.submitsOf("desktop").single()
        assertEquals(640, desktop.w)
        assertEquals(480, desktop.h)
        assertTrue(desktop.fingerprint.startsWith("desktop:"))
        val pointer = display.submitsOf("pointer").single()
        assertArrayEquals(PointerSprite.normal, pointer.pixels)
        assertEquals(PointerSprite.fingerprint(PointerSprite.normal), pointer.fingerprint)
    }

    @Test
    fun `pointer moves are coalesced`() {
        attached()
        // Left of the tiles: nothing to hover, nothing lit.
        moveTo(15, 250)
        display.clear()
        repeat(5) {
            controller.moveBy(1f, 0f)
            scheduler.runPending()
        }
        assertTrue(display.submitsOf("pointer").isEmpty())
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        assertEquals(1, display.submitsOf("pointer").size)
        assertEquals(20, display.configs.last { it.id == "pointer" }.x)
        assertEquals(20, controller.frame.value.pointerX)
    }

    @Test
    fun `over lit pixels the pointer turns negative with the same move`() {
        attached()
        // Tip on the corner of the tile; hovering lights the tile's frame there.
        val tile = tileOf(AppId.CLOCK)
        moveTo(tile.x, tile.y)
        val sent = display.submitsOf("pointer").last()
        assertEquals(PointerSprite.DARK, sent.pixels[0].toInt() and 0xFF)
        assertArrayEquals(pointerOverSentDesktop(tile.x, tile.y), sent.pixels)
        assertEquals(tile.x, display.configs.last { it.id == "pointer" }.x)
        assertEquals(tile.y, display.configs.last { it.id == "pointer" }.y)
        // The published frame carries the same pointer.
        assertArrayEquals(sent.pixels, controller.frame.value.pointerPixels)

        moveTo(15, 250)
        assertArrayEquals(PointerSprite.normal, display.submitsOf("pointer").last().pixels)
        assertArrayEquals(PointerSprite.normal, controller.frame.value.pointerPixels)
    }

    @Test
    fun `when the desktop changes under a resting pointer it follows without waiting`() {
        attached()
        val tile = tileOf(AppId.CLOCK)
        moveTo(tile.x, tile.y)
        val before = display.submitsOf("pointer")
        val at = scheduler.now
        // The window opens where the tile was; what lies under the pointer changes.
        controller.click()
        scheduler.runPending()
        assertEquals(at, scheduler.now)
        val after = display.submitsOf("pointer")
        assertEquals(before.size + 1, after.size)
        assertArrayEquals(pointerOverSentDesktop(tile.x, tile.y), after.last().pixels)
        assertFalse(after.last().pixels.contentEquals(before.last().pixels))
    }

    @Test
    fun `an unchanged pointer is not sent again`() {
        attached()
        moveTo(15, 250)
        val count = display.submitsOf("pointer").size
        // A status change redraws the desktop but nothing under the pointer.
        controller.updateStatus { it.copy(glassesBattery = 50) }
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        assertEquals(2, display.submitsOf("desktop").size)
        assertEquals(count, display.submitsOf("pointer").size)
    }

    @Test
    fun `the desktop is sent again only when its pixels change`() {
        attached()
        val (x, y) = tileCenter(AppId.CLOCK)
        moveTo(x, y)
        // Hovering highlights the tile: one new desktop frame.
        assertEquals(2, display.submitsOf("desktop").size)
        moveTo(x + 3, y + 2)
        assertEquals(2, display.submitsOf("desktop").size)
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
    fun `speed stays in range and the pointer window changes it`() {
        controller.setSpeed(10f)
        scheduler.runPending()
        assertEquals(PointerMotion.MAX_SPEED, controller.speed.value)
        controller.setSpeed(0f)
        scheduler.runPending()
        assertEquals(PointerMotion.MIN_SPEED, controller.speed.value)

        controller.setSpeed(1f)
        val (tx, ty) = tileCenter(AppId.POINTER)
        moveTo(tx, ty)
        controller.click()
        scheduler.runPending()
        val (fx, fy) = buttonCenter(AppId.POINTER, ButtonId.FASTER)
        moveTo(fx, fy)
        controller.click()
        scheduler.runPending()
        assertEquals(1.2f, controller.speed.value, 0.001f)

        val (cx, cy) = buttonCenter(AppId.POINTER, ButtonId.CENTER)
        moveTo(cx, cy)
        controller.click()
        scheduler.runPending()
        assertEquals(320, controller.frame.value.pointerX)
        assertEquals(240, controller.frame.value.pointerY)
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

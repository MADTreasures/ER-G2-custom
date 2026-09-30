package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO

/**
 * Writes PNGs of what the glasses would show, with the app's own renderer and Android's fonts:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*RenderSnapshotTest*' -PsnapshotDir=$PWD/docs/bilder
 *
 * Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class RenderSnapshotTest {

    @Test
    fun render() {
        val dir = System.getProperty("snapshotDir")
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        val out = File(dir!!).apply { mkdirs() }

        val scheduler = FakeScheduler()
        val display = FakeDisplay()
        val controller = DesktopController(
            AndroidTextPainter(AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets)),
            scheduler,
            nowMs = { scheduler.now },
            now = { LocalDateTime.of(2026, 9, 25, 14, 5) },
        )
        controller.startClock()
        controller.updateStatus {
            it.copy(watchBattery = 76, glassesBattery = 81, connection = "Verbunden", firmware = "Faceclaw/35 · Basis 2.3.0.24")
        }
        controller.attach(display)
        scheduler.runPending()

        fun moveTo(x: Int, y: Int) {
            val frame = controller.frame.value
            controller.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
            scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        }

        fun tile(app: AppId) = controller.layout.tiles.first { it.first == app }.second

        fun save(name: String) {
            val frame = controller.frame.value
            val pointer = display.submitsOf("pointer").last().pixels
            ImageIO.write(
                glassesImage(display.submitsOf("desktop").last().pixels, frame.pointerX, frame.pointerY, pointer),
                "png",
                File(out, "$name.png"),
            )
        }

        moveTo(tile(AppId.CLOCK).x + 60, tile(AppId.CLOCK).y + 40)
        save("desktop-start")

        fun button(app: AppId, id: ButtonId) = controller.layout.buttons(app).first { it.first == id }.second

        moveTo(tile(AppId.COUNTER).x + 60, tile(AppId.COUNTER).y + 40)
        controller.click()
        scheduler.runPending()
        val plus = button(AppId.COUNTER, ButtonId.PLUS)
        moveTo(plus.x + plus.w / 2, plus.y + plus.h / 2)
        repeat(3) { controller.click() }
        scheduler.runPending()
        save("desktop-zaehler")
        controller.back()
        scheduler.runPending()

        // The other windows with the pointer on their close box.
        val close = controller.layout.closeButton
        val names = mapOf(AppId.CLOCK to "uhr", AppId.NOTE to "notiz", AppId.INFO to "info", AppId.HELP to "hilfe")
        for ((app, name) in names) {
            moveTo(tile(app).x + 60, tile(app).y + 40)
            controller.click()
            scheduler.runPending()
            moveTo(close.x + close.w / 2, close.y + close.h / 2)
            save("desktop-$name")
            controller.back()
            scheduler.runPending()
        }

        // The pointer up close: over dark pixels, over the lit digits of the clock, half over an edge.
        moveTo(15, 250)
        val overDark = zoom(display, controller.frame.value)
        moveTo(tile(AppId.CLOCK).x + 60, tile(AppId.CLOCK).y + 40)
        controller.click()
        scheduler.runPending()
        val desktop = raster(display.submitsOf("desktop").last().pixels)
        val digits = Rect(200, 190, 240, 90)
        val mostlyLit = positions(digits).maxBy { litShare(desktop, it) }
        moveTo(mostlyLit.first, mostlyLit.second)
        val overLit = zoom(display, controller.frame.value)
        val halfLit = positions(digits).minBy { kotlin.math.abs(litShare(desktop, it) - 0.5f) }
        moveTo(halfLit.first, halfLit.second)
        val overEdge = zoom(display, controller.frame.value)
        ImageIO.write(sideBySide(listOf(overDark, overLit, overEdge)), "png", File(out, "zeiger-lupe.png"))
    }

    private fun raster(pixels: ByteArray) =
        GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT).also { pixels.copyInto(it.pixels) }

    private fun positions(area: Rect) = sequence {
        for (y in area.y until area.bottom step 2) for (x in area.x until area.right step 2) yield(Pair(x, y))
    }

    /** Share of the pointer's pixels that lie over lit desktop pixels. */
    private fun litShare(desktop: GrayRaster, at: Pair<Int, Int>): Float {
        val over = PointerSprite.render(desktop, at.first, at.second)
        var shape = 0
        var negative = 0
        for (i in over.indices) {
            if (PointerSprite.normal[i] == 0.toByte()) continue
            shape++
            if (over[i] != PointerSprite.normal[i]) negative++
        }
        return negative.toFloat() / shape
    }

    /** 8× enlarged 40 × 30 cut around the pointer, from what the glasses were sent. */
    private fun zoom(display: FakeDisplay, frame: DesktopFrame): BufferedImage {
        val full = glassesImage(
            display.submitsOf("desktop").last().pixels, frame.pointerX, frame.pointerY, display.submitsOf("pointer").last().pixels,
        )
        val scale = 8
        val cut = BufferedImage(40 * scale, 30 * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 30) for (x in 0 until 40) {
            val sx = (frame.pointerX - 12 + x).coerceIn(0, full.width - 1)
            val sy = (frame.pointerY - 8 + y).coerceIn(0, full.height - 1)
            val rgb = full.getRGB(sx, sy)
            for (dy in 0 until scale) for (dx in 0 until scale) cut.setRGB(x * scale + dx, y * scale + dy, rgb)
        }
        return cut
    }

    private fun sideBySide(images: List<BufferedImage>): BufferedImage {
        val gap = 24
        val out = BufferedImage(images.sumOf { it.width } + gap * (images.size + 1), images.maxOf { it.height } + 2 * gap, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = java.awt.Color(40, 40, 44)
        g.fillRect(0, 0, out.width, out.height)
        var x = gap
        for (image in images) {
            g.drawImage(image, x, gap, null)
            x += image.width + gap
        }
        g.dispose()
        return out
    }

    /** The composite as the lens shows it: 16 shades of green, the pointer as sent on top with its color key. */
    private fun glassesImage(desktop: ByteArray, pointerX: Int, pointerY: Int, pointer: ByteArray): BufferedImage {
        val w = DesktopLayout.SCREEN_WIDTH
        val h = DesktopLayout.SCREEN_HEIGHT
        fun green(v: Int): Int {
            val level = (v shr 4) * 17
            return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, green(desktop[y * w + x].toInt() and 0xFF))
        for (y in 0 until PointerSprite.height) for (x in 0 until PointerSprite.width) {
            val v = pointer[y * PointerSprite.width + x].toInt() and 0xFF
            val px = pointerX + x
            val py = pointerY + y
            if (v != 0 && px < w && py < h) image.setRGB(px, py, if (v == 1) 0 else green(v))
        }
        return image
    }
}

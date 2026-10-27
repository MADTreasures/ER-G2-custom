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
 * Writes the pointer up close as the glasses show it (zeiger-lupe.png), with the app's own renderer and
 * Android's fonts. The other pictures of the glasses, the launcher at home among them, come from the
 * app host's snapshot tests.
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
        val painter = AndroidTextPainter(AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets))
        val controller = DesktopController(painter, scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 9, 25, 14, 5) })
        controller.startClock()
        controller.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81) }
        controller.attach(display)
        scheduler.runPending()

        fun moveTo(x: Int, y: Int) {
            val frame = controller.frame.value
            controller.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
            scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
        }

        // An app showing large lit digits; the pointer over dark pixels, over the digits, half over an edge.
        val area = controller.layout.appArea(fullScreen = false)
        val clock = GrayRaster(area.w, area.h)
        val size = 72
        val width = painter.measure("14:05", size, true)
        val digits = Rect((area.w - width) / 2, 60, width, painter.lineHeight(size))
        painter.draw(clock, "14:05", digits.x, digits.y, size, 255, bold = true)
        controller.showApp(AppView("Uhr", null, false, clock.pixels, area.w, area.h, pointer = true))
        scheduler.runPending()

        moveTo(area.x + 8, area.bottom - 30)
        val overDark = zoom(display, controller.frame.value)
        val desktop = raster(controller.frame.value)
        val onScreen = Rect(area.x + digits.x, area.y + digits.y, digits.w, digits.h)
        val mostlyLit = positions(onScreen).maxBy { litShare(desktop, it) }
        moveTo(mostlyLit.first, mostlyLit.second)
        val overLit = zoom(display, controller.frame.value)
        val halfLit = positions(onScreen).minBy { kotlin.math.abs(litShare(desktop, it) - 0.5f) }
        moveTo(halfLit.first, halfLit.second)
        val overEdge = zoom(display, controller.frame.value)
        ImageIO.write(sideBySide(listOf(overDark, overLit, overEdge)), "png", File(out, "zeiger-lupe.png"))
    }

    /** The desktop without the pointer, as last rendered. */
    private fun raster(frame: DesktopFrame) = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT).also {
        for (row in 0 until frame.band.h) {
            System.arraycopy(frame.pixels, row * frame.band.w, it.pixels, (frame.band.y + row) * it.width + frame.band.x, frame.band.w)
        }
    }

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
        val full = glassesImage(display.content("desktop").pixels)
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

    /** The desktop surface as the lens shows it, pointer included: 16 shades of green. */
    private fun glassesImage(desktop: ByteArray): BufferedImage {
        val w = DesktopLayout.SCREEN_WIDTH
        val h = DesktopLayout.SCREEN_HEIGHT
        fun green(v: Int): Int {
            val level = (v shr 4) * 17
            return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, green(desktop[y * w + x].toInt() and 0xFF))
        return image
    }
}

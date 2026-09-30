package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.builtin.shopping.ShoppingListApp
import ch.madtreasures.g2watch.apps.builtin.stopwatch.StopwatchApp
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakeAppPlatform
import ch.madtreasures.g2watch.apps.host.GlassesInput
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.render.PageRenderer
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.AppId
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.desktop.PointerSprite
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Pictures of the app host on the glasses (03 §7), through the real desktop and Android's fonts:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*AppsSnapshotTest*' -PsnapshotDir=$PWD/docs/bilder
 *
 * Each picture is the visible band (640 × 288) as the lens shows it. Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class AppsSnapshotTest {

    private var watchMs = 0L

    /** Every block type of 02 §4.2 on one page, focus on the toggle. */
    private inner class BlocksApp : G2App {
        override val manifest = AppManifest("ch.madtreasures.bausteine", "Bausteine", "1.0.0")

        override fun onEvent(event: AppEvent, ui: AppContext) {
            if (event != AppEvent.Start) return
            ui.definePages(
                listOf(
                    Page(
                        "p_bausteine", "Alle Bausteine",
                        blocks = listOf(
                            Block.Heading("h", "Überschrift"),
                            Block.Text("t", "Text bricht um, wenn er länger ist als eine Zeile der Brille."),
                            Block.Button("b", "Knopf mit Ziel", target = "p_ziel"),
                            Block.Toggle("s", "Schalter", on = true),
                            Block.Value("v", "Wert", "80 %"),
                            Block.Progress("p", "Fortschritt", 60),
                            Block.Divider("d"),
                            Block.List("l1", ListStyle.BULLETS, listOf(ListItem("Punkt"), ListItem("Noch ein Punkt"))),
                            Block.List("l2", ListStyle.NUMBERS, listOf(ListItem("Erstens"), ListItem("Zweitens"))),
                            Block.List("l3", ListStyle.CHECKS, listOf(ListItem("Erledigt", true), ListItem("Offen"))),
                            Block.Image("i", "data:image/png;base64," + png(landscape(160, 60)), w = 160, h = 60),
                            Block.Button("z", "Zurück", target = Block.BACK),
                        ),
                    ),
                    Page("p_ziel", "Ziel", blocks = listOf(Block.Text("zt", "Ziel"))),
                ),
            )
            ui.show("p_bausteine")
        }
    }

    /** A page without header whose picture (too large for a data URL) comes from the app package. */
    private inner class PictureApp : G2App {
        override val manifest = AppManifest("ch.madtreasures.bild", "Bild", "1.0.0", input = InputMode.GESTURES)

        override fun onEvent(event: AppEvent, ui: AppContext) {
            if (event != AppEvent.Start) return
            ui.definePages(listOf(Page("p_bild", "Bild", statusBar = false, blocks = listOf(Block.Image("bild", "asset:landschaft.png", 576, 288, bleed = true)))))
            ui.show("p_bild")
        }
    }

    private inner class AskingApp : G2App {
        override val manifest = AppManifest("ch.madtreasures.wetter", "Wetter", "1.0.0", permissions = setOf(Permission.NETWORK, Permission.LOCATION))

        override fun onEvent(event: AppEvent, ui: AppContext) = Unit
    }

    @Test
    fun render() {
        val dir = System.getProperty("snapshotDir")
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        val out = File(dir!!).apply { mkdirs() }

        val desktopScheduler = FakeScheduler()
        val appScheduler = FakeScheduler()
        val display = FakeDisplay()
        val controller = DesktopController(
            AndroidTextPainter(),
            desktopScheduler,
            nowMs = { desktopScheduler.now },
            now = { LocalDateTime.of(2026, 9, 30, 14, 5) },
        )
        controller.startClock()
        controller.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81) }
        val platform = FakeAppPlatform().withRealAssets()
        platform.assets["apps/ch.madtreasures.bild/landschaft.png"] = Base64.getDecoder().decode(png(landscape(576, 288)))
        val host = AppHost(
            platform,
            PageRenderer(AndroidTextPainter()),
            appScheduler,
            builtIn = listOf({ StopwatchApp { watchMs } }, { ShoppingListApp() }, { BlocksApp() }, { PictureApp() }, { AskingApp() }),
            clockMs = { appScheduler.now },
        )
        controller.connectApps(host)
        controller.attach(display)

        fun settle() = repeat(6) {
            desktopScheduler.runPending()
            appScheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
            desktopScheduler.runPending()
        }

        fun moveTo(x: Int, y: Int) {
            val frame = controller.frame.value
            controller.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
            desktopScheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
            settle()
        }

        fun save(name: String) {
            settle()
            val frame = controller.frame.value
            val pointer = if (display.visible[DesktopController.POINTER] == false) null else display.submitsOf(DesktopController.POINTER).last().pixels
            ImageIO.write(band(display.submitsOf(DesktopController.DESKTOP).last().pixels, frame.pointerX, frame.pointerY, pointer), "png", File(out, "$name.png"))
        }

        val area = controller.layout.appArea
        fun pointAt(page: Page, blockId: String, row: Int = -1) {
            val layout = PageRenderer(AndroidTextPainter()).layout(page, area.w, area.h)
            val r = layout.rectOf(FocusTarget(blockId, row))!!
            moveTo(area.x + r.x + r.w * 3 / 4, area.y + r.y + r.h / 2 - host.snapshot().scroll)
        }

        // The desktop with the new tile, then the launcher.
        val tile = controller.layout.tiles.first { it.first == AppId.APPS }.second
        moveTo(tile.x + 70, tile.y + 45)
        save("apps-kachel")
        controller.click()
        settle()
        pointAt(host.snapshot().page!!, "@launch:ch.madtreasures.stoppuhr")
        save("apps-starter")

        // Stopwatch, stopped at 1:05.4 after running.
        controller.click()
        settle()
        val stopwatch = host.snapshot().page!!
        pointAt(stopwatch, StopwatchApp.START_STOP)
        controller.click()
        settle()
        watchMs = 65_400
        controller.click()
        settle()
        save("apps-stoppuhr")

        // Shopping list from the Baukasten asset: two rows ticked with the temple, one focused.
        controller.glassesGesture(GlassesInput(GestureKind.DOUBLE_CLICK, InputSource.LEFT))
        settle()
        host.launch(ShoppingListApp.ID)
        settle()
        val list = host.snapshot().page!!
        pointAt(list, ShoppingListApp.LIST, 1)
        controller.click()
        settle()
        controller.glassesGesture(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        controller.glassesGesture(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        controller.glassesGesture(GlassesInput(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        save("apps-einkauf")
        controller.glassesGesture(GlassesInput(GestureKind.SHORT_THEN_LONG_PRESS, InputSource.RIGHT))
        settle()
        save("apps-menue")
        controller.glassesGesture(GlassesInput(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()

        // All blocks, focus on the switch; then scrolled to the end.
        host.launch("ch.madtreasures.bausteine")
        settle()
        val blocks = host.snapshot().page!!
        pointAt(blocks, "s")
        save("apps-bausteine")
        repeat(6) { controller.glassesGesture(GlassesInput(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN)) }
        settle()
        save("apps-bausteine-unten")

        // The permission question, then a full-screen picture without header or pointer.
        host.launch("ch.madtreasures.wetter")
        settle()
        pointAt(host.snapshot().page!!, "@prompt.allow")
        save("apps-berechtigung")
        host.launch("ch.madtreasures.bild")
        settle()
        save("apps-bild-randlos")
    }

    /** The visible band (y 96–383) as the lens shows it: 16 shades of green, the pointer on top. */
    private fun band(desktop: ByteArray, pointerX: Int, pointerY: Int, pointer: ByteArray?): BufferedImage {
        val w = DesktopLayout.SCREEN_WIDTH
        val top = (DesktopLayout.SCREEN_HEIGHT - DesktopLayout.BAND_HEIGHT) / 2
        val image = BufferedImage(w, DesktopLayout.BAND_HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until DesktopLayout.BAND_HEIGHT) for (x in 0 until w) image.setRGB(x, y, green(desktop[(top + y) * w + x].toInt() and 0xFF))
        if (pointer != null) {
            for (y in 0 until PointerSprite.height) for (x in 0 until PointerSprite.width) {
                val v = pointer[y * PointerSprite.width + x].toInt() and 0xFF
                val px = pointerX + x
                val py = pointerY + y - top
                if (v != 0 && px < w && py in 0 until DesktopLayout.BAND_HEIGHT) image.setRGB(px, py, if (v == 1) 0 else green(v))
            }
        }
        return image
    }

    /** Gray to lens green, rounded to 16 levels like the glasses (BmpUtil: min(15, (v + 8) >> 4)). */
    private fun green(v: Int): Int {
        val level = minOf(15, (v + 8) shr 4) * 17
        return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
    }

    private fun png(image: BufferedImage): String =
        Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())

    /** A small drawn landscape in gray: sky, sun, two mountain ranges, a lake. */
    private fun landscape(w: Int, h: Int): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(10, 20, 40), 0f, h * 0.7f, Color(120, 150, 190))
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 230, 160)
        g.fillOval((w * 0.68).toInt(), (h * 0.12).toInt(), h / 4, h / 4)
        g.color = Color(70, 80, 95)
        g.fillPolygon(intArrayOf(0, w / 5, w * 2 / 5, w * 3 / 5, w * 4 / 5, w, w, 0), intArrayOf(h * 3 / 5, h * 3 / 10, h / 2, h / 4, h / 2, h * 2 / 5, h, h), 8)
        g.color = Color(30, 35, 45)
        g.fillPolygon(intArrayOf(0, w / 4, w / 2, w * 3 / 4, w, w, 0), intArrayOf(h * 4 / 5, h * 11 / 20, h * 3 / 4, h * 3 / 5, h * 4 / 5, h, h), 7)
        g.color = Color(160, 180, 200)
        g.stroke = BasicStroke(maxOf(1f, h / 90f))
        for (i in 0 until 4) g.drawLine(w / 6 + i * 7, h * 9 / 10 + i * 2, w / 3 + i * 9, h * 9 / 10 + i * 2)
        g.dispose()
        return image
    }
}

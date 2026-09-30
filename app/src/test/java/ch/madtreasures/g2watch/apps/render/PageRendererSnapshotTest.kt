package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.builtin.shopping.ShoppingListApp
import ch.madtreasures.g2watch.apps.builtin.stopwatch.StopwatchApp
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.host.ScriptedApp
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.desktop.PointerSprite
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pictures of apps on the glasses, drawn by the real app host, page renderer and desktop with
 * Android's fonts, composited like the glasses do it (desktop surface, pointer on top):
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*PageRendererSnapshotTest*' -PsnapshotDir=$PWD/docs/bilder
 *
 * Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class PageRendererSnapshotTest {
    private val dir = System.getProperty("snapshotDir")
    private val scheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val ports = FakePorts()
    private lateinit var desktop: DesktopController
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        desktop = DesktopController(AndroidTextPainter(), scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 9, 25, 14, 5) })
        desktop.startClock()
        desktop.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81, connection = "Verbunden") }
        desktop.attach(display)
        scheduler.runPending()
        // Off the app area, so it focuses nothing until a picture puts it somewhere.
        pointerTo(620, 380)
    }

    private fun host(vararg apps: () -> G2App) = AppHost(
        scheduler, desktop, AndroidTextPainter(), ports, apps.toList(), nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L },
    ).also { desktop.connectApps(it) }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
    }

    private fun AppHost.gesture(kind: GestureKind) {
        gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    /** Puts the pointer on ([x], [y]) of the 640 × 480 screen. */
    private fun pointerTo(x: Int, y: Int) {
        val frame = desktop.frame.value
        desktop.moveBy((x - frame.pointerX).toFloat(), (y - frame.pointerY).toFloat())
        settle()
    }

    /** Puts the pointer on block [id] of [page], shown below the header. */
    private fun pointerOn(page: Page, id: String, row: Int = -1, dx: Int = 40) {
        val rect = PageLayout.of(page, AndroidTextPainter()).focusables.first { it.target == FocusTarget(id, row) }.rect
        val area = desktop.layout.appArea(!page.statusBar)
        pointerTo(area.x + rect.x + dx, area.y + rect.y + rect.h / 2)
    }

    private fun save(name: String) {
        settle()
        val frame = desktop.frame.value
        val pointer = if (display.visibility["pointer"] == false) null else display.submitsOf("pointer").last().pixels
        ImageIO.write(glassesImage(display.submitsOf("desktop").last().pixels, frame.pointerX, frame.pointerY, pointer), "png", File(dir!!, "$name.png"))
    }

    private val blocksPage = Page(
        "p_bausteine", "Bausteine",
        listOf(
            Block.Heading("titel", "Alle Bausteine"),
            Block.Text("text", "Text bricht um, wenn er länger ist als eine Zeile, und hält 16 px Rand."),
            Block.Button("knopf", "Knopf mit Ziel", target = "p_bausteine"),
            Block.Button("knopf2", "Knopf ohne Ziel"),
            Block.Toggle("schalter", "Schalter", on = true),
            Block.Value("wert", "Wert", "80 %"),
            Block.Progress("fortschritt", "Fortschritt", 60),
            Block.Divider("linie"),
            Block.List("punkte", listOf(ListItem("Punkte"), ListItem("als Liste")), ListStyle.BULLETS),
            Block.List("zahlen", listOf(ListItem("Nummern"), ListItem("der Reihe nach")), ListStyle.NUMBERS),
            Block.List("haken", listOf(ListItem("Häkchen", true), ListItem("zum Abhaken")), ListStyle.CHECKS),
            Block.Heading("gross", "Groß", size = HeadingSize.GROSS),
            Block.Button("zurueck", "Zurück", target = Block.BACK),
        ),
    )

    private fun demo(page: Page, permissions: Set<Permission> = emptySet(), input: InputMode = InputMode.POINTER): () -> G2App = {
        ScriptedApp(AppManifest("ch.madtreasures.demo", "Demo", "1.0.0", input, permissions)) { event, ui ->
            if (event == AppEvent.Start) {
                ui.definePages(listOf(page))
                ui.show(page.id)
                ui.menu(listOf(MenuItem("neu", "Neu laden"), MenuItem("hilfe", "Hilfe")))
            }
        }
    }

    @Test
    fun launcher() {
        val host = host({ StopwatchApp { clock } }, ::ShoppingListApp)
        host.launch("watch:ch.madtreasures.einkauf")
        settle()
        host.openLauncher()
        settle()
        val page = Page("p_apps", "Apps", listOf(Block.Button("app0", "Stoppuhr"), Block.Button("app1", "Einkauf · läuft")))
        pointerOn(page, "app0", dx = 140)
        save("apps-starter")
    }

    @Test
    fun stopwatch() {
        val host = host({ StopwatchApp { clock } })
        host.launch("watch:ch.madtreasures.stoppuhr")
        settle()
        host.gesture(GestureKind.CLICK)
        clock += 65_300
        scheduler.advanceBy(500)
        val page = Page(
            StopwatchApp.PAGE, "Stoppuhr",
            listOf(Block.Heading("zeit", "0:00,0", size = HeadingSize.GROSS), Block.Button("startstop", "Start"), Block.Button("reset", "Zurücksetzen")),
        )
        pointerOn(page, "startstop", dx = 60)
        save("apps-stoppuhr")
    }

    @Test
    fun shoppingList() {
        val host = host(::ShoppingListApp)
        host.launch("watch:ch.madtreasures.einkauf")
        settle()
        // Temple: down to "Brot", tick it, on to "Äpfel".
        host.gesture(GestureKind.SCROLL_DOWN)
        host.gesture(GestureKind.CLICK)
        host.gesture(GestureKind.SCROLL_DOWN)
        save("apps-einkauf")
        host.gesture(GestureKind.SHORT_THEN_LONG_PRESS)
        save("apps-menue")
    }

    @Test
    fun blocks() {
        val host = host(demo(blocksPage))
        host.launch("watch:ch.madtreasures.demo")
        settle()
        pointerOn(blocksPage, "knopf2", dx = 300)
        save("apps-bausteine")
        pointerTo(620, 380)
        repeat(9) { host.gesture(GestureKind.SCROLL_DOWN) }
        save("apps-bausteine-gescrollt")
    }

    @Test
    fun permissionQuestion() {
        val host = host(demo(blocksPage, setOf(Permission.MIC, Permission.LOCATION)))
        host.launch("watch:ch.madtreasures.demo")
        settle()
        save("apps-berechtigung")
    }

    @Test
    fun borderlessPicture() {
        val picture = Page(
            "p_bild", "Bild",
            listOf(Block.Image("bild", "data:image/png;base64," + Base64.getEncoder().encodeToString(landscape()), 576, 288, bleed = true)),
            statusBar = false,
        )
        val host = host(demo(picture, input = InputMode.GESTURES))
        host.launch("watch:ch.madtreasures.demo")
        settle()
        save("apps-bild-randlos")
    }

    /** A made-up picture in grey (sky, sun, mountains, lake), as a PNG. */
    private fun landscape(): ByteArray {
        val w = 288
        val h = 144
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val sky = (40 + 80 * y / h)
            val sun = if (hypot((x - 210).toDouble(), (y - 40).toDouble()) < 18) 255 else 0
            val ridge = 80 + 22 * sin(x / 23.0) + 12 * sin(x / 7.0)
            val far = 95 + 10 * sin(x / 40.0 + 1)
            var v = maxOf(sky, sun)
            if (y > far) v = 90
            if (y > ridge) v = 45 + (y - ridge).roundToInt() / 3
            if (y > 118) v = if ((x + y * 3) % 17 < 3) 150 else 30
            image.setRGB(x, y, v * 0x010101)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** The composite as the lens shows it: 16 shades of green, the pointer (if shown) on top with its color key. */
    private fun glassesImage(desktop: ByteArray, pointerX: Int, pointerY: Int, pointer: ByteArray?): BufferedImage {
        val w = DesktopLayout.SCREEN_WIDTH
        val h = DesktopLayout.SCREEN_HEIGHT
        fun green(v: Int): Int {
            val level = minOf(15, (v + 8) shr 4) * 17
            return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, green(desktop[y * w + x].toInt() and 0xFF))
        if (pointer != null) {
            for (y in 0 until PointerSprite.height) for (x in 0 until PointerSprite.width) {
                val v = pointer[y * PointerSprite.width + x].toInt() and 0xFF
                val px = pointerX + x
                val py = pointerY + y
                if (v != 0 && px < w && py < h) image.setRGB(px, py, if (v == 1) 0 else green(v))
            }
        }
        return image
    }
}

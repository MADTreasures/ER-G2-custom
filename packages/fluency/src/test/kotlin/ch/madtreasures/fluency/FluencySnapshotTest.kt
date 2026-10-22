package ch.madtreasures.fluency

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.input.KeyPress
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO

/**
 * Pictures of the app on the glasses, drawn by the real app host and desktop with Android's fonts, in the
 * glasses' 16 levels of green (README of the package):
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'ch.madtreasures.fluency.FluencySnapshotTest' -PsnapshotDir=$PWD/packages/fluency/bilder
 *
 * Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FluencySnapshotTest {
    private val dir = System.getProperty("snapshotDir")
    private val emojiFont = AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets)
    private val scheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val ports = FakePorts()
    private val link = FakePhoneLink()
    private lateinit var desktop: DesktopController
    private var app: FluencyApp? = null
    private var context: AppContext? = null
    private val id = "ch.madtreasures.fluency"

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        desktop = DesktopController(AndroidTextPainter(emojiFont), scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 10, 22, 14, 5) })
        desktop.startClock()
        desktop.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81, connection = "Verbunden") }
        desktop.attach(display)
        scheduler.runPending()
        // The pointer's tip in the bottom right corner of the stage, where only a sliver of it shows.
        val frame = desktop.frame.value
        desktop.moveBy((605 - frame.pointerX).toFloat(), (381 - frame.pointerY).toFloat())
        settle()
    }

    private fun host(phone: PhoneLink = NoPhoneLink) = AppHost(
        scheduler, desktop, AndroidTextPainter(emojiFont), ports,
        listOf({
            FluencyApp(clock = { scheduler.now }, linkFor = { ui -> context = ui; phone }).also { app = it }
        }),
        nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L },
    ).also {
        desktop.connectApps(it)
        it.launch("watch:$id")
        settle()
    }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
    }

    private fun AppHost.temple(kind: GestureKind) {
        gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun fromLink(event: LinkEvent) {
        scheduler.post { app!!.onLink(event, context!!) }
        settle()
    }

    /** The Probelauf, [ms] after it started; notes over the page have gone by then. */
    private fun probelauf(ms: Long): AppHost {
        val host = host()
        host.temple(GestureKind.SCROLL_DOWN)
        host.temple(GestureKind.CLICK)
        scheduler.advanceBy(ms)
        settle()
        return host
    }

    private fun save(name: String) {
        settle()
        val pixels = display.content("desktop").pixels
        val w = DesktopLayout.SCREEN_WIDTH
        val h = DesktopLayout.SCREEN_HEIGHT
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, green(pixels[y * w + x].toInt() and 0xFF))
        ImageIO.write(image, "png", File(dir!!, "$name.png"))
    }

    /** A grey value as the lens shows it: one of 16 levels of green. */
    private fun green(v: Int): Int {
        val level = minOf(15, (v + 8) shr 4) * 17
        return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
    }

    @Test
    fun start() {
        host()
        save("fluency-start")
    }

    @Test
    fun captions() {
        // In the long sentence: the caption has rolled on, the original shows the newest words.
        probelauf(46_000)
        save("fluency-probelauf")
    }

    @Test
    fun largeText() {
        ports.storage(id).set("gross", JsonPrimitive(true))
        probelauf(30_000)
        save("fluency-gross")
    }

    @Test
    fun withoutOriginal() {
        ports.storage(id).set("original", JsonPrimitive(false))
        probelauf(30_000)
        save("fluency-ohne-original")
    }

    @Test
    fun live() {
        val host = host(link)
        fromLink(LinkEvent.Connected)
        fromLink(LinkEvent.Message(Harness.welcome()))
        host.temple(GestureKind.CLICK)
        fromLink(LinkEvent.Message(Harness.state(running = true, re = 1)))
        fromLink(LinkEvent.Message(Harness.utt(1, "Bonjour, vous cherchez la gare ?", "Guten Tag, suchen Sie den Bahnhof?", final = true, from = "fr")))
        scheduler.advanceBy(3_000)
        fromLink(LinkEvent.Message(Harness.utt(2, "Elle est à dix minutes à pied, tout droit", "Er ist zehn Minuten zu Fuss entfernt, geradeaus")))
        scheduler.advanceBy(FluencyApp.LONG_NOTE_MS.toLong())
        save("fluency-live")
    }

    @Test
    fun history() {
        val host = probelauf(40_000)
        // V on a Bluetooth keyboard, the same as "Verlauf" in the app menu.
        host.key(KeyPress("v", "v"))
        settle()
        scheduler.advanceBy(FluencyApp.LONG_NOTE_MS.toLong())
        save("fluency-verlauf")
    }
}

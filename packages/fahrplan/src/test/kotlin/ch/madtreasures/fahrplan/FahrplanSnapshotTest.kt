package ch.madtreasures.fahrplan

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.input.KeyPress
import kotlinx.serialization.json.JsonArray
import org.junit.Assume.assumeTrue
import org.junit.Before
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
 * Pictures of the app on the glasses, drawn by the real app host and desktop with Android's fonts, in the
 * glasses' 16 levels of green (README of the package):
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'ch.madtreasures.fahrplan.FahrplanSnapshotTest' -PsnapshotDir=$PWD/packages/fahrplan/bilder
 *
 * The timetable answers with the recorded answers ([Fixtures]) for Monday 2 November 2026 at 07:40; only the
 * delay of the first connection in the list is made up, to show how one looks. Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FahrplanSnapshotTest {
    private val dir = System.getProperty("snapshotDir")
    private val emojiFont by lazy { AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets) }
    private val scheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val ports = FakePorts()
    private lateinit var desktop: DesktopController
    private lateinit var host: AppHost

    /** Made-up: the first connection 3 minutes late, from platform 16 instead of 15. */
    private var late = false

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        // Searched before: from Zürich HB to Bern, and two other trips.
        val store = ports.storage(FahrplanApp.ID)
        val zurich = Place("8503000", "Zürich HB")
        val bern = Place("8507000", "Bern")
        store.set("von", zurich.toJson())
        store.set("nach", bern.toJson())
        store.set(
            "zuletzt",
            JsonArray(
                listOf(
                    FahrplanApp.Route(zurich, bern),
                    FahrplanApp.Route(Place("8588078", "Zürich, Central"), Place("8501689", "Zermatt")),
                    FahrplanApp.Route(null, Place("8505000", "Luzern")),
                ).map { it.toJson() },
            ),
        )
        store.set("orte", JsonArray(listOf(zurich, bern).map { it.toJson() }))
        FahrplanApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        desktop = DesktopController(AndroidTextPainter(emojiFont), scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 11, 2, 7, 40) })
        desktop.startClock()
        desktop.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81) }
        desktop.attach(display)
        scheduler.runPending()
        // The pointer's tip in the bottom right corner of the stage, where only a sliver of it shows.
        val frame = desktop.frame.value
        desktop.moveBy((605 - frame.pointerX).toFloat(), (381 - frame.pointerY).toFloat())
        host = AppHost(
            scheduler, desktop, AndroidTextPainter(emojiFont), ports,
            listOf({ FahrplanApp(clock = { Fixtures.MONDAY + scheduler.now }, zone = Fixtures.ZONE) }),
            nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L },
        )
        desktop.connectApps(host)
    }

    private fun launch() {
        host.launch("watch:${FahrplanApp.ID}")
        settle()
    }

    /** Runs the app thread and the background work until both are idle. */
    private fun settle() {
        repeat(20) {
            scheduler.runPending()
            scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
            scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
            if (ports.work.isEmpty()) return
            ports.runWork()
        }
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun page() = host.currentPageOf(FahrplanApp.ID)!!

    private fun press(block: String) {
        for (direction in listOf(GestureKind.SCROLL_DOWN, GestureKind.SCROLL_UP)) {
            repeat(80) {
                if (host.state.value.focus == FocusTarget(block)) {
                    temple(GestureKind.CLICK)
                    return
                }
                temple(direction)
            }
        }
        throw AssertionError("$block never got the focus on ${host.state.value.page}")
    }

    /** Back to the top of a long page: the focus up to its first button. */
    private fun top() {
        val first = page().blocks.first { it is Block.Button || it is Block.Toggle }.id
        repeat(80) {
            if (host.state.value.focus == FocusTarget(first)) return
            temple(GestureKind.SCROLL_UP)
        }
    }

    private fun buttonFor(text: String): String = page().blocks.filterIsInstance<Block.Button>().first { it.text.startsWith(text) }.id

    /** Answers what the app asked the timetable, oldest first. */
    private fun answerRequests() {
        while (ports.requests.isNotEmpty()) {
            val (request, done) = ports.requests.removeAt(0)
            val body = Fixtures.text(FahrplanHostTest.fixtureFor(request.url))
            done(HttpResult(200, if (late && request.url.contains("/connections?from=8503000")) delayed(body) else body))
            settle()
        }
    }

    private fun delayed(body: String) = body.replaceFirst(
        "\"delay\":null,\"platform\":\"15\",\"prognosis\":{\"platform\":null}",
        "\"delay\":3,\"platform\":\"15\",\"prognosis\":{\"platform\":\"16\"}",
    ).replaceFirst(
        "\"departureTimestamp\":1793602140,\"delay\":null,\"platform\":\"15\",\"prognosis\":{\"platform\":null}",
        "\"departureTimestamp\":1793602140,\"delay\":3,\"platform\":\"15\",\"prognosis\":{\"platform\":\"16\"}",
    )

    private fun save(name: String) {
        settle()
        // Notes at the bottom of the page have gone by then.
        scheduler.advanceBy(3_000)
        settle()
        val pixels = display.content("desktop").pixels
        val w = DesktopLayout.SCREEN_WIDTH
        val h = DesktopLayout.SCREEN_HEIGHT
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, green(pixels[y * w + x].toInt() and 0xFF))
        ImageIO.write(image, "png", File(File(dir!!).apply { mkdirs() }, "$name.png"))
    }

    /** A grey value as the lens shows it: one of 16 levels of green. */
    private fun green(v: Int): Int {
        val level = minOf(15, (v + 8) shr 4) * 17
        return ((0x7C * level / 255) shl 16) or ((0xFF * level / 255) shl 8) or (0xA0 * level / 255)
    }

    @Test
    fun connections() {
        late = true
        launch()
        save("fahrplan-start")
        press(FahrplanApp.ST_SEARCH)
        answerRequests()
        top()
        save("fahrplan-verbindungen")
        press("${FahrplanApp.VE_CONN}3")
        top()
        save("fahrplan-verbindung")
        press("${FahrplanApp.DE_LEG}0")
        save("fahrplan-halte")
    }

    @Test
    fun changes() {
        launch()
        press(buttonFor("Zürich, Central → Zermatt"))
        answerRequests()
        press("${FahrplanApp.VE_CONN}0")
        top()
        save("fahrplan-umsteigen")
    }

    @Test
    fun typing() {
        // With a Bluetooth keyboard the question shows on the glasses.
        ports.hardwareKeyboard = true
        launch()
        press(FahrplanApp.ST_FROM)
        for (c in "Zürich") host.key(KeyPress(c.toString(), c.toString(), shift = c.isUpperCase()))
        settle()
        save("fahrplan-eingabe")
        host.key(KeyPress("Enter"))
        settle()
        answerRequests()
        top()
        save("fahrplan-auswahl")
    }

    @Test
    fun departures() {
        launch()
        press(FahrplanApp.ST_BOARD)
        ports.questions.last().third("Zürich HB")
        settle()
        answerRequests()
        save("fahrplan-abfahrten")
    }

    @Test
    fun nearby() {
        launch()
        press(FahrplanApp.ST_NEAR)
        ports.location.lines.last().fix(Fixtures.LAT, Fixtures.LON)
        settle()
        answerRequests()
        top()
        save("fahrplan-naehe")
    }
}

package ch.madtreasures.navi

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
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
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

/**
 * Pictures of the app on the glasses, drawn by the real app host and desktop with Android's fonts, in the
 * glasses' 16 levels of green, on the real map of Switzerland (README of the package):
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'ch.madtreasures.navi.NaviSnapshotTest' \
 *         -PsnapshotDir=$PWD/packages/navi/bilder -PnaviData=<folder with switzerland.map>
 *
 * Skipped without -PsnapshotDir and switzerland.map in -PnaviData.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class NaviSnapshotTest {
    private val dir = System.getProperty("snapshotDir")
    private val ch = Fixtures.realMap("switzerland.map")
    private val emojiFont by lazy { AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets) }
    private val scheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val ports = FakePorts()
    private lateinit var desktop: DesktopController
    private lateinit var host: AppHost

    /** 14:05 on the day of the pictures, in the zone whose time the navigation shows. */
    private val base = LocalDateTime.of(2026, 10, 22, 14, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Zürich main station, on the square in front of it. */
    private val bahnhofplatz = LatLon(47.37717, 8.54016)

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        // Switzerland on the watch, as after its download and search index.
        val files = ports.files(NaviApp.ID)!!
        link(ch!!, File(files, "maps/europe_switzerland.map"))
        link(index(ch), File(files, "index/europe_switzerland.idx"))
        NaviStore(ports.storage(NaviApp.ID)).put(Installed("europe/switzerland", "Schweiz", ch.length(), "2026-09-23", indexed = true))
        NaviApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        desktop = DesktopController(AndroidTextPainter(emojiFont), scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 10, 22, 14, 5) })
        desktop.startClock()
        desktop.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81) }
        desktop.attach(display)
        scheduler.runPending()
        // The pointer's tip in the bottom right corner of the stage, where only a sliver of it shows.
        val frame = desktop.frame.value
        desktop.moveBy((605 - frame.pointerX).toFloat(), (381 - frame.pointerY).toFloat())
        host = AppHost(
            scheduler, desktop, AndroidTextPainter(emojiFont), ports,
            listOf({ NaviApp(clock = { base + scheduler.now }) }),
            nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L },
        )
        desktop.connectApps(host)
        host.launch("watch:${NaviApp.ID}")
        settle()
    }

    private fun link(from: File, to: File) {
        to.parentFile.mkdirs()
        Files.createSymbolicLink(to.toPath(), from.absoluteFile.toPath())
    }

    /** Runs the app thread and the background work until both are idle. */
    private fun settle() {
        repeat(50) {
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

    private fun page() = host.currentPageOf(NaviApp.ID)!!

    private fun press(block: String) {
        for (direction in listOf(GestureKind.SCROLL_DOWN, GestureKind.SCROLL_UP)) {
            repeat(60) {
                if (host.state.value.focus == FocusTarget(block)) {
                    temple(GestureKind.CLICK)
                    return
                }
                temple(direction)
            }
        }
        throw AssertionError("$block never got the focus on ${host.state.value.page}")
    }

    private fun buttonFor(text: String): String = page().blocks.filterIsInstance<Block.Button>().first { it.text.startsWith(text) }.id

    private fun answer(text: String) {
        ports.questions.last().third(text)
        settle()
    }

    private fun gps(p: LatLon, acc: Float = 4f, speed: Float? = null, bearing: Float? = null) {
        ports.location.lines.last().fix(p.lat, p.lon, acc, base + scheduler.now, speed, bearing)
        settle()
    }

    private fun backTo(page: String) {
        while (host.state.value.page != page) {
            host.back()
            settle()
        }
    }

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

    /** Searches [query] from the menu and takes the first result: the route and its overview. */
    private fun routeTo(query: String) {
        temple(GestureKind.CLICK)
        press("m_ziel")
        answer(query)
        press("t_0")
    }

    @Test
    fun map() {
        gps(bahnhofplatz)
        save("navi-karte")
        temple(GestureKind.CLICK)
        save("navi-menue")
    }

    @Test
    fun search() {
        gps(bahnhofplatz)
        temple(GestureKind.CLICK)
        press("m_ziel")
        answer("Grossmünster")
        save("navi-treffer")
        press("t_0")
        save("navi-route")
        // The route page keeps start and destination.
        backTo(NaviApp.P_KARTE)
        temple(GestureKind.CLICK)
        press("m_route")
        save("navi-route-planen")
    }

    @Test
    fun navigation() {
        gps(bahnhofplatz)
        routeTo("Grossmünster")
        press("e_los")
        // Some way along, before a turn: where the app's router takes the wearer, walking.
        val target = SearchIndex(index(ch!!)).use { it.search("Grossmünster", bahnhofplatz).first() }
        val route = MapSource(ch).use { Router(listOf(it)).route(bahnhofplatz, target.position, Profile.FOOT) }
        val turn = route.maneuvers.first { it.turn != Turn.START && it.turn != Turn.ARRIVE && route.cum[it.index] > 250 }
        val at = route.cum[turn.index] - 70
        val walk = DemoTrip(route, 0, 1.4)
        val fix = walk.fixAt((at / 1.4 * 1000).toLong())
        gps(LatLon(fix.lat, fix.lon), acc = 4f, speed = 1.4f, bearing = fix.bearing)
        save("navi-navigation")
        temple(GestureKind.CLICK)
        save("navi-navigation-karte")
    }

    @Test
    fun maps() {
        temple(GestureKind.CLICK)
        press("m_karten")
        press("k_neu")
        save("navi-erdteile")
        press(buttonFor("Europa"))
        save("navi-laender")
        press(buttonFor("Liechtenstein"))
        press("d_laden")
        ports.downloads.started.last().listener.onProgress(950_000, 2_261_314)
        settle()
        backTo(NaviApp.P_LAENDER)
        press(buttonFor("Deutschland"))
        press(buttonFor("Bayern"))
        save("navi-land")
        backTo(NaviApp.P_KARTEN)
        save("navi-karten")
    }

    companion object {
        private var built: File? = null

        /** The search index of Switzerland, built once for all pictures. */
        @Synchronized
        fun index(map: File): File = built ?: File(createTempDirectory("navi-shots").toFile().apply { deleteOnExit() }, "switzerland.idx").also { f ->
            MapFile(map).use { SearchIndexBuilder.build(it, f) }
            built = f
        }
    }
}

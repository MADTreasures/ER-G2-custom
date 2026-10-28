package ch.madtreasures.musik

import ch.madtreasures.g2watch.FakeDisplay
import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.media.AndroidMediaEngine
import ch.madtreasures.g2watch.apps.media.GrayPng
import ch.madtreasures.g2watch.apps.media.MediaSnapshot
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.desktop.DesktopLayout
import ch.madtreasures.g2watch.desktop.GrayRaster
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
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Pictures of the app on the glasses, drawn by the real app host and desktop with Android's fonts, in the
 * glasses' 16 levels of green (README of the package):
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'ch.madtreasures.musik.MusikSnapshotTest' -PsnapshotDir=$PWD/packages/musik/bilder
 *
 * The players and the stream are the host's fakes, with made-up but typical reports: what Spotify on the watch
 * and SRF 3 send. Skipped without -PsnapshotDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class MusikSnapshotTest {
    private val dir = System.getProperty("snapshotDir")
    private val emojiFont by lazy { AndroidTextPainter.emojiFont(RuntimeEnvironment.getApplication().assets) }
    private val scheduler = FakeScheduler()
    private val display = FakeDisplay()
    private val ports = FakePorts()
    private lateinit var desktop: DesktopController
    private lateinit var host: AppHost

    /** 14:05 on the day of the pictures. */
    private val base = LocalDateTime.of(2026, 10, 28, 14, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val spotify by lazy {
        MediaInfo(
            app = MusikApp.SPOTIFY,
            appName = "Spotify",
            state = MediaState.PLAYING,
            title = "W. Nuss vo Bümpliz",
            artist = "Patent Ochsner",
            album = "Gmües",
            durationMs = 271_000,
            positionMs = 98_000,
            positionAt = base,
            artwork = record(),
        )
    }

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        // Some stations kept as favorites, SRF 3 heard last.
        val favorites = listOf("SRF 3", "Radio Swiss Jazz", "Energy Zürich").map { name -> SwissStations.all.first { it.name == name } }
        ports.storage(MusikApp.ID).set("favoriten", JsonArray(favorites.map { it.toJson() }))
        ports.storage(MusikApp.ID).set("zuletzt", favorites.first().toJson())
        MusikApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        ports.media.snapshot = MediaSnapshot(listOf(spotify), 9, 15, "Kopfhörer: Pixel Buds")
        desktop = DesktopController(AndroidTextPainter(emojiFont), scheduler, nowMs = { scheduler.now }, now = { LocalDateTime.of(2026, 10, 28, 14, 5) })
        desktop.startClock()
        desktop.updateStatus { it.copy(watchBattery = 76, glassesBattery = 81) }
        desktop.attach(display)
        scheduler.runPending()
        // The pointer's tip in the bottom right corner of the stage, where only a sliver of it shows.
        val frame = desktop.frame.value
        desktop.moveBy((605 - frame.pointerX).toFloat(), (381 - frame.pointerY).toFloat())
        host = AppHost(
            scheduler, desktop, AndroidTextPainter(emojiFont), ports,
            listOf({ MusikApp(clock = { base + scheduler.now }) }),
            nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L },
        )
        desktop.connectApps(host)
    }

    private fun launch() {
        host.launch("watch:${MusikApp.ID}")
        settle()
    }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
        scheduler.advanceBy(DesktopController.POINTER_INTERVAL_MS)
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun page() = host.currentPageOf(MusikApp.ID)!!

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

    private fun buttonFor(text: String): String = page().blocks.filterIsInstance<Block.Button>().first { it.text.startsWith(text) }.id

    /** Answers what the app asked radio-browser.info, oldest first: the clicks it counts with an empty object. */
    private fun answerRequests(body: (String) -> String = { "{}" }) {
        while (ports.requests.isNotEmpty()) {
            val (request, done) = ports.requests.removeAt(0)
            done(HttpResult(200, body(request.url)))
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

    /** A made-up cover, as the watch app sends one (grey, 120 px): a record with its grooves and label. */
    private fun record(): String {
        val n = MediaInfo.ARTWORK_SIZE
        val r = GrayRaster(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - n / 2.0 + 0.5
            val dy = y - n / 2.0 + 0.5
            val d = sqrt(dx * dx + dy * dy)
            val v = when {
                d < 4 -> 20
                d < 17 -> 190
                d < 19 -> 30
                d < 55 -> if ((d / 2.5).toInt() % 2 == 0) 80 else 45
                d < 57 -> 110
                else -> 20 + (x + y) / 10
            }
            r.pixels[y * n + x] = v.toByte()
        }
        return GrayPng.dataUrl(r)
    }

    @Test
    fun spotify() {
        launch()
        save("musik-start")
        press(MusikApp.ST_PLAYER)
        save("musik-spotify")
        temple(GestureKind.LONG_PRESS)
        temple(GestureKind.SCROLL_UP)
        save("musik-lautstaerke")
        temple(GestureKind.CLICK)
        temple(GestureKind.SHORT_THEN_LONG_PRESS)
        save("musik-menue")
    }

    @Test
    fun radio() {
        launch()
        press(MusikApp.ST_RADIO)
        save("musik-senderliste")
        press(buttonFor("SRF 3"))
        answerRequests()
        ports.streams.opened.last().listener.onState(PlayerState.PLAYING, "PAOLO CONTE - VIA CON ME", "SRF 3", null)
        settle()
        save("musik-radio")
    }

    @Test
    fun search() {
        launch()
        press(MusikApp.ST_RADIO)
        press(MusikApp.RA_SEARCH)
        ports.questions.last().third("jazz")
        settle()
        answerRequests { JAZZ_STATIONS }
        save("musik-suche")
    }

    @Test
    fun withoutAccess() {
        ports.media.snapshot = MediaSnapshot(emptyList(), 9, 15, "Kopfhörer: Pixel Buds", AndroidMediaEngine.NO_ACCESS)
        launch()
        press(MusikApp.ST_PLAYER)
        save("musik-ohne-zugriff")
    }

    private companion object {
        /** What radio-browser.info answered to "jazz" on 28 October 2026 (its first eight), shortened to what the app reads. */
        val JAZZ_STATIONS = listOf(
            Triple("101 SMOOTH JAZZ", "US", "d28420a4-eccf-47a2-ace1-088c7e7cb7e0"),
            Triple("Adroit Jazz Underground", "US", "ea8059be-d119-4de3-b27b-0d9bd6aedb17"),
            Triple("Jazz Radio Blues", "FR", "96102c22-0601-11e8-ae97-52543be04c81"),
            Triple("Jazz Radio", "FR", "96136fe5-0601-11e8-ae97-52543be04c81"),
            Triple("IBIZA CHILLOUT LOUNGE - Ambient, Deep House, Balearic, Smooth Jazz, Acoustic, Chill House", "DE", "d271b4e8-a298-4862-9f4b-025c110cedd9"),
            Triple("Bossa Jazz Brasil", "BR", "7ada8a81-5ae1-418c-8f18-51d2f38d86a4"),
            Triple("Radio Swiss Jazz", "CH", "961ac56b-0601-11e8-ae97-52543be04c81"),
            Triple("Jazz Sakura (asia dream radio)", "JP", "9766d47a-68a3-4a30-8aec-c026f1ec6020"),
        ).joinToString(",", "[", "]") { (name, country, uuid) ->
            """{"stationuuid":"$uuid","name":"$name","url_resolved":"https://stream.example/${uuid.take(8)}.mp3","countrycode":"$country","codec":"MP3","bitrate":128}"""
        }
    }
}

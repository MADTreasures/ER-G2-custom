package ch.madtreasures.musik

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.media.GrayPng
import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * With Android's real fonts (Robolectric, native graphics) and the host's own layout: whatever a player or a
 * station sends, the pages that take gestures – the player and the station – never grow past the app area. They
 * cannot scroll, so the time and the state at the bottom must always be in view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class MusikPageFitTest {
    private val text = AndroidTextPainter()

    /** Hard cases for a width estimate: long compounds, capitals, wide letters, digits, Chinese, emoji, other scripts. */
    private val samples = listOf(
        "Die Donaudampfschifffahrtsgesellschaftskapitänswitwe singt das allerlängste Lied der ganzen Schweiz",
        "ACHTUNG ACHTUNG – WIR UNTERBRECHEN FÜR EINE WICHTIGE VERKEHRSMELDUNG AUF DER A1 ZWISCHEN BERN UND ZÜRICH",
        "MMM WWW mmm www MMM WWW mmm www MMM WWW mmm www MMM WWW mmm www MMM WWW mmm www",
        "Symphonie Nr. 9 in d-Moll, op. 125 – IV. Presto – Allegro assai (Live 1989, remastered 2024)",
        "月亮代表我的心 – 邓丽君 – 经典老歌精选集 第一辑 第二十三首",
        "🎉🎶 Party Hits 2026 🔥🔥🔥 👨‍👩‍👧 🇨🇭 Sommer Mix 🎧",
        "ต้องการเพลงที่ดีที่สุดในประเทศไทยตอนนี้",
        "Ja",
        "",
    )

    private val cover = GrayPng.dataUrl(GrayRaster(120, 120))
    private val wide = GrayPng.dataUrl(GrayRaster(120, 60))

    private fun check(ui: FakeAppContext, pageId: String, case: String) {
        val page = ui.page(pageId)
        val layout = PageLayout.of(page, text)
        assertTrue(
            "$case: $pageId is ${layout.contentHeight} px high, more than ${layout.height}: ${page.blocks}",
            layout.contentHeight <= layout.height,
        )
    }

    @Test
    fun `the player page fits whatever plays`() {
        for (sample in samples) {
            for (other in listOf(samples.first(), "Queen", "")) {
                for (art in listOf(cover, wide, null)) {
                    for (duration in listOf(0L, 271_000L, 4_000_000L)) {
                        val app = MusikApp(clock = { 1_000L })
                        val ui = FakeAppContext.forApp(app)
                        app.onEvent(AppEvent.Start, ui)
                        app.onEvent(AppEvent.Visible, ui)
                        val player = MediaInfo(MusikApp.SPOTIFY, "Spotify", MediaState.PLAYING, sample, other, other, duration, 3_599_000, 1_000, artwork = art)
                        app.onEvent(AppEvent.Media(listOf(player), 7, 15, "Kopfhörer: Pixel Buds"), ui)
                        check(ui, MusikApp.P_PLAYER, "„$sample“ by „$other“, cover $art, $duration ms")
                        ui.pages.show(MusikApp.P_PLAYER)
                        app.onEvent(AppEvent.Navigate(MusikApp.P_START, MusikApp.P_PLAYER, MusikApp.ST_PLAYER), ui)
                        app.onEvent(AppEvent.Gesture(GestureKind.LONG_PRESS, InputSource.RIGHT), ui)
                        check(ui, MusikApp.P_PLAYER, "volume, „$sample“")
                    }
                }
            }
        }
    }

    @Test
    fun `the player page fits without players and without access`() {
        for (message in listOf(null, "Musik der Uhr: Zugriff noch nicht erlaubt", "Keine Mediensteuerung auf dieser Uhr", samples[1])) {
            val app = MusikApp()
            val ui = FakeAppContext.forApp(app)
            app.onEvent(AppEvent.Start, ui)
            app.onEvent(AppEvent.Media(emptyList(), 15, 15, null, message), ui)
            check(ui, MusikApp.P_PLAYER, "no players, $message")
        }
    }

    @Test
    fun `the station page fits whatever the station sends`() {
        val long = Station(samples[0], "https://stream.example.ch/radio.mp3", null, "CH", "AAC+", 320)
        for (station in listOf(SwissStations.all.first(), long)) {
            for (sample in samples) {
                for (state in PlayerState.entries) {
                    val app = MusikApp()
                    val ui = FakeAppContext.forApp(app)
                    ui.storage.set("favoriten", JsonArray(listOf(station.toJson())))
                    app.onEvent(AppEvent.Start, ui)
                    app.onEvent(AppEvent.Click(MusikApp.P_RADIO, "${MusikApp.RA_FAVORITE}0"), ui)
                    app.onEvent(AppEvent.Player(state, ui.stream()!!.url, sample, sample, sample.ifEmpty { null }), ui)
                    check(ui, MusikApp.P_SENDER, "${station.name}: $state, „$sample“")
                    app.onEvent(AppEvent.Gesture(GestureKind.LONG_PRESS, InputSource.RIGHT), ui)
                    check(ui, MusikApp.P_SENDER, "volume, ${station.name}")
                }
            }
        }
    }
}

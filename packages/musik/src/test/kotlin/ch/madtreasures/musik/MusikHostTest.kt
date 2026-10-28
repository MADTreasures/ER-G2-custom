package ch.madtreasures.musik

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.media.MediaSnapshot
import ch.madtreasures.g2watch.apps.render.FocusTarget
import ch.madtreasures.g2watch.apps.textOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app in the real app host of the watch app, with fakes for the watch's players and the stream: the temples
 * work its pages, its commands reach the players and the stream, what they report reaches the glasses, its
 * pictures load, and the host refuses none of its commands.
 */
class MusikHostTest {
    private val scheduler = FakeScheduler()
    private val ports = FakePorts()
    private val host = AppHost(
        scheduler, FakeScreen(), FakeText(), ports,
        listOf({ MusikApp(clock = { 1_700_000_000_000L + scheduler.now }) }),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private val spotify = MediaInfo(
        app = MusikApp.SPOTIFY,
        appName = "Spotify",
        state = MediaState.PLAYING,
        title = "Bigger Than Us",
        artist = "Michael Schulte",
        durationMs = 182_000,
        positionMs = 30_000,
        positionAt = 1_700_000_000_000L,
    )

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun page() = host.currentPageOf(MusikApp.ID)!!

    /** Moves the focus along the page with the temple until [block] has it, then clicks it. */
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

    private fun launch() {
        MusikApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        ports.media.snapshot = MediaSnapshot(listOf(spotify), 7, 15, "Kopfhörer: Pixel Buds")
        host.launch("watch:${MusikApp.ID}")
        settle()
        assertEquals(MusikApp.ID, host.state.value.visible)
    }

    @After
    fun nothingRefused() {
        assertFalse(ports.logs.joinToString("\n"), ports.logs.any { "abgelehnt" in it || "ließ sich nicht lesen" in it })
    }

    @Test
    fun `the temples steer Spotify on the watch`() {
        launch()
        assertEquals(1, ports.media.listeners.size)
        assertEquals("Spotify – spielt", page().textOf(MusikApp.ST_PLAYER))
        press(MusikApp.ST_PLAYER)
        assertEquals(MusikApp.P_PLAYER, host.state.value.page)
        assertEquals("Bigger Than Us", page().textOf(MusikApp.PL_TITLE))
        assertEquals("Spielt · 0:30 / 3:02", page().textOf(MusikApp.PL_TIME))

        temple(GestureKind.CLICK)
        temple(GestureKind.SCROLL_DOWN)
        temple(GestureKind.LONG_PRESS)
        temple(GestureKind.SCROLL_UP)
        temple(GestureKind.CLICK)
        assertEquals(
            listOf(MediaAction.Pause(MusikApp.SPOTIFY), MediaAction.Next(MusikApp.SPOTIFY), MediaAction.Volume(1)),
            ports.media.commands,
        )
        // What the watch reports next shows at once.
        ports.media.change(MediaSnapshot(listOf(spotify.copy(state = MediaState.PAUSED, title = "Nemo")), 8, 15, "Lautsprecher der Uhr"))
        settle()
        assertEquals("Nemo", page().textOf(MusikApp.PL_TITLE))
        assertTrue(page().textOf(MusikApp.PL_TIME).startsWith("Pause"))

        // Hidden, the app follows the players no more.
        host.openLauncher()
        settle()
        assertTrue(ports.media.listeners.isEmpty())
    }

    @Test
    fun `a station plays through the watch's stream and shows what the station sends`() {
        launch()
        press(MusikApp.ST_RADIO)
        assertEquals(MusikApp.P_RADIO, host.state.value.page)
        press(buttonFor("Radio Swiss Pop"))
        assertEquals(MusikApp.P_SENDER, host.state.value.page)
        val stream = ports.streams.opened.single()
        assertEquals("https://stream.srg-ssr.ch/srgssr/rsp/mp3/128", stream.url)
        assertEquals("Radio Swiss Pop", stream.title)
        // radio-browser.info gets its click.
        val click = ports.requests.single()
        assertEquals("https://all.api.radio-browser.info/json/url/961ac635-0601-11e8-ae97-52543be04c81", click.first.url)
        click.second(HttpResult(200, "{}"))

        stream.listener.onState(PlayerState.PLAYING, "Vlada - Every Time", "Swiss Pop", null)
        settle()
        assertEquals("Vlada - Every Time", page().textOf(MusikApp.SE_SONG))
        assertEquals("Live · MP3 128 kbit/s", page().textOf(MusikApp.SE_STATE))

        temple(GestureKind.CLICK)
        assertTrue(stream.paused)
        temple(GestureKind.CLICK)
        assertFalse(stream.paused)
        // Swiping on: the next station replaces the stream.
        temple(GestureKind.SCROLL_DOWN)
        assertTrue(stream.released)
        assertEquals("Radio Swiss Jazz", ports.streams.opened.last().title)

        // The station plays on behind the launcher.
        host.openLauncher()
        settle()
        assertFalse(ports.streams.opened.last().released)
    }
}

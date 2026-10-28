package ch.madtreasures.musik

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PlayerAction
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.media.GrayPng
import ch.madtreasures.g2watch.apps.textOf
import ch.madtreasures.g2watch.apps.valueOf
import ch.madtreasures.g2watch.desktop.GrayRaster
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app against [FakeAppContext]: what the pages show, what the gestures, buttons and the app menu ask of
 * the players on the watch and of the app's own stream, the station list, the favorites, the search at
 * radio-browser.info and what happens when a server or a stream fails.
 */
class MusikAppTest {
    private var now = 1_700_000_000_000L
    private val app = MusikApp(clock = { now })
    private val ui = FakeAppContext.forApp(app)

    private val cover = GrayPng.dataUrl(GrayRaster(120, 90))

    private val spotify = MediaInfo(
        app = MusikApp.SPOTIFY,
        appName = "Spotify",
        state = MediaState.PLAYING,
        title = "W. Nuss vo Bümpliz",
        artist = "Patent Ochsner",
        album = "Gmües",
        durationMs = 271_000,
        positionMs = 61_000,
        positionAt = now,
        artwork = cover,
    )

    private fun send(e: AppEvent) = app.onEvent(e, ui)

    private fun start() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    private fun page(): Page = ui.current!!

    private fun media(vararg players: MediaInfo, volume: Int = 7, message: String? = null) =
        send(AppEvent.Media(players.toList(), volume, 15, "Kopfhörer: Pixel Buds", message))

    /** A click as the host does it: a button with a page shows that page and tells the app. */
    private fun click(block: String) {
        val p = page()
        val b = p.block(block) as? Block.Button ?: throw AssertionError("no button $block on ${p.id}: ${p.blocks.map { it.id }}")
        val target = b.target
        if (target == null) {
            send(AppEvent.Click(p.id, block))
        } else {
            ui.pages.show(target)
            send(AppEvent.Navigate(p.id, target, block))
        }
    }

    /** The button on this page whose text starts with [text]. */
    private fun buttonFor(text: String): String =
        page().blocks.filterIsInstance<Block.Button>().firstOrNull { it.text.startsWith(text) }?.id
            ?: throw AssertionError("no button „$text“ on ${page().id}: ${page().blocks.filterIsInstance<Block.Button>().map { it.text }}")

    private fun back() {
        send(AppEvent.Back(page().id))
        ui.pages.back()
    }

    private fun gesture(kind: GestureKind) = send(AppEvent.Gesture(kind, InputSource.RIGHT))

    private fun menu(item: String) {
        assertTrue("$item not in the menu ${ui.menu}", ui.menu.any { it.id == item })
        send(AppEvent.Menu(item))
    }

    /** Answers the request whose address contains [part]. */
    private fun answer(part: String, result: HttpResult) {
        val i = ui.requests.indexOfFirst { part in it.first.url }
        if (i < 0) throw AssertionError("no request with $part: ${ui.requests.map { it.first.url }}")
        ui.requests.removeAt(i).second(result)
    }

    private fun requested(part: String): HttpRequest? = ui.requests.firstOrNull { part in it.first.url }?.first

    private fun ok(body: String = "{}") = HttpResult(200, body)

    private fun srf3() = SwissStations.all.first { it.name == "SRF 3" }

    private fun playSrf3() {
        start()
        click(MusikApp.ST_RADIO)
        click(buttonFor("SRF 3"))
    }

    @Test
    fun `the start page comes first, and the players are followed only while the app shows`() {
        start()
        assertEquals(MusikApp.P_START, page().id)
        assertTrue(ui.followsMedia())
        assertEquals(listOf("Sender suchen", "Lauter", "Leiser"), ui.menu.map { it.text })
        send(AppEvent.Hidden)
        assertFalse(ui.followsMedia())
        send(AppEvent.Visible)
        assertTrue(ui.followsMedia())
    }

    @Test
    fun `the start page tells what plays, the volume and where the sound goes`() {
        start()
        assertEquals("–", page().valueOf(MusikApp.ST_VOLUME))
        media(spotify)
        assertEquals("Spotify – spielt", page().textOf(MusikApp.ST_PLAYER))
        assertEquals("7 von 15", page().valueOf(MusikApp.ST_VOLUME))
        assertEquals("Kopfhörer: Pixel Buds", page().valueOf(MusikApp.ST_OUTPUT))
        media(spotify.copy(state = MediaState.PAUSED))
        assertEquals("Spotify – Pause", page().textOf(MusikApp.ST_PLAYER))
        assertNull(page().block(MusikApp.ST_NOTE))
    }

    @Test
    fun `the player shows the song, its cover and its time, and the gestures steer it`() {
        start()
        media(spotify)
        click(MusikApp.ST_PLAYER)
        val p = page()
        assertEquals(MusikApp.P_PLAYER, p.id)
        assertEquals("Spotify", p.name)
        assertEquals("W. Nuss vo Bümpliz", p.textOf(MusikApp.PL_TITLE))
        assertEquals("Patent Ochsner · Gmües", p.textOf(MusikApp.PL_LINE))
        // The cover keeps its proportions: 120 × 90 becomes 96 × 72.
        val image = p.block(MusikApp.PL_COVER) as Block.Image
        assertEquals(Triple(cover, 96, 72), Triple(image.src, image.w, image.h))
        val time = p.block(MusikApp.PL_TIME) as Block.Progress
        assertEquals("Spielt · 1:01 / 4:31", time.text)
        assertEquals(22, time.value)

        // The time moves on every second while it plays.
        assertEquals(Pair(1_000L, true), ui.timers[MusikApp.TICK])
        now += 10_000
        send(AppEvent.Timer(MusikApp.TICK))
        assertEquals("Spielt · 1:11 / 4:31", page().textOf(MusikApp.PL_TIME))

        gesture(GestureKind.CLICK)
        gesture(GestureKind.SCROLL_DOWN)
        gesture(GestureKind.SCROLL_UP)
        assertEquals(
            listOf(MediaAction.Pause(MusikApp.SPOTIFY), MediaAction.Next(MusikApp.SPOTIFY), MediaAction.Previous(MusikApp.SPOTIFY)),
            ui.mediaActions.drop(1),
        )
        media(spotify.copy(state = MediaState.PAUSED, positionAt = now))
        assertEquals("Pause · 1:01 / 4:31", page().textOf(MusikApp.PL_TIME))
        assertNull(ui.timers[MusikApp.TICK])
        gesture(GestureKind.CLICK)
        assertEquals(MediaAction.Play(MusikApp.SPOTIFY), ui.mediaActions.last())

        // A player that cannot skip back says so instead.
        media(spotify.copy(canPrevious = false))
        val before = ui.mediaActions.size
        gesture(GestureKind.SCROLL_UP)
        assertEquals(before, ui.mediaActions.size)
        assertEquals("Spotify kann hier nicht zurück", ui.toasts.last())
    }

    @Test
    fun `a long press turns swiping into the volume until a tap or a pause`() {
        start()
        media(spotify)
        click(MusikApp.ST_PLAYER)
        gesture(GestureKind.LONG_PRESS)
        assertEquals(MusikApp.VOLUME_HINT, page().textOf(MusikApp.PL_LINE))
        assertEquals("Lautstärke 7 von 15", page().textOf(MusikApp.PL_VOLUME))
        assertNull(page().block(MusikApp.PL_TIME))
        gesture(GestureKind.SCROLL_UP)
        assertEquals("Lautstärke 8 von 15", page().textOf(MusikApp.PL_VOLUME))
        assertEquals(53, (page().block(MusikApp.PL_VOLUME) as Block.Progress).value)
        gesture(GestureKind.SCROLL_DOWN)
        gesture(GestureKind.SCROLL_DOWN)
        assertEquals(listOf(MediaAction.Volume(1), MediaAction.Volume(-1), MediaAction.Volume(-1)), ui.mediaActions.drop(1))
        // The watch's own report wins.
        media(spotify, volume = 9)
        assertEquals("Lautstärke 9 von 15", page().textOf(MusikApp.PL_VOLUME))
        // A tap ends it, and is no pause.
        gesture(GestureKind.CLICK)
        assertEquals("Patent Ochsner · Gmües", page().textOf(MusikApp.PL_LINE))
        assertEquals(4, ui.mediaActions.size)
        // Five seconds without swiping end it too; on the watch a swipe to the left starts it.
        gesture(GestureKind.SWIPE_LEFT)
        assertEquals(Pair(5_000L, false), ui.timers[MusikApp.VOLUME_TIMER])
        send(AppEvent.Timer(MusikApp.VOLUME_TIMER))
        assertTrue(page().block(MusikApp.PL_TIME) is Block.Progress)
    }

    @Test
    fun `without access to the media controls the app says so and the keys still work`() {
        start()
        media(volume = 7, message = "Musik der Uhr: Zugriff noch nicht erlaubt")
        assertTrue(page().textOf(MusikApp.ST_NOTE).startsWith("Musik der Uhr: Zugriff noch nicht erlaubt. Steuern geht trotzdem"))
        click(MusikApp.ST_PLAYER)
        assertEquals("Spotify", page().textOf(MusikApp.PL_TITLE))
        assertTrue(page().textOf(MusikApp.PL_LINE).startsWith("Musik der Uhr: Zugriff noch nicht erlaubt."))
        assertEquals(MusikApp.NOTE_PICTURE, (page().block(MusikApp.PL_COVER) as Block.Image).src)
        gesture(GestureKind.CLICK)
        gesture(GestureKind.SCROLL_DOWN)
        assertEquals(listOf(MediaAction.PlayPause(), MediaAction.Next()), ui.mediaActions.drop(1))
        assertEquals(listOf("Play/Pause", "Nächster Titel"), ui.toasts)
    }

    @Test
    fun `when nothing plays the player page says how to start`() {
        start()
        media()
        click(MusikApp.ST_PLAYER)
        assertEquals("Es spielt nichts", page().textOf(MusikApp.PL_TITLE))
        assertEquals("Spotify", page().name)
        gesture(GestureKind.CLICK)
        assertEquals(MediaAction.PlayPause(), ui.mediaActions.last())
        assertTrue(ui.toasts.isEmpty())
    }

    @Test
    fun `a long title is cut to one line, and a song without time shows its state`() {
        start()
        val title = "Die Donaudampfschifffahrtsgesellschaftskapitänswitwe singt das allerlängste Lied der ganzen Schweiz"
        media(spotify.copy(title = title, durationMs = 0))
        click(MusikApp.ST_PLAYER)
        val shown = page().textOf(MusikApp.PL_TITLE)
        assertTrue(shown, shown.endsWith("…") && title.startsWith(shown.removeSuffix("…")))
        assertEquals(1, TextFit.lines(shown, 28, bold = true))
        assertEquals("Spielt", page().textOf(MusikApp.PL_STATE))
        assertNull(ui.timers[MusikApp.TICK])
    }

    @Test
    fun `the menu of the player searches in Spotify, opens it and goes to the radio`() {
        start()
        media(spotify)
        click(MusikApp.ST_PLAYER)
        assertEquals(listOf("Lauter", "Leiser", "Suchen und spielen", "Spotify auf der Uhr öffnen", "Radiosender"), ui.menu.map { it.text })
        menu(MusikApp.M_SEARCH_PLAYER)
        assertEquals(MusikApp.ASK_PLAYER, ui.question!!.first)
        send(AppEvent.TextInput(MusikApp.ASK_PLAYER, "  Patent   Ochsner "))
        assertEquals(MediaAction.Search("Patent Ochsner", MusikApp.SPOTIFY), ui.mediaActions.last())
        assertEquals("Spotify sucht „Patent Ochsner“", ui.toasts.last())
        // The search is offered again next time.
        menu(MusikApp.M_SEARCH_PLAYER)
        assertEquals(listOf("Patent Ochsner"), ui.question!!.third)
        send(AppEvent.TextInput(MusikApp.ASK_PLAYER, null))
        menu(MusikApp.M_OPEN)
        assertEquals(MediaAction.Open(MusikApp.SPOTIFY), ui.mediaActions.last())
        menu(MusikApp.M_LOUDER)
        assertEquals("Lautstärke 8 von 15", ui.toasts.last())
        menu(MusikApp.M_RADIO)
        assertEquals(MusikApp.P_RADIO, page().id)
        assertEquals(listOf(MusikApp.P_START, MusikApp.P_RADIO), ui.pages.historyIds)
    }

    @Test
    fun `a station of the list plays, shows its song, pauses, and swiping turns the dial`() {
        playSrf3()
        assertEquals(PlayerAction.Play("https://stream.srg-ssr.ch/srgssr/srf3/mp3/128", "SRF 3"), ui.stream())
        assertEquals(MusikApp.P_SENDER, page().id)
        assertEquals("SRF 3", page().textOf(MusikApp.SE_NAME))
        assertEquals("Verbindet …", page().textOf(MusikApp.SE_STATE))
        // radio-browser.info gets its click, with the app's name.
        val click = requested("/json/url/961f1da4-0601-11e8-ae97-52543be04c81")!!
        assertEquals("G2-Musik/1.0", click.headers["User-Agent"])

        val url = ui.stream()!!.url
        send(AppEvent.Player(PlayerState.PLAYING, url, "PAOLO CONTE - VIA CON ME", "SRF 3"))
        assertEquals("PAOLO CONTE - VIA CON ME", page().textOf(MusikApp.SE_SONG))
        assertEquals("Live · MP3 128 kbit/s", page().textOf(MusikApp.SE_STATE))

        gesture(GestureKind.CLICK)
        assertEquals(PlayerAction.Pause, ui.playerActions.last())
        assertEquals("Pause", page().textOf(MusikApp.SE_STATE))
        send(AppEvent.Player(PlayerState.PAUSED, url, null, "SRF 3", "Eine andere App spielt"))
        assertEquals("Pause – Eine andere App spielt", page().textOf(MusikApp.SE_STATE))
        gesture(GestureKind.CLICK)
        assertEquals(PlayerAction.Resume, ui.playerActions.last())

        gesture(GestureKind.SCROLL_DOWN)
        assertEquals("SRF 4 News", ui.stream()!!.title)
        gesture(GestureKind.SCROLL_UP)
        gesture(GestureKind.SCROLL_UP)
        assertEquals("SRF 2 Kultur", ui.stream()!!.title)
        assertEquals("SRF 2 Kultur", page().textOf(MusikApp.SE_NAME))
        // A late word from a station left behind changes nothing.
        send(AppEvent.Player(PlayerState.ERROR, url, null, null, "alt"))
        assertEquals("Verbindet …", page().textOf(MusikApp.SE_STATE))

        // The start page leads back to it.
        back()
        back()
        assertEquals(MusikApp.P_START, page().id)
        assertEquals("SRF 2 Kultur – verbindet", page().textOf(MusikApp.ST_STATION))
        click(MusikApp.ST_STATION)
        assertEquals(MusikApp.P_SENDER, page().id)
    }

    @Test
    fun `the station chosen again is only shown, and radio off ends the stream`() {
        playSrf3()
        back()
        assertEquals("SRF 3 – verbindet", page().textOf(buttonFor("SRF 3")))
        click(buttonFor("SRF 3"))
        assertEquals(1, ui.playerActions.size)
        assertEquals(MusikApp.P_SENDER, page().id)
        assertEquals(listOf("Lauter", "Leiser", "Als Favorit merken", "Radio aus", "Senderliste"), ui.menu.map { it.text })
        menu(MusikApp.M_OFF)
        assertNull(ui.stream())
        assertEquals("Aus – Tippen spielt", page().textOf(MusikApp.SE_STATE))
        assertFalse(ui.menu.any { it.id == MusikApp.M_OFF })
        gesture(GestureKind.CLICK)
        assertEquals("SRF 3", ui.stream()!!.title)
        menu(MusikApp.M_LIST)
        assertEquals(listOf(MusikApp.P_START, MusikApp.P_RADIO), ui.pages.historyIds)
    }

    @Test
    fun `favorites come first in the list, and the app remembers them and the last station`() {
        playSrf3()
        menu(MusikApp.M_FAVORITE)
        assertEquals("SRF 3: bei den Favoriten", ui.toasts.last())
        assertTrue(ui.menu.any { it.text == "Favorit entfernen" })
        // Swiping now goes through the favorites, where SRF 3 is the only one.
        back()
        assertEquals("Favoriten", page().textOf(MusikApp.RA_FAVORITES))
        assertEquals("${MusikApp.RA_FAVORITE}0", buttonFor("SRF 3"))
        click("${MusikApp.RA_FAVORITE}0")
        gesture(GestureKind.SCROLL_DOWN)
        assertEquals("Keine weiteren Sender in dieser Liste", ui.toasts.last())

        // Another session of the app, with the same store.
        val again = FakeAppContext.forApp(app)
        again.storage.values.putAll(ui.storage.values)
        val other = MusikApp(clock = { now })
        other.onEvent(AppEvent.Start, again)
        assertEquals("SRF 3 hören", again.current!!.textOf(MusikApp.ST_LAST))
        assertEquals("SRF 3", again.page(MusikApp.P_RADIO).textOf("${MusikApp.RA_FAVORITE}0"))
        other.onEvent(AppEvent.Click(MusikApp.P_START, MusikApp.ST_LAST), again)
        assertEquals("SRF 3", again.stream()!!.title)
        assertEquals(MusikApp.P_SENDER, again.current!!.id)

        menu(MusikApp.M_FAVORITE)
        assertEquals("SRF 3: kein Favorit mehr", ui.toasts.last())
        assertEquals(0, (ui.storage.get("favoriten") as JsonArray).size)
    }

    @Test
    fun `stations are searched at radio-browser_info, and one found plays`() {
        start()
        click(MusikApp.ST_RADIO)
        click(MusikApp.RA_SEARCH)
        assertEquals(MusikApp.ASK_STATION, ui.question!!.first)
        send(AppEvent.TextInput(MusikApp.ASK_STATION, "jazz"))
        assertEquals(MusikApp.P_TREFFER, page().id)
        assertEquals("Suche „jazz“ …", page().textOf(MusikApp.TR_INFO))
        val search = requested("/json/stations/search")!!
        assertEquals("https://all.api.radio-browser.info/json/stations/search?name=jazz&hidebroken=true&order=clickcount&reverse=true&limit=60", search.url)
        answer("/json/stations/search", ok(RadioBrowserTest.JAZZ))
        assertEquals("2 Sender für „jazz“", page().textOf(MusikApp.TR_INFO))
        assertEquals("Radio Swiss Jazz · CH", page().textOf("${MusikApp.TR_STATION}0"))
        assertEquals("Jazz Radio · FR", page().textOf("${MusikApp.TR_STATION}1"))
        click("${MusikApp.TR_STATION}1")
        assertEquals(PlayerAction.Play("http://jazz-wr04.ice.infomaniak.ch/jazz-wr04-128.mp3", "Jazz Radio"), ui.stream())
        assertTrue(requested("/json/url/9617a958-0601-11e8-ae97-52543be04c81") != null)
        // Back on the results, the station chosen says so; the search is offered again.
        back()
        assertEquals("Jazz Radio – verbindet · FR", page().textOf("${MusikApp.TR_STATION}1"))
        click(MusikApp.TR_NEW)
        assertEquals(listOf("jazz"), ui.question!!.third)
    }

    @Test
    fun `a server that does not answer is passed over, and when none answers the search can be tried again`() {
        start()
        click(MusikApp.ST_RADIO)
        click(MusikApp.RA_SEARCH)
        send(AppEvent.TextInput(MusikApp.ASK_STATION, "SRF"))
        answer("all.api", HttpResult(0, "", "timeout"))
        answer("de1.api", ok(RadioBrowserTest.JAZZ))
        assertEquals("2 Sender für „SRF“", page().textOf(MusikApp.TR_INFO))
        // The server that answered is asked first from now on.
        click(MusikApp.TR_NEW)
        send(AppEvent.TextInput(MusikApp.ASK_STATION, "Energy"))
        answer("de1.api", HttpResult(503, ""))
        answer("de2.api", HttpResult(503, ""))
        answer("all.api", HttpResult(0, "", "Keine Verbindung"))
        assertEquals("Die Senderliste ist gerade nicht erreichbar. Ist die Uhr im Netz?", page().textOf(MusikApp.TR_INFO))
        assertTrue(ui.logs.any { "radio-browser.info" in it })
        click(MusikApp.TR_AGAIN)
        assertTrue(requested("name=Energy") != null)
        assertEquals("Suche „Energy“ …", page().textOf(MusikApp.TR_INFO))
    }

    @Test
    fun `a station whose stream fails is looked up at radio-browser_info and plays from where it is now`() {
        playSrf3()
        answer("/json/url/", ok())
        val url = ui.stream()!!.url
        send(AppEvent.Player(PlayerState.ERROR, url, null, null, "Der Sender ist unter dieser Adresse nicht zu finden"))
        assertEquals("Verbindet …", page().textOf(MusikApp.SE_STATE))
        answer("/json/stations/byuuid/961f1da4-0601-11e8-ae97-52543be04c81", ok(station("SRF 3", "https://stream.example.ch/srf3.mp3")))
        assertEquals(PlayerAction.Play("https://stream.example.ch/srf3.mp3", "SRF 3"), ui.stream())
        assertEquals("https://stream.example.ch/srf3.mp3", ((ui.storage.get("korrekturen") as JsonObject)[srf3().uuid!!] as kotlinx.serialization.json.JsonPrimitive).content)

        // That address fails as well: no second try, the reason shows.
        send(AppEvent.Player(PlayerState.ERROR, "https://stream.example.ch/srf3.mp3", null, null, "Keine Verbindung zum Sender"))
        assertEquals("Keine Verbindung zum Sender", page().textOf(MusikApp.SE_SONG))
        assertEquals("Tippen: nochmals versuchen", page().textOf(MusikApp.SE_STATE))
        assertNull(requested("/json/stations/byuuid/"))

        // Next time SRF 3 starts at the address that worked last; when radio-browser.info knows no better, it
        // goes back to the list's own.
        gesture(GestureKind.CLICK)
        assertEquals("https://stream.example.ch/srf3.mp3", ui.stream()!!.url)
        send(AppEvent.Player(PlayerState.ERROR, "https://stream.example.ch/srf3.mp3", null, null, "Keine Verbindung zum Sender"))
        answer("/json/stations/byuuid/", ok(station("SRF 3", "https://stream.example.ch/srf3.mp3")))
        assertEquals("Keine Verbindung zum Sender", page().textOf(MusikApp.SE_SONG))
        assertTrue((ui.storage.get("korrekturen") as JsonObject).isEmpty())
    }

    @Test
    fun `the time reads as on a player`() {
        assertEquals("0:00", MusikApp.time(0))
        assertEquals("4:31", MusikApp.time(271_999))
        assertEquals("1:02:03", MusikApp.time(3_723_000))
    }

    private fun station(name: String, url: String) =
        """[{"stationuuid":"961f1da4-0601-11e8-ae97-52543be04c81","name":"$name","url":"$url","url_resolved":"$url","countrycode":"CH","codec":"MP3","bitrate":128}]"""
}

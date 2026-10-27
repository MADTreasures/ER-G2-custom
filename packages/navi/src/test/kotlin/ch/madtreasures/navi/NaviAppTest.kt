package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.DownloadState
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Sensor
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.textOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The app with FakeAppContext (03 §7): the catalog of all countries, a download of Liechtenstein (the Vaduz map
 * stands in for what the server sends), the check and the search index, search, start and destination, the
 * route, the navigation with the GPS and as demo trip, and what goes wrong on the way.
 */
class NaviAppTest {
    private var now = 1_790_000_000_000L
    private val app = NaviApp(clock = { now })
    private val ui = FakeAppContext.forApp(app)

    /** The Städtle in Vaduz, by the town hall. */
    private val rathaus = LatLon(47.13942, 9.52216)

    private fun send(e: AppEvent) = app.onEvent(e, ui)

    private fun start() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    private fun page() = ui.current!!.id

    private fun text(block: String) = ui.current!!.textOf(block)

    private fun click(block: String) = send(AppEvent.Click(page(), block))

    private fun gesture(kind: GestureKind) = send(AppEvent.Gesture(kind, InputSource.RIGHT))

    /** Back as the host does it: the app hears of it, then the page goes. */
    private fun back() {
        send(AppEvent.Back(page()))
        ui.pages.back()
    }

    private fun backToMap() {
        while (page() != NaviApp.P_KARTE) back()
    }

    private fun buttons(): List<Block.Button> = ui.current!!.blocks.filterIsInstance<Block.Button>()

    private fun buttonFor(start: String): String =
        buttons().firstOrNull { it.text.startsWith(start) }?.id ?: throw AssertionError("no button \"$start\" in ${buttons().map { it.text }}")

    private fun picture(pageId: String, block: String): String? = (ui.page(pageId).block(block) as Block.Image).src

    private fun fix(p: LatLon, acc: Float = 5f, speed: Float? = null, bearing: Float? = null) =
        send(AppEvent.Location(p.lat, p.lon, acc, now, speed, bearing))

    private val dir: File get() = ui.filesDir

    /** Loads "Liechtenstein" through the pages; the Vaduz map is what the server sends. */
    private fun install(listing: String = LISTING) {
        gesture(GestureKind.CLICK)
        click("m_karten")
        click("k_neu")
        click(buttonFor("Europa"))
        ui.answer(HttpResult(200, listing))
        click(buttonFor("Liechtenstein"))
        assertEquals(NaviApp.P_LAND, page())
        click("d_laden")
        val d = ui.downloads.single()
        assertEquals("https://download.mapsforge.org/maps/v5/europe/liechtenstein.map", d.url)
        assertEquals("maps/europe_liechtenstein.map", d.name)
        assertTrue(d.wifiOnly)
        send(AppEvent.Download(d.tag, DownloadState.RUNNING, 1L shl 20, 2_261_314))
        assertEquals("Liechtenstein: 1,0 MB von 2,2 MB", text("d_fort"))
        send(ui.finishDownload(d.tag, Fixtures.vaduz.readBytes()))
    }

    /** The share of lit pixels of a picture block's PNG. */
    private fun litShare(src: String): Double {
        val png = Base64.getDecoder().decode(src.substringAfter("base64,"))
        val image = ImageIO.read(ByteArrayInputStream(png))
        var lit = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and 0xFF > 40) lit++
        return lit.toDouble() / (image.width * image.height)
    }

    /** The search result the app will have found for [query], from the index it built. */
    private fun place(query: String): Place = SearchIndex(File(dir, "index/europe_liechtenstein.idx")).use { it.search(query, rathaus).first() }

    @Test
    fun `without maps the map says how to get one, and the search asks for a map first`() {
        start()
        assertEquals(NaviApp.P_KARTE, page())
        assertEquals(InputMode.GESTURES, ui.current!!.input)
        assertFalse(ui.current!!.statusBar)
        assertTrue(Sensor.LOCATION in ui.subscriptions)
        assertTrue(picture(NaviApp.P_KARTE, NaviApp.B_KARTE)!!.startsWith("data:image/png;base64,"))
        assertEquals(listOf("Ziel suchen", "Karten"), ui.menu.map { it.text })
        gesture(GestureKind.CLICK)
        assertEquals(NaviApp.P_MENU, page())
        assertEquals("GPS: warte auf den Standort …", text("m_gps"))
        click("m_ziel")
        assertEquals(NaviApp.ASK_TO, ui.question!!.first)
        send(AppEvent.TextInput(NaviApp.ASK_TO, "Bahnhof"))
        assertEquals("Zuerst eine Karte laden: Menü → Karten", ui.toasts.last())
        // The GPS shows in the menu as it comes.
        fix(rathaus, acc = 7f)
        assertEquals("GPS: ±7 m", text("m_gps"))
    }

    @Test
    fun `every country of the catalog can be chosen, big ones also by region`() {
        start()
        gesture(GestureKind.CLICK)
        click("m_karten")
        assertEquals(NaviApp.P_KARTEN, page())
        click("k_neu")
        assertEquals(
            listOf("Afrika", "Asien", "Australien und Ozeanien", "Europa", "Mittelamerika", "Nordamerika", "Russland", "Südamerika", "Welt"),
            buttons().map { it.text },
        )
        click(buttonFor("Europa"))
        assertEquals(NaviApp.P_LAENDER, page())
        assertTrue(buttons().any { it.text == "Schweiz · 319 MB" })
        assertTrue(buttons().size > 40)
        // The newest dates come from the server's folder listing.
        assertEquals("https://download.mapsforge.org/maps/v5/europe/", ui.requests.single().first.url)
        ui.answer(HttpResult(200, LISTING))
        click(buttonFor("Deutschland"))
        assertEquals(NaviApp.P_REGIONEN, page())
        assertEquals("Deutschland", text("s_titel"))
        assertEquals("Ganz Deutschland · 3,0 GB", buttons().first().text)
        assertTrue(buttons().map { it.text }.toString(), buttons().any { it.text == "Bayern · 551 MB" })
        click(buttonFor("Bayern"))
        assertEquals(NaviApp.P_LAND, page())
        assertEquals("Bayern", text("d_titel"))
        assertEquals("Laden · 551 MB · WLAN", text("d_laden"))
        back()
        back()
        assertEquals(NaviApp.P_LAENDER, page())
        click(buttonFor("Schweiz"))
        assertEquals("Karte 319 MB · Stand 23.09.2026", text("d_info"))
        backToMap()
        // A country that the server only has in parts: a folder of its regions.
        gesture(GestureKind.CLICK)
        click("m_karten")
        click("k_neu")
        click(buttonFor("Asien"))
        click(buttonFor("China ›"))
        assertEquals(NaviApp.P_REGIONEN, page())
        assertFalse(buttons().first().text.startsWith("Ganz"))
        assertTrue(buttons().size > 20)
    }

    @Test
    fun `a country loads over WLAN, is checked, gets its search index and shows on the map`() {
        start()
        val empty = picture(NaviApp.P_KARTE, NaviApp.B_KARTE)!!
        install()
        assertEquals("Liechtenstein ist bereit", ui.toasts.last())
        assertEquals(Vibration.DOUBLE, ui.vibrations.last())
        assertTrue(File(dir, "maps/europe_liechtenstein.map").isFile)
        assertTrue(File(dir, "index/europe_liechtenstein.idx").isFile)
        // Installed: the page offers deleting, and the update the listing shows (newer by more than a few days).
        assertEquals(NaviApp.P_LAND, page())
        assertEquals("Löschen", text("d_loeschen"))
        assertEquals("Aktualisieren · 2,2 MB", text("d_laden"))
        assertTrue(text("d_info"), text("d_info").contains("Auf der Uhr: Stand "))
        back()
        assertEquals("Liechtenstein · auf der Uhr", buttons().first { it.text.startsWith("Liechtenstein") }.text)
        backToMap()
        // The map now shows streets around where the wearer is.
        fix(rathaus)
        val map = picture(NaviApp.P_KARTE, NaviApp.B_KARTE)!!
        assertNotEquals(empty, map)
        assertTrue(litShare(map) > 0.03)
        // The maps page lists it with its update.
        gesture(GestureKind.CLICK)
        click("m_karten")
        assertTrue(buttons().map { it.text }.toString(), buttons().any { it.text.startsWith("Liechtenstein · 0,1 MB · neuer Stand") })
    }

    @Test
    fun `the search index is built in the background and shows its progress`() {
        ui.queueWork = true
        start()
        ui.runWork()
        gesture(GestureKind.CLICK)
        click("m_karten")
        click("k_neu")
        click(buttonFor("Europa"))
        click(buttonFor("Liechtenstein"))
        click("d_laden")
        val d = ui.downloads.single()
        send(ui.finishDownload(d.tag, Fixtures.vaduz.readBytes()))
        assertEquals("Liechtenstein: wird geprüft …", text("d_fort"))
        // The check, then opening the map and building the index, one task after another.
        ui.runWork()
        assertEquals(NaviApp.P_LAND, page())
        assertEquals("Löschen", text("d_loeschen"))
        assertEquals("Liechtenstein ist bereit", ui.toasts.last())
        assertTrue(NaviStore(ui.storage).installed().single().indexed)
    }

    @Test
    fun `search a destination, see the route, and walk there with the GPS`() {
        start()
        install()
        backToMap()
        fix(rathaus)
        gesture(GestureKind.CLICK)
        click("m_ziel")
        send(AppEvent.TextInput(NaviApp.ASK_TO, "Äulestrasse"))
        assertEquals(NaviApp.P_TREFFER, page())
        val first = buttons().first()
        assertTrue(first.text, first.text.startsWith("Äulestrasse · Vaduz · "))
        click(first.id)
        assertEquals(NaviApp.P_ERGEBNIS, page())
        assertTrue(text("e_info"), text("e_info").endsWith(" · Zu Fuss"))
        assertEquals("Mein Standort → Äulestrasse · Vaduz", text("e_wo"))
        assertTrue(litShare(picture(NaviApp.P_ERGEBNIS, NaviApp.B_UEBERSICHT)!!) > 0.02)
        // Back on the map the route waits in the menu.
        back()
        gesture(GestureKind.CLICK)
        assertTrue(text("m_weiter"), text("m_weiter").startsWith("Route: "))
        click("m_weiter")
        assertEquals(NaviApp.P_ERGEBNIS, page())
        click("e_los")
        assertEquals(NaviApp.P_NAV, page())
        assertEquals(listOf(NaviApp.P_KARTE, NaviApp.P_NAV), ui.pages.historyIds)
        assertEquals(listOf("Navigation beenden", "Route neu berechnen"), ui.menu.map { it.text })
        val first0 = picture(NaviApp.P_NAV, NaviApp.B_NAV)!!
        // Along the route as the app's own router finds it: a tick before each turn, a long one at the end.
        val route = MapSource(Fixtures.vaduz).use { Router(listOf(it)).route(rathaus, place("Äulestrasse").position, Profile.FOOT) }
        val walk = DemoTrip(route, now)
        val turns = route.maneuvers.count { it.turn != Turn.START && it.turn != Turn.ARRIVE }
        var t = 0L
        while (!walk.finished(t)) {
            t += 1_000
            now += 1_000
            send(walk.fixAt(t))
        }
        send(walk.fixAt(t))
        assertNotEquals(first0, picture(NaviApp.P_NAV, NaviApp.B_NAV))
        assertEquals(Vibration.LONG, ui.vibrations.last())
        assertTrue("$turns turns, ${ui.vibrations}", ui.vibrations.count { it == Vibration.TICK } in minOf(1, turns)..turns)
        // A tap shows the whole map.
        val mini = picture(NaviApp.P_NAV, NaviApp.B_NAV)
        gesture(GestureKind.CLICK)
        assertNotEquals(mini, picture(NaviApp.P_NAV, NaviApp.B_NAV))
        // Back to the map: the navigation goes on and is in the app menu; ending it leaves the map alone.
        back()
        assertEquals(NaviApp.P_KARTE, page())
        assertEquals("Navigation", ui.menu.first().text)
        send(AppEvent.Menu(NaviApp.M_NAV))
        assertEquals(NaviApp.P_NAV, page())
        send(AppEvent.Menu(NaviApp.M_STOP))
        assertEquals(listOf(NaviApp.P_KARTE), ui.pages.historyIds)
        assertEquals(listOf("Ziel suchen", "Karten"), ui.menu.map { it.text })
    }

    @Test
    fun `off the route a new one is found from where the wearer is`() {
        start()
        install()
        backToMap()
        fix(rathaus)
        gesture(GestureKind.CLICK)
        click("m_ziel")
        send(AppEvent.TextInput(NaviApp.ASK_TO, "Äulestrasse"))
        click(buttons().first().id)
        click("e_los")
        ui.vibrations.clear()
        // Two fixes far from the route, on another street of Vaduz.
        val elsewhere = LatLon(47.1440, 9.5300)
        fix(elsewhere)
        now += 1_000
        fix(elsewhere)
        assertEquals(listOf(Vibration.DOUBLE), ui.vibrations.filter { it == Vibration.DOUBLE })
        // The wearer asks again: a new route at once.
        send(AppEvent.Menu(NaviApp.M_REROUTE))
        assertEquals(2, ui.vibrations.count { it == Vibration.DOUBLE })
    }

    @Test
    fun `start and destination are both chosen, then the route goes between them without GPS`() {
        start()
        install()
        backToMap()
        gesture(GestureKind.CLICK)
        click("m_route")
        assertEquals(NaviApp.P_ROUTE, page())
        assertEquals("Von: Mein Standort", text("r_von"))
        click("r_von")
        assertEquals(NaviApp.ASK_FROM, ui.question!!.first)
        assertEquals("Mein Standort", ui.question!!.third.first())
        send(AppEvent.TextInput(NaviApp.ASK_FROM, "Städtle"))
        assertEquals(NaviApp.P_TREFFER, page())
        assertTrue(text("t_frage"), text("t_frage").startsWith("Start: "))
        click(buttonFor("Städtle · Vaduz"))
        assertEquals(NaviApp.P_ROUTE, page())
        assertEquals("Von: Städtle · Vaduz", text("r_von"))
        click("r_velo")
        send(AppEvent.Toggle(page(), "r_velo", true))
        click("r_nach")
        send(AppEvent.TextInput(NaviApp.ASK_TO, "Äulestrasse"))
        click(buttonFor("Äulestrasse · Vaduz"))
        assertEquals(NaviApp.P_ERGEBNIS, page())
        assertEquals("Städtle · Vaduz → Äulestrasse · Vaduz", text("e_wo"))
        assertTrue(text("e_info"), text("e_info").endsWith(" · Velo"))
        // The destination is remembered: it is offered next time.
        back()
        gesture(GestureKind.CLICK)
        click("m_ziel")
        assertEquals("Äulestrasse · Vaduz", ui.question!!.third.first())
        assertEquals(Profile.BIKE, NaviStore(ui.storage).profile)
    }

    @Test
    fun `the demo trip drives the navigation by itself and the GPS waits meanwhile`() {
        start()
        install()
        backToMap()
        fix(rathaus)
        gesture(GestureKind.CLICK)
        click("m_ziel")
        send(AppEvent.TextInput(NaviApp.ASK_TO, "Äulestrasse"))
        click(buttons().first().id)
        click("e_demo")
        assertEquals(NaviApp.P_NAV, page())
        assertTrue(NaviApp.T_DEMO in ui.timers)
        assertEquals("Demo beenden", ui.menu.last().text)
        // A GPS fix elsewhere is not taken while the demo runs: no new route.
        fix(LatLon(47.1440, 9.5300))
        fix(LatLon(47.1440, 9.5300))
        assertFalse(Vibration.DOUBLE in ui.vibrations.drop(1))
        var steps = 0
        while (NaviApp.T_DEMO in ui.timers && steps++ < 3_000) {
            now += 1_000
            send(AppEvent.Timer(NaviApp.T_DEMO))
        }
        assertFalse(NaviApp.T_DEMO in ui.timers)
        assertEquals(Vibration.LONG, ui.vibrations.last())
    }

    @Test
    fun `too little room, a broken connection, a cancelled and a refused download`() {
        start()
        gesture(GestureKind.CLICK)
        click("m_karten")
        click("k_neu")
        click(buttonFor("Europa"))
        click(buttonFor("Liechtenstein"))
        ui.freeBytes = 100L shl 20
        click("d_laden")
        assertTrue(ui.downloads.isEmpty())
        assertEquals("Zu wenig Platz: braucht 502 MB, frei sind 100 MB", ui.toasts.last())
        ui.freeBytes = 20L shl 30
        click("d_laden")
        val first = ui.downloads.single()
        send(AppEvent.Download(first.tag, DownloadState.FAILED, 500_000, 2_261_314, "Verbindung unterbrochen (timeout); nochmals laden setzt fort"))
        assertEquals("Liechtenstein: Verbindung unterbrochen (timeout); nochmals laden setzt fort", text("d_fort"))
        assertEquals("Nochmals versuchen", text("d_laden"))
        click("d_laden")
        val second = ui.downloads.last()
        assertNotEquals(first.tag, second.tag)
        click("d_stopp")
        assertEquals(listOf(second.tag), ui.cancelledDownloads)
        send(AppEvent.Download(second.tag, DownloadState.CANCELLED, 600_000, 2_261_314))
        // A part stays and is offered to go on with.
        File(dir, "maps/europe_liechtenstein.map.part").apply { parentFile.mkdirs() }.writeBytes(ByteArray(600_000))
        back()
        click(buttonFor("Liechtenstein"))
        assertEquals("Weiter laden · 0,6 MB von 2,2 MB · WLAN", text("d_laden"))
        // A download the watch never reports on was refused (no permission for the internet).
        click("d_laden")
        send(AppEvent.Timer(NaviApp.T_SILENT))
        assertTrue(text("d_fort"), text("d_fort").contains("Internet"))
    }

    @Test
    fun `over LTE when allowed`() {
        start()
        gesture(GestureKind.CLICK)
        click("m_karten")
        send(AppEvent.Toggle(page(), "k_lte", true))
        click("k_neu")
        click(buttonFor("Europa"))
        click(buttonFor("Liechtenstein"))
        assertEquals("Laden · 2,2 MB", text("d_laden"))
        click("d_laden")
        assertFalse(ui.downloads.single().wifiOnly)
    }

    @Test
    fun `an installed map is deleted after asking once more`() {
        start()
        install()
        click("d_loeschen")
        assertEquals("Wirklich löschen?", text("d_loeschen"))
        click("d_loeschen")
        assertEquals("Liechtenstein gelöscht", ui.toasts.last())
        assertTrue(NaviStore(ui.storage).installed().isEmpty())
        assertFalse(File(dir, "maps/europe_liechtenstein.map").exists())
        assertFalse(File(dir, "index/europe_liechtenstein.idx").exists())
        assertEquals("Laden · 2,2 MB · WLAN", text("d_laden"))
    }

    @Test
    fun `an update keeps the search working and builds a new index`() {
        start()
        install()
        click("d_laden")
        val update = ui.downloads.last()
        assertEquals("maps/europe_liechtenstein.map", update.name)
        send(ui.finishDownload(update.tag, Fixtures.vaduz.readBytes()))
        assertEquals(2, ui.toasts.count { it == "Liechtenstein ist bereit" })
        assertTrue(NaviStore(ui.storage).installed().single().indexed)
    }

    @Test
    fun `the map for where the wearer stands is offered`() {
        start()
        fix(LatLon(47.3769, 8.5417))
        gesture(GestureKind.CLICK)
        click("m_karten")
        assertEquals("Hier: Schweiz · 319 MB", text("k_hier0"))
        click("k_hier0")
        assertEquals(NaviApp.P_LAND, page())
        assertEquals("Schweiz", text("d_titel"))
    }

    @Test
    fun `scrolling zooms the map, the zoom stays within bounds`() {
        start()
        install()
        backToMap()
        fix(rathaus)
        val before = picture(NaviApp.P_KARTE, NaviApp.B_KARTE)
        gesture(GestureKind.SCROLL_DOWN)
        val out = picture(NaviApp.P_KARTE, NaviApp.B_KARTE)
        assertNotEquals(before, out)
        repeat(30) { gesture(GestureKind.SCROLL_UP) }
        assertNotNull(picture(NaviApp.P_KARTE, NaviApp.B_KARTE))
    }

    @Test
    fun `sizes and dates as the wearer reads them`() {
        assertEquals("2,2 MB", NaviApp.size(2_261_314))
        assertEquals("319 MB", NaviApp.size(334_266_727))
        assertEquals("3,0 GB", NaviApp.size(3_233_325_182))
        assertEquals("23.09.2026", NaviApp.date("2026-09-23"))
        assertEquals("2026-09-23", NaviApp.day(1_790_121_600_000L))
    }

    companion object {
        /** Rows of the server's folder listing of europe/ (as on 2026-10-27), Liechtenstein made newer. */
        val LISTING = """
            <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="liechtenstein.map">liechtenstein.map</a>      </td><td align="right">2026-10-20 12:15  </td><td align="right">2.2M</td><td>&nbsp;</td></tr>
            <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="switzerland.map">switzerland.map</a>        </td><td align="right">2026-09-23 12:53  </td><td align="right">319M</td><td>&nbsp;</td></tr>
        """.trimIndent()
    }
}

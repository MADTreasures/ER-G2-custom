package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.assetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog of all maps the package ships, the server's folder listings, and what the app keeps. */
class CatalogTest {
    private val catalog = Catalog.parse(assetFile(NaviApp.CATALOG).readText())

    @Test
    fun `the catalog holds every continent and country of the server, big countries also in regions`() {
        assertEquals(423, catalog.regions.size)
        assertEquals(9, catalog.groups.size)
        assertEquals("https://download.mapsforge.org/maps/v5/", catalog.mapBase)
        val ch = catalog.region("europe/switzerland")!!
        assertEquals("Schweiz", ch.name)
        assertEquals(334_266_727L, ch.bytes)
        assertEquals("https://download.mapsforge.org/maps/v5/europe/switzerland.map", catalog.url(ch))
        assertEquals("europe_switzerland", ch.file)
        // Germany as a whole and in its states; China only in provinces (a folder).
        assertTrue(catalog.children("europe/germany").any { it.name == "Bayern" })
        assertTrue(catalog.region("asia/china")!!.isFolder)
        assertTrue(catalog.children("asia/china").size > 20)
        // Every country of every continent is there, with a size and a box.
        for (g in catalog.groups) assertTrue(g.name, catalog.countries(g.id).isNotEmpty())
        for (r in catalog.regions.filter { !it.isFolder }) {
            assertTrue(r.id, (r.bytes ?: 0) > 0)
            assertTrue(r.id, r.box.maxLat > r.box.minLat && r.box.maxLon > r.box.minLon)
        }
    }

    @Test
    fun `the maps that hold a place, smallest first`() {
        val zurich = catalog.holding(LatLon(47.3769, 8.5417))
        assertEquals("europe/switzerland", zurich.first().id)
        assertTrue(zurich.none { it.group == "world" })
        assertEquals("europe/liechtenstein", catalog.holding(LatLon(47.1410, 9.5215)).first().id)
        // Out on the Atlantic no map reaches.
        assertTrue(catalog.holding(LatLon(30.0, -40.0)).isEmpty())
    }

    @Test
    fun `the folder listing gives each map's newest date in UTC`() {
        val rows = Listing.parse(NaviAppTest.LISTING)
        assertEquals(Listed("2026-10-20", "2.2M"), rows["liechtenstein"])
        // 12:53 on the server (UTC+8) is 04:53 UTC, the same day.
        assertEquals("2026-09-23", rows["switzerland"]!!.date)
        val early = """<tr><td><a href="iceland.map">iceland.map</a> </td><td align="right">2026-10-02 03:10  </td><td align="right">57M</td></tr>"""
        assertEquals("2026-10-01", Listing.parse(early)["iceland"]!!.date)
        assertEquals("https://download.mapsforge.org/maps/v5/europe/germany/", Listing.folderOf(catalog, catalog.region("europe/germany/bayern")!!))
    }

    @Test
    fun `the store keeps maps, profile, LTE, position and the last five destinations`() {
        val ui = FakeAppContext()
        val store = NaviStore(ui.storage)
        assertTrue(store.installed().isEmpty())
        store.put(Installed("europe/switzerland", "Schweiz", 334_266_727, "2026-09-23", indexed = false))
        store.put(Installed("europe/liechtenstein", "Liechtenstein", 2_261_314, "2026-09-11", indexed = true))
        store.put(Installed("europe/switzerland", "Schweiz", 334_266_727, "2026-09-23", indexed = true))
        assertEquals(listOf("Liechtenstein", "Schweiz"), store.installed().map { it.name })
        assertTrue(store.installed().all { it.indexed })
        store.remove("europe/liechtenstein")
        assertEquals(listOf("europe/switzerland"), store.installed().map { it.id })
        assertEquals(Profile.FOOT, store.profile)
        store.profile = Profile.BIKE
        assertEquals(Profile.BIKE, NaviStore(ui.storage).profile)
        store.lte = true
        assertTrue(store.lte)
        assertNull(store.lastPosition)
        store.lastPosition = LatLon(47.5, 8.5)
        assertEquals(LatLon(47.5, 8.5), store.lastPosition)
        for (i in 1..7) store.remember(Place("Ziel $i", "Zürich", 47.0 + i / 100.0, 8.5, Kind.POI))
        assertEquals((7 downTo 3).map { "Ziel $it · Zürich" }, store.recent().map { it.label })
        // Again: to the front, not twice.
        store.remember(Place("Ziel 5", "Zürich", 47.05, 8.5, Kind.POI))
        assertEquals(listOf("Ziel 5", "Ziel 7", "Ziel 6", "Ziel 4", "Ziel 3"), store.recent().map { it.name })
    }

    @Test
    fun `events return quickly, the heavy work waits for the background`() {
        val ui = FakeAppContext.forApp(NaviApp()).apply { queueWork = true }
        val app = NaviApp()
        val times = ArrayList<Pair<String, Long>>()
        fun send(e: AppEvent) {
            val t = System.nanoTime()
            app.onEvent(e, ui)
            times += e.toString().take(40) to (System.nanoTime() - t) / 1_000_000
        }
        // Warm up the classes once, as the watch has them loaded after the first start.
        NaviApp().onEvent(AppEvent.Start, FakeAppContext.forApp(NaviApp()).apply { queueWork = true })
        send(AppEvent.Start)
        send(AppEvent.Visible)
        ui.runWork()
        for (i in 0 until 20) send(AppEvent.Location(47.3769 + i * 1e-5, 8.5417, 5f, i * 1_000L))
        ui.runWork()
        println(times.sortedByDescending { it.second }.take(5))
        // The host allows 50 ms; on the JVM here an event takes a few.
        assertTrue(times.toString(), times.all { it.second < 25 })
    }
}

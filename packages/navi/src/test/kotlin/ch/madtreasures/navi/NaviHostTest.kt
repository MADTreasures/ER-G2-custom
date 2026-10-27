package ch.madtreasures.navi

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.DownloadState
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.HttpResult
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Vibration
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.FakePorts
import ch.madtreasures.g2watch.apps.host.FakeScreen
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.render.FocusTarget
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app in the real app host of the watch app (fakes for the screen, the GPS and the downloads, virtual
 * time, the background work run by the test): the temples work its pages, the GPS reaches it, a map loads,
 * is checked and gets its search index in the background, and the host refuses none of its commands.
 */
class NaviHostTest {
    private val scheduler = FakeScheduler()
    private val ports = FakePorts()
    private val host = AppHost(
        scheduler, FakeScreen(), FakeText(), ports,
        listOf({ NaviApp(clock = { scheduler.now }) }),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    /** Runs the app thread and the background work until both are idle. */
    private fun settle() {
        repeat(50) {
            scheduler.runPending()
            scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
            if (ports.work.isEmpty()) return
            ports.runWork()
        }
    }

    private fun temple(kind: GestureKind) {
        host.gesture(Gesture(kind, InputSource.RIGHT))
        settle()
    }

    private fun page() = host.currentPageOf(NaviApp.ID)!!

    private fun picture(block: String) = (page().block(block) as Block.Image).src

    /** Moves the focus along the page with the temple until [block] has it, then clicks it. */
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

    /** The button on this page whose text starts with [text]. */
    private fun buttonFor(text: String): String =
        page().blocks.filterIsInstance<Block.Button>().first { it.text.startsWith(text) }.id

    private fun launch() {
        NaviApp().manifest.let { ports.answers["${it.id}@${it.version}"] = it.permissions }
        host.launch("watch:${NaviApp.ID}")
        settle()
        assertEquals(NaviApp.ID, host.state.value.visible)
        assertEquals(NaviApp.P_KARTE, host.state.value.page)
    }

    private fun noComplaints() =
        assertTrue(ports.logs.toString(), ports.logs.none { "abgelehnt" in it || "dauerte" in it || "abgestürzt" in it || "nicht gezeichnet" in it })

    @Test
    fun `the map takes gestures, the GPS reaches it, and a country loads with its index in the background`() {
        launch()
        assertEquals(InputMode.GESTURES, host.inputMode.value)
        assertTrue(picture(NaviApp.B_KARTE)!!.startsWith("data:image/png;base64,"))
        // The watch's GPS every second while the app is on the glasses.
        val line = ports.location.lines.single()
        assertEquals(1_000L, line.intervalMs)

        // A tap opens the menu, with the pointer; the temple goes to "Karten" and on to the catalog.
        temple(GestureKind.CLICK)
        assertEquals(NaviApp.P_MENU, host.state.value.page)
        assertEquals(InputMode.POINTER, host.inputMode.value)
        press("m_karten")
        assertEquals(NaviApp.P_KARTEN, host.state.value.page)
        press("k_neu")
        press(buttonFor("Europa"))
        assertEquals(NaviApp.P_LAENDER, host.state.value.page)
        ports.requests.single().second(HttpResult(200, NaviAppTest.LISTING))
        settle()
        press(buttonFor("Liechtenstein"))
        assertEquals(NaviApp.P_LAND, host.state.value.page)
        press("d_laden")

        // The host loads it (here: the test writes the Vaduz map where the file goes) and reports.
        val download = ports.downloads.started.single()
        assertEquals("https://download.mapsforge.org/maps/v5/europe/liechtenstein.map", download.request.url)
        assertTrue(download.request.wifiOnly)
        download.listener.onProgress(1L shl 20, 2_261_314)
        settle()
        val target: File = download.request.target
        target.parentFile.mkdirs()
        Fixtures.vaduz.copyTo(target, overwrite = true)
        download.listener.onEnd(DownloadState.DONE, target.length(), target.length(), null)
        settle()
        assertEquals(Vibration.DOUBLE, ports.vibrations.last())
        val maps = ports.storage(NaviApp.ID).get("karten") as JsonArray
        assertEquals(1, maps.size)
        assertTrue(maps.toString(), maps.toString().contains("\"indexed\":true"))
        assertTrue(File(target.parentFile.parentFile, "index/europe_liechtenstein.idx").isFile)

        // Back to the map; the position draws the streets of Vaduz.
        while (host.state.value.page != NaviApp.P_KARTE) {
            host.back()
            settle()
        }
        val before = picture(NaviApp.B_KARTE)
        line.fix(47.13942, 9.52216, acc = 6f)
        settle()
        assertNotEquals(before, picture(NaviApp.B_KARTE))
        noComplaints()
    }

    @Test
    fun `a destination typed on the watch gives the route and the navigation`() {
        // A map on the watch already, as after a download.
        val dir = ports.files(NaviApp.ID)!!
        Fixtures.vaduz.copyTo(File(dir, "maps/europe_liechtenstein.map"), overwrite = true)
        MapFile(Fixtures.vaduz).use { SearchIndexBuilder.build(it, File(dir, "index/europe_liechtenstein.idx")) }
        NaviStore(ports.storage(NaviApp.ID)).put(Installed("europe/liechtenstein", "Liechtenstein", Fixtures.vaduz.length(), "2026-09-11", indexed = true))
        launch()
        val line = ports.location.lines.single()
        line.fix(47.13942, 9.52216)
        settle()
        temple(GestureKind.CLICK)
        press("m_ziel")
        // The watch asks; the wearer types the destination.
        val (prompt, _, answer) = ports.questions.last()
        assertEquals("Wohin?", prompt)
        answer("Äulestrasse")
        settle()
        assertEquals(NaviApp.P_TREFFER, host.state.value.page)
        press(buttonFor("Äulestrasse · Vaduz"))
        assertEquals(NaviApp.P_ERGEBNIS, host.state.value.page)
        assertTrue(picture(NaviApp.B_UEBERSICHT)!!.startsWith("data:image/png;base64,"))
        press("e_los")
        assertEquals(NaviApp.P_NAV, host.state.value.page)
        assertEquals(InputMode.GESTURES, host.inputMode.value)
        val first = picture(NaviApp.B_NAV)
        // A tap: the whole map instead of the small one.
        temple(GestureKind.CLICK)
        assertNotEquals(first, picture(NaviApp.B_NAV))
        // A double tap on the temple is back: the map, the navigation goes on.
        temple(GestureKind.DOUBLE_CLICK)
        assertEquals(NaviApp.P_KARTE, host.state.value.page)
        noComplaints()
    }
}

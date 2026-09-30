package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.VideoAction
import ch.madtreasures.g2watch.apps.VideoItem
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text from the watch, videos and per-page input in the app host (03 §10), with fakes and virtual time. */
class AppHostVideoTest {
    private val scheduler = FakeScheduler()
    private val screen = FakeScreen()
    private val ports = FakePorts()
    private val text = FakeText()
    private val created = mutableListOf<ScriptedApp>()

    private val list = Page("liste", "Liste", listOf(Block.Button("zum_video", "Video", target = "video"), Block.Button("frage", "Frage")))
    private val video = Page(
        "video", "Video",
        listOf(Block.Image("bild", null, 96, 54), Block.Text("status", "")),
        statusBar = false,
        input = InputMode.GESTURES,
    )

    /** Shows the list; the test drives the rest through the app's context. */
    private var context: AppContext? = null

    private val pages: ScriptedApp.(AppEvent, AppContext) -> Unit = { event, ui ->
        context = ui
        if (event == AppEvent.Start) {
            ui.definePages(listOf(list, video))
            ui.show("liste")
        }
    }

    private fun app(id: String = "ch.test.video", permissions: Set<Permission> = setOf(Permission.NETWORK)): () -> G2App = {
        ScriptedApp(AppManifest(id, id.substringAfterLast('.'), "1.0.0", permissions = permissions), pages).also { created += it }
    }

    private fun host(vararg apps: () -> G2App) = AppHost(
        scheduler, screen, text, ports, apps.toList(),
        nowMs = { scheduler.now },
        nanoTime = { scheduler.now * 1_000_000L },
    )

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun AppHost.start(id: String = "ch.test.video"): ScriptedApp {
        ports.answers["$id@1.0.0"] = setOf(Permission.NETWORK)
        launch("watch:$id")
        settle()
        return created.last()
    }

    /** Runs [block] on the app thread with the app's context, as the app would from an event. */
    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    private val player get() = ports.video.players.last()

    private fun ScriptedApp.videoEvents() = events.filterIsInstance<AppEvent.Video>()

    @Test
    fun `a page with its own input mode switches the watch to gestures and back`() {
        val host = host(app())
        val app = host.start()
        assertEquals(InputMode.POINTER, host.inputMode.value)
        asApp { show("video") }
        assertEquals(InputMode.GESTURES, host.inputMode.value)
        // A tap is the app's now, not a click on a focused button.
        host.gesture(Gesture(GestureKind.CLICK, InputSource.RIGHT))
        settle()
        assertEquals(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT), app.events.last())
        // Double tap on the temple still means back, and the list takes the pointer again.
        host.gesture(Gesture(GestureKind.DOUBLE_CLICK, InputSource.RIGHT))
        settle()
        assertEquals("liste", host.state.value.page)
        assertEquals(InputMode.POINTER, host.inputMode.value)
    }

    @Test
    fun `text from the watch comes back as an event, with a hint on the glasses`() {
        val host = host(app())
        val app = host.start()
        asApp { askText("suche", "Wonach suchen?", listOf("Katzen", "Musik")) }
        val (prompt, suggestions, answer) = ports.questions.single()
        assertEquals("Wonach suchen?", prompt)
        assertEquals(listOf("Katzen", "Musik"), suggestions)
        assertTrue(ports.vibrations.isNotEmpty())
        settle()
        val hintShown = screen.last.pixels.copyOf()
        answer("  Katzen im Schnee ")
        settle()
        assertEquals(AppEvent.TextInput("suche", "Katzen im Schnee"), app.events.last())
        // The hint on the glasses goes with the answer.
        assertTrue(!hintShown.contentEquals(screen.last.pixels))
        // An answer arrives once; a late second one is dropped.
        answer("nochmal")
        settle()
        assertEquals(1, app.events.count { it is AppEvent.TextInput })
    }

    @Test
    fun `cancelled, replaced or orphaned questions`() {
        val host = host(app())
        val app = host.start()
        asApp { askText("eins", "Eins?") }
        ports.questions.last().third(null)
        settle()
        assertEquals(AppEvent.TextInput("eins", null), app.events.last())

        asApp { askText("zwei", "Zwei?") }
        asApp { askText("drei", "Drei?") }
        assertEquals(AppEvent.TextInput("zwei", null), app.events.last())
        // The replaced question's answer no longer counts.
        ports.questions[1].third("zu spät")
        settle()
        assertEquals(AppEvent.TextInput("zwei", null), app.events.last())

        // The app ends with a question still open: the watch takes it back.
        asApp { close() }
        assertEquals(1, ports.textCancels)
    }

    @Test
    fun `questions beyond the limits are refused and logged`() {
        val host = host(app())
        host.start()
        asApp { askText("t", "?", List(6) { "v$it" }) }
        asApp { askText("", "?") }
        assertTrue(ports.questions.isEmpty())
        assertEquals(2, ports.logs.count { it.contains("askText") })
    }

    @Test
    fun `a video writes its pictures into the image block and reports its state`() {
        val host = host(app())
        val app = host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("https://www.youtube.com/watch?v=abc", VideoProfile.FAST, sound = true, startMs = 7_000))
        }
        val request = player.request
        assertEquals(96, request.width)
        assertEquals(54, request.height)
        assertEquals(VideoProfile.FAST, request.profile)
        assertTrue(request.sound)
        assertEquals(7_000, request.startMs)

        player.state(VideoState.PLAYING, 7_000, 60_000)
        val picture = GrayRaster(96, 54).also { it.fillRect(0, 0, 96, 54, 200) }
        player.listener.onFrame(picture)
        settle()
        assertEquals(AppEvent.Video("bild", VideoState.PLAYING, 7_000, 60_000), app.videoEvents().last())
        // The picture is on the glasses: the image block sits at the top of the full-screen page.
        val shown = screen.last
        assertTrue(shown.fullScreen)
        val x = (shown.width - 96) / 2
        assertEquals(200, shown.pixels[(8 + 10) * shown.width + x + 10].toInt() and 0xFF)

        // A picture of another size (the block changed meanwhile) is dropped, not drawn.
        player.listener.onFrame(GrayRaster(10, 10))
        settle()
        assertEquals(200, screen.last.pixels[(8 + 10) * shown.width + x + 10].toInt() and 0xFF)
    }

    @Test
    fun `pause, resume, seek, profile and stop reach the player`() {
        val host = host(app())
        host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
            video("bild", VideoAction.Pause)
            video("bild", VideoAction.Seek(12_000))
            video("bild", VideoAction.Profile(VideoProfile.STABLE))
            video("bild", VideoAction.Resume)
            video("bild", VideoAction.Stop)
        }
        assertEquals(listOf("pause", "seek 12000", "profile stable", "resume", "release"), player.calls)
        // No video left: a pause is refused.
        asApp { video("bild", VideoAction.Pause) }
        assertTrue(ports.logs.last(), ports.logs.last().contains("läuft kein Video"))
    }

    @Test
    fun `internet videos need the network permission, test pictures do not`() {
        val host = host(app(permissions = emptySet()))
        ports.answers["ch.test.video@1.0.0"] = emptySet()
        host.launch("watch:ch.test.video")
        settle()
        asApp { video("bild", VideoAction.Play("https://example.org/a.mp4")) }
        assertTrue(ports.video.players.isEmpty())
        assertTrue(ports.logs.last(), ports.logs.last().contains("Keine Berechtigung"))
        asApp { video("bild", VideoAction.Play("test:muster")) }
        assertEquals(1, ports.video.players.size)
        asApp { video("bild", VideoAction.Play("file:///sdcard/a.mp4")) }
        asApp { video("status", VideoAction.Play("test:muster")) }
        assertEquals(1, ports.video.players.size)
    }

    @Test
    fun `a hidden app's video waits, and goes on when the app is back`() {
        val host = host(app())
        host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
        }
        player.state(VideoState.PLAYING)
        settle()
        host.openLauncher()
        settle()
        assertEquals(listOf("pause"), player.calls)
        host.launch("watch:ch.test.video")
        settle()
        assertEquals(listOf("pause", "resume"), player.calls)
    }

    @Test
    fun `a video the app paused stays paused when the app comes back`() {
        val host = host(app())
        host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
            video("bild", VideoAction.Pause)
        }
        host.openLauncher()
        settle()
        host.launch("watch:ch.test.video")
        settle()
        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun `videos wait while the glasses are gone`() {
        val host = host(app())
        host.start()
        host.updateGlasses(GlassesStatus(connected = true))
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
        }
        player.state(VideoState.PLAYING)
        host.updateGlasses(GlassesStatus(connected = false))
        settle()
        host.updateGlasses(GlassesStatus(connected = true, battery = 80))
        settle()
        assertEquals(listOf("pause", "resume"), player.calls)
    }

    @Test
    fun `an error ends the video and is logged, closing the app releases the player`() {
        val host = host(app())
        val app = host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
        }
        player.state(VideoState.ERROR, message = "Keine Verbindung")
        settle()
        assertEquals(AppEvent.Video("bild", VideoState.ERROR, 0, 60_000, "Keine Verbindung"), app.videoEvents().last())
        assertTrue(player.released)
        assertTrue(ports.logs.any { it.contains("Keine Verbindung") })

        asApp { video("bild", VideoAction.Play("test:muster")) }
        asApp { close() }
        assertTrue(player.released)
    }

    @Test
    fun `a new video replaces the old one in the same block`() {
        val host = host(app())
        val app = host.start()
        asApp {
            show("video")
            video("bild", VideoAction.Play("test:muster"))
        }
        val first = player
        asApp { video("bild", VideoAction.Play("https://example.org/b.mp4")) }
        assertTrue(first.released)
        // The old player's late reports are ignored.
        first.state(VideoState.ENDED)
        settle()
        assertTrue(app.videoEvents().none { it.state == VideoState.ENDED })
    }

    @Test
    fun `video search runs off the app thread and answers on it`() {
        val host = host(app())
        host.start()
        var got: VideoSearchResult? = null
        asApp { videoSearch("katzen") { got = it } }
        val (query, answer) = ports.video.searches.single()
        assertEquals("katzen", query)
        answer(VideoSearchResult(listOf(VideoItem("https://www.youtube.com/watch?v=1", "Katze"))))
        assertEquals(null, got)
        settle()
        assertEquals("Katze", got!!.items.single().title)
        asApp { videoSearch("  ") {} }
        assertEquals(1, ports.video.searches.size)
    }
}

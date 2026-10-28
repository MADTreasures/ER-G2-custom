package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.FakeText
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.PlayerAction
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.media.MediaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Music on the watch for the apps (interface 6) in the app host: its players and an app's own stream, with fakes. */
class AppHostMediaTest {
    private val scheduler = FakeScheduler()
    private val text = FakeText()
    private val ports = FakePorts()
    private val created = mutableListOf<ScriptedApp>()
    private var context: AppContext? = null

    private fun app(permissions: Set<Permission>): () -> G2App = {
        ScriptedApp(AppManifest(ID, "Musik", "1.0.0", permissions = permissions)) { event, ui ->
            context = ui
            if (event == AppEvent.Start) {
                ui.definePages(listOf(Page("start", "Start", listOf(Block.Text("t", "Musik")))))
                ui.show("start")
            }
        }.also { created += it }
    }

    private fun host(permissions: Set<Permission> = setOf(Permission.MEDIA, Permission.NETWORK)): AppHost {
        val host = AppHost(scheduler, FakeScreen(), text, ports, listOf(app(permissions)), nowMs = { scheduler.now }, nanoTime = { scheduler.now * 1_000_000L })
        ports.answers["$ID@1.0.0"] = permissions
        host.launch("watch:$ID")
        settle()
        return host
    }

    private fun settle() {
        scheduler.runPending()
        scheduler.advanceBy(AppHost.RENDER_INTERVAL_MS)
    }

    private fun asApp(block: AppContext.() -> Unit) {
        scheduler.post { context!!.block() }
        settle()
    }

    private fun events() = created.last().events

    private val spotify = MediaInfo(
        app = "com.spotify.music",
        appName = "Spotify",
        state = MediaState.PLAYING,
        title = "Bohemian Rhapsody",
        artist = "Queen",
        album = "A Night at the Opera",
        durationMs = 354_000,
        positionMs = 60_000,
        positionAt = 1_000,
    )

    @Test
    fun `an app follows the players now, on every change, until it stops following`() {
        host()
        asApp { media(MediaAction.Follow) }
        val first = events().filterIsInstance<AppEvent.Media>().single()
        assertEquals(7, first.volume)
        assertEquals("Kopfhörer: Pixel Buds", first.output)
        assertTrue(first.players.isEmpty())

        ports.media.change(MediaSnapshot(listOf(spotify), 8, 15, "Kopfhörer: Pixel Buds"))
        settle()
        val second = events().filterIsInstance<AppEvent.Media>().last()
        assertEquals(listOf(spotify), second.players)
        assertEquals(8, second.volume)

        // Following twice is following once.
        asApp { media(MediaAction.Follow) }
        assertEquals(1, ports.media.listeners.size)
        asApp { media(MediaAction.Unfollow) }
        assertTrue(ports.media.listeners.isEmpty())
        ports.media.change(MediaSnapshot(emptyList(), 8, 15, null))
        settle()
        assertEquals(2, events().filterIsInstance<AppEvent.Media>().size)
    }

    @Test
    fun `commands reach the players, and a note why one did not work shows on the glasses`() {
        host()
        asApp {
            media(MediaAction.Pause())
            media(MediaAction.Next("com.spotify.music"))
            media(MediaAction.Volume(-2))
            media(MediaAction.Search("Queen", "com.spotify.music"))
        }
        assertEquals(
            listOf(MediaAction.Pause(), MediaAction.Next("com.spotify.music"), MediaAction.Volume(-2), MediaAction.Search("Queen", "com.spotify.music")),
            ports.media.commands,
        )
        ports.media.note = "Spotify ließ sich nicht öffnen"
        asApp { media(MediaAction.Open("com.spotify.music")) }
        assertTrue("Spotify ließ sich nicht öffnen" in text.drawn)
        assertTrue(ports.logs.any { "Spotify ließ sich nicht öffnen" in it })
    }

    @Test
    fun `wrong commands are refused before they reach a player`() {
        host()
        asApp {
            media(MediaAction.Open("not a package"))
            media(MediaAction.Volume(0))
            media(MediaAction.Volume(40))
            media(MediaAction.Search(" ", "com.spotify.music"))
            media(MediaAction.Seek(-1))
        }
        assertTrue(ports.media.commands.isEmpty())
        assertEquals(5, ports.logs.count { "abgelehnt" in it })
    }

    @Test
    fun `without the permission there is nothing to follow and nothing to do`() {
        host(permissions = setOf(Permission.NETWORK))
        asApp {
            media(MediaAction.Follow)
            media(MediaAction.Play())
        }
        assertTrue(ports.media.listeners.isEmpty())
        assertTrue(ports.media.commands.isEmpty())
        assertTrue(ports.logs.any { "abgelehnt" in it && "Musik der Uhr steuern" in it })
    }

    @Test
    fun `an own stream plays, reports, pauses, and a new one takes its place`() {
        host()
        asApp { player(PlayerAction.Play("http://stream.example.ch/radio.mp3", "Radio Test")) }
        val first = ports.streams.opened.single()
        assertEquals("Radio Test", first.title)
        first.listener.onState(PlayerState.PLAYING, "Queen - Bohemian Rhapsody", "Radio Test", null)
        settle()
        assertEquals(
            AppEvent.Player(PlayerState.PLAYING, "http://stream.example.ch/radio.mp3", "Queen - Bohemian Rhapsody", "Radio Test", null),
            events().filterIsInstance<AppEvent.Player>().single(),
        )
        asApp { player(PlayerAction.Pause) }
        assertTrue(first.paused)
        asApp { player(PlayerAction.Resume) }
        assertFalse(first.paused)

        // Another station: the old stream goes, its late reports are not heard any more.
        asApp { player(PlayerAction.Play("https://stream.example.ch/zwei.aac")) }
        assertTrue(first.released)
        val second = ports.streams.opened.last()
        assertEquals("Musik", second.title)
        first.listener.onState(PlayerState.ERROR, null, null, "alt")
        settle()
        assertEquals(1, events().filterIsInstance<AppEvent.Player>().size)
        second.listener.onState(PlayerState.ERROR, null, null, "Keine Verbindung zum Sender")
        settle()
        assertEquals("Keine Verbindung zum Sender", events().filterIsInstance<AppEvent.Player>().last().message)
        assertTrue(ports.logs.any { "Keine Verbindung zum Sender" in it })
    }

    @Test
    fun `streams need the network permission and an http address, and end with the app`() {
        host(permissions = setOf(Permission.MEDIA))
        asApp { player(PlayerAction.Play("https://stream.example.ch/radio.mp3")) }
        assertTrue(ports.streams.opened.isEmpty())
        assertTrue(ports.logs.any { "abgelehnt" in it && "Internet" in it })
    }

    @Test
    fun `stopping, and the end of the app, end the stream and the following`() {
        host()
        asApp {
            media(MediaAction.Follow)
            player(PlayerAction.Play("ftp://stream.example.ch/radio.mp3"))
            player(PlayerAction.Pause)
        }
        assertTrue(ports.streams.opened.isEmpty())
        asApp { player(PlayerAction.Play("https://stream.example.ch/radio.mp3")) }
        asApp { player(PlayerAction.Stop) }
        assertTrue(ports.streams.opened.single().released)
        asApp { player(PlayerAction.Play("https://stream.example.ch/radio.mp3")) }
        val playing = ports.streams.opened.last()
        asApp { close() }
        assertTrue(playing.released)
        assertTrue(ports.media.listeners.isEmpty())
    }

    @Test
    fun `the stream goes on while the app is hidden`() {
        val host = host()
        asApp { player(PlayerAction.Play("https://stream.example.ch/radio.mp3")) }
        host.openLauncher()
        settle()
        val stream = ports.streams.opened.single()
        assertFalse(stream.released)
        assertFalse(stream.paused)
        stream.listener.onState(PlayerState.PLAYING, "Song", null, null)
        settle()
        assertEquals("Song", events().filterIsInstance<AppEvent.Player>().last().title)
    }

    private companion object {
        const val ID = "ch.test.musik"
    }
}

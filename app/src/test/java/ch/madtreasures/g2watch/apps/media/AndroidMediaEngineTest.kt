package ch.madtreasures.g2watch.apps.media

import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The players on the watch through Android's media controls (03 §15), with Robolectric's media sessions,
 * notification access and audio: what a follower hears first, the media keys without access, the volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidMediaEngineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val component = ComponentName(context, MediaAccessService::class.java)
    private val logs = mutableListOf<String>()

    private fun firstSnapshot(engine: AndroidMediaEngine): MediaSnapshot {
        val heard = LinkedBlockingQueue<MediaSnapshot>()
        engine.follow { heard += it }
        return heard.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no snapshot")
    }

    @Test
    fun `without access a follower hears why, and the keys still reach the last player`() {
        shadowOf(audio).setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()))
        val engine = AndroidMediaEngine(context) { logs += it }
        val first = firstSnapshot(engine)
        assertEquals(AndroidMediaEngine.NO_ACCESS, first.message)
        assertTrue(first.players.isEmpty())
        assertTrue(first.output, first.output!!.startsWith("Kopfhörer"))
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), first.maxVolume)

        assertNull(engine.command(MediaAction.Play()))
        assertNull(engine.command(MediaAction.Next()))
        assertNull(engine.command(MediaAction.PlayPause()))
        val keys = shadowOf(audio).dispatchedMediaKeyEvents.map { it.keyCode to it.action }
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY to KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY to KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyEvent.ACTION_UP,
            ),
            keys,
        )
        // Seeking needs a session; there is none to see.
        assertEquals(AndroidMediaEngine.NO_PLAYER, engine.command(MediaAction.Seek(1_000)))
    }

    @Test
    fun `the volume goes up and down by steps`() {
        val engine = AndroidMediaEngine(context) { logs += it }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        assertNull(engine.command(MediaAction.Volume(2)))
        assertEquals(7, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertNull(engine.command(MediaAction.Volume(-3)))
        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `with access the players come with title, state, position, actions and a grey cover`() {
        val session = MediaSession(context, "test")
        val cover = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        val metadata =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Bohemian Rhapsody")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Queen")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "A Night at the Opera")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 354_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
                .build()
        val playback =
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 61_000, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO)
                .build()
        // Robolectric has no system server keeping a session's state: the controller tells it as Spotify's would.
        val spotify = MediaController(context, session.sessionToken)
        shadowOf(spotify).apply {
            setPackageName("com.spotify.music")
            setMetadata(metadata)
            setPlaybackState(playback)
        }
        shadowOf(context.getSystemService(MediaSessionManager::class.java)).addController(spotify)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(component, true)

        val first = firstSnapshot(AndroidMediaEngine(context) { logs += it })
        assertNull(first.message)
        val player = first.players.single()
        assertEquals("com.spotify.music", player.app)
        assertEquals("Bohemian Rhapsody", player.title)
        assertEquals("Queen", player.artist)
        assertEquals("A Night at the Opera", player.album)
        assertEquals(MediaState.PLAYING, player.state)
        assertEquals(354_000L, player.durationMs)
        assertEquals(61_000L, player.positionMs)
        assertTrue(player.canNext)
        assertFalse(player.canPrevious)
        assertTrue(player.canSeek)
        assertFalse(player.canSearch)
        assertTrue(player.artwork!!.startsWith("data:image/png;base64,"))

        // Play/pause goes to the session (Robolectric passes nothing on to it); no key goes out.
        val engine = AndroidMediaEngine(context) { logs += it }
        assertNull(engine.command(MediaAction.PlayPause("com.spotify.music")))
        assertTrue(shadowOf(audio).dispatchedMediaKeyEvents.isEmpty())
        session.release()
    }
}

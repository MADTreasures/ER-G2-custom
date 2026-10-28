package ch.madtreasures.g2watch.apps.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ch.madtreasures.g2watch.apps.PlayerState
import ch.madtreasures.g2watch.apps.video.FastNetwork

/** [StreamEngine] on the watch (03 §15): every stream an [ExoStreamPlayer] on its own thread. */
class AndroidStreamEngine(context: Context, private val log: (String) -> Unit) : StreamEngine {
    private val context = context.applicationContext

    override fun open(url: String, title: String, listener: StreamListener): StreamPlayer =
        ExoStreamPlayer(context, url, title, listener, FastNetwork.shared(context, log), log)
}

/**
 * An app's audio stream (internet radio, an audio file) with Media3's ExoPlayer on the thread "G2Watch-audio".
 * It takes the sound from other players and gives it back to them (audio focus), pauses when headphones go
 * (becoming noisy), and holds WLAN/LTE ([FastNetwork]) while it loads or plays. What the station sends about
 * itself and its song (ICY) comes as title and station.
 */
@OptIn(UnstableApi::class)
internal class ExoStreamPlayer(
    private val context: Context,
    private val url: String,
    private val title: String,
    private val listener: StreamListener,
    private val network: FastNetwork,
    private val log: (String) -> Unit,
) : StreamPlayer {
    private val thread = HandlerThread("G2Watch-audio").apply { start() }
    private val handler = Handler(thread.looper)

    // Only on the audio thread.
    private var player: ExoPlayer? = null
    private var released = false
    private var networkHeld = false
    private var played = false
    private var pauseReason: String? = null
    private var reported: List<Any?>? = null

    init {
        post { start() }
    }

    override fun pause() = post {
        pauseReason = null
        player?.playWhenReady = false
    }

    override fun resume() = post {
        val p = player ?: return@post
        // After a failure, or a live stream left long: connect again.
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            p.seekToDefaultPosition()
            p.prepare()
        }
        p.playWhenReady = true
    }

    override fun release() = post {
        released = true
        player?.release()
        player = null
        useNetwork(false)
        thread.quitSafely()
    }

    private fun post(action: () -> Unit) {
        handler.post { if (!released) action() }
    }

    private fun start() {
        useNetwork(true)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(TIMEOUT_MS)
            .setReadTimeoutMs(TIMEOUT_MS)
        val p = ExoPlayer.Builder(context)
            .setLooper(thread.looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(events)
        p.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        p.prepare()
        p.playWhenReady = true
        player = p
        report(PlayerState.LOADING)
    }

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = sync()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) played = true
            sync()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pauseReason = if (playWhenReady) null else when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "Eine andere App spielt"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "Kopfhörer getrennt"
                else -> null
            }
            sync()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = sync()

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = sync()

        override fun onPlayerError(error: PlaybackException) {
            log("Stream: ${error.errorCodeName} – ${error.message}")
            useNetwork(false)
            report(PlayerState.ERROR, explain(error))
        }
    }

    private fun sync() {
        val p = player ?: return
        if (p.playerError != null) return
        val state = when {
            p.playbackState == Player.STATE_ENDED -> PlayerState.ENDED
            p.isPlaying -> PlayerState.PLAYING
            !p.playWhenReady -> PlayerState.PAUSED
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> PlayerState.PAUSED
            p.playbackState == Player.STATE_BUFFERING && played -> PlayerState.BUFFERING
            else -> PlayerState.LOADING
        }
        val message = when {
            state != PlayerState.PAUSED -> null
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "Kurz unterbrochen"
            else -> pauseReason
        }
        useNetwork(state == PlayerState.PLAYING || state == PlayerState.BUFFERING || state == PlayerState.LOADING)
        report(state, message)
    }

    /** Tells the listener, once per change. The song is what the station sends, not the name the app gave. */
    private fun report(state: PlayerState, message: String? = null) {
        val m = player?.mediaMetadata
        val song = m?.title?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val station = m?.station?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val now = listOf(state, song, station, message)
        if (now == reported) return
        reported = now
        listener.onState(state, song, station, message)
    }

    private fun useNetwork(on: Boolean) {
        if (on == networkHeld || (on && released)) return
        networkHeld = on
        if (on) network.acquire() else network.release()
    }

    private fun explain(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Keine Verbindung zum Sender"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Der Sender ist unter dieser Adresse nicht zu finden"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Unverschlüsselte Adresse nicht erlaubt"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        -> "Dieses Format kann die Uhr nicht spielen"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Der Stream ist abgerissen"
        else -> "Stream fehlgeschlagen (${e.errorCodeName})"
    }

    private companion object {
        const val USER_AGENT = "G2Watch"
        const val TIMEOUT_MS = 15_000
    }
}

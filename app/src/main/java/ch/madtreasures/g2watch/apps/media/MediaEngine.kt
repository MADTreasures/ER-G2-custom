package ch.madtreasures.g2watch.apps.media

import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.PlayerState

/** What the players on the watch do, as one app's [MediaAction.Follow] hears it (interface 6, 03 §15). */
data class MediaSnapshot(
    val players: List<MediaInfo>,
    val volume: Int,
    val maxVolume: Int,
    val output: String?,
    val message: String? = null,
)

/** Hears what the players do; [onChange] comes from any thread. */
fun interface MediaListener {
    fun onChange(snapshot: MediaSnapshot)
}

/** One app's following of the players; [stop] ends it. */
fun interface MediaLine {
    fun stop()
}

/**
 * The music players on the watch (interface 6): Android's media controls there, a fake in tests. [follow]
 * reports them now and on every change; [command] carries out every other [MediaAction] on the calling thread
 * and returns a German note when it could not (no such app, nothing to play).
 */
interface MediaEngine {
    fun follow(listener: MediaListener): MediaLine

    fun command(action: MediaAction): String?

    companion object {
        const val NO_MEDIA = "Keine Mediensteuerung auf dieser Uhr"

        /** Where there are no players (tests that do not need them). */
        val NONE: MediaEngine = object : MediaEngine {
            override fun follow(listener: MediaListener): MediaLine {
                listener.onChange(MediaSnapshot(emptyList(), 0, 0, null, NO_MEDIA))
                return MediaLine {}
            }

            override fun command(action: MediaAction): String? = NO_MEDIA
        }
    }
}

/** Hears how an app's own stream goes; any thread. [title] and [station] as the station sends them. */
fun interface StreamListener {
    fun onState(state: PlayerState, title: String?, station: String?, message: String?)
}

/** A running stream; its methods may be called from any thread, and do nothing once it is released. */
interface StreamPlayer {
    fun pause()

    fun resume()

    fun release()
}

/** The apps' own audio streams (interface 6): Media3 on the watch, a fake in tests. */
fun interface StreamEngine {
    fun open(url: String, title: String, listener: StreamListener): StreamPlayer

    companion object {
        const val NO_SOUND = "Kein Ton auf dieser Uhr"

        val NONE = StreamEngine { _, _, listener ->
            listener.onState(PlayerState.ERROR, null, null, NO_SOUND)
            object : StreamPlayer {
                override fun pause() = Unit

                override fun resume() = Unit

                override fun release() = Unit
            }
        }
    }
}

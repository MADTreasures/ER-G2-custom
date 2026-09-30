package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.desktop.GrayRaster

/** What to play ([src] as in [ch.madtreasures.g2watch.apps.VideoAction.Play]) into a [width] × [height] picture. */
data class VideoRequest(
    val src: String,
    val width: Int,
    val height: Int,
    val profile: VideoProfile,
    val sound: Boolean = false,
    val startMs: Long = 0,
)

/** What a playing video reports; called from the engine's threads. */
interface VideoListener {
    /** A new picture of exactly the requested size; the engine never touches it again. */
    fun onFrame(raster: GrayRaster)

    /** A new state, or (while playing) the position about once a second; [message] explains an error (German). */
    fun onState(state: VideoState, positionMs: Long, durationMs: Long, message: String? = null)
}

/** A running video; its methods may be called from any thread. */
interface VideoPlayer {
    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun setProfile(profile: VideoProfile)

    /** Ends playback and frees decoder, network and threads; no callbacks afterwards. */
    fun release()
}

/**
 * The video part of the platform (03 §10): searching and playing. On the watch it is
 * [AndroidVideoEngine] (NewPipeExtractor for YouTube, Media3 for decoding); tests use fakes. Sources
 * starting with `test:` are the watch's own test pictures ([TestPatternPlayer]), which need no network.
 */
interface VideoEngine {
    /** Searches YouTube off the caller's thread; [done] is called from any thread. */
    fun search(query: String, done: (VideoSearchResult) -> Unit)

    fun open(request: VideoRequest, listener: VideoListener): VideoPlayer

    companion object {
        /** Where there is no video (tests that do not need it): searches fail, sources report an error. */
        val NONE: VideoEngine = object : VideoEngine {
            override fun search(query: String, done: (VideoSearchResult) -> Unit) =
                done(VideoSearchResult(emptyList(), "Videos gibt es hier nicht"))

            override fun open(request: VideoRequest, listener: VideoListener): VideoPlayer {
                listener.onState(VideoState.ERROR, 0, 0, "Videos gibt es hier nicht")
                return object : VideoPlayer {
                    override fun pause() = Unit

                    override fun resume() = Unit

                    override fun seekTo(positionMs: Long) = Unit

                    override fun setProfile(profile: VideoProfile) = Unit

                    override fun release() = Unit
                }
            }
        }
    }
}

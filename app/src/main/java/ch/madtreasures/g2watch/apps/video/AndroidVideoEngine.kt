package ch.madtreasures.g2watch.apps.video

import android.content.Context
import android.os.SystemClock
import ch.madtreasures.g2watch.ThreadScheduler
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import java.util.concurrent.Executors

/**
 * [VideoEngine] on the watch (03 §10): YouTube search and video pages with NewPipeExtractor, playback with
 * Media3 ([ExoVideoPlayer], over WLAN or LTE when Wear OS gives it, see [FastNetwork]), the test pictures of
 * the watch with [TestPatternPlayer]. Network work runs on two background threads, so a slow search never
 * holds up a video.
 */
class AndroidVideoEngine(context: Context, private val log: (String) -> Unit) : VideoEngine {
    private val context = context.applicationContext
    private val work = Executors.newFixedThreadPool(2) { r -> Thread(r, "G2Watch-video-net").apply { isDaemon = true } }
    private val network = FastNetwork(this.context, log)
    private val testThread by lazy { ThreadScheduler("G2Watch-video-test") }

    /** A search is a few hundred kilobytes: it takes whatever network there is, without waiting for WLAN/LTE. */
    override fun search(query: String, done: (VideoSearchResult) -> Unit) {
        work.execute { done(NewPipeCatalog.search(query)) }
    }

    override fun open(request: VideoRequest, listener: VideoListener): VideoPlayer {
        if (request.src.startsWith("test:")) {
            if (request.src != TestPattern.SRC) {
                listener.onState(VideoState.ERROR, 0, 0, "Unbekanntes Testbild „${request.src}“")
                return Stopped
            }
            return TestPatternPlayer(request, listener, testThread) { SystemClock.elapsedRealtime() }
        }
        return ExoVideoPlayer(context, request, listener, work, network, log)
    }

    private object Stopped : VideoPlayer {
        override fun pause() = Unit

        override fun resume() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setProfile(profile: VideoProfile) = Unit

        override fun release() = Unit
    }
}

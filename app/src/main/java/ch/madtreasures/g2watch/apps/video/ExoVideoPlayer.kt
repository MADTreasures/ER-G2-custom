package ch.madtreasures.g2watch.apps.video

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.desktop.Rect
import java.util.concurrent.Executor

/**
 * A video from the internet, decoded on the watch for the glasses (03 §10). Media3's ExoPlayer decodes
 * in hardware into a [GlFrameGrabber]; every [VideoProfile.frameMs] a picture is read back at raster size
 * and turned into grey levels by [FrameConverter]. Video pages (YouTube …) are looked up with
 * [NewPipeCatalog] on [work]; when a stream fails the next [PlaybackPlan] is tried, and once all failed the
 * page is looked up again (addresses expire). WLAN/LTE ([FastNetwork]) is held while the video loads or
 * plays, not while it is over or paused for long. Everything else runs on the thread "G2Watch-video".
 */
@OptIn(UnstableApi::class)
internal class ExoVideoPlayer(
    context: Context,
    private val request: VideoRequest,
    private val listener: VideoListener,
    private val work: Executor,
    private val network: FastNetwork,
    private val log: (String) -> Unit,
) : VideoPlayer {
    private val context = context.applicationContext

    // Before the thread: a size the converter refuses throws here, without leaving a thread behind.
    private val converter = FrameConverter(request.width, request.height, request.profile)
    private val thread = HandlerThread("G2Watch-video").apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on the video thread.
    private var player: ExoPlayer? = null
    private var grabber: GlFrameGrabber? = null
    private var luma = ByteArray(0)
    private var picture = Rect(0, 0, converter.grid.width, converter.grid.height)
    private var plans: List<PlaybackPlan> = emptyList()
    private var plan = 0
    private var lookedUpAgain = false
    private var durationMs = 0L
    private var live = false

    /** Where a (re)start begins: the requested start, a jump made meanwhile, or where a failed stream stopped. */
    private var resumeAt = request.startMs
    private var wantPause = false
    private var released = false
    private var shown = false
    private var lastPictureAt = Long.MIN_VALUE / 2
    private var lastState: VideoState? = null
    private var networkHeld = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (released) return
            if (p.isPlaying) listener.onState(VideoState.PLAYING, p.currentPosition, duration())
            handler.postDelayed(this, REPORT_MS)
        }
    }

    /** A pause that lasts: the buffer is full by then, WLAN/LTE can go (akku). */
    private val idle = Runnable { if (lastState == VideoState.PAUSED) useNetwork(false) }

    override fun pause() = post {
        wantPause = true
        player?.playWhenReady = false
    }

    override fun resume() = post {
        wantPause = false
        val p = player ?: return@post
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = true
    }

    override fun seekTo(positionMs: Long) = post {
        resumeAt = positionMs
        val p = player ?: return@post
        lastPictureAt = Long.MIN_VALUE / 2
        converter.reset()
        p.seekTo(positionMs)
    }

    override fun setProfile(profile: VideoProfile) = post {
        converter.profile = profile
        picture = player?.videoSize?.let { fit(it) } ?: Rect(0, 0, converter.grid.width, converter.grid.height)
        lastPictureAt = Long.MIN_VALUE / 2
        // Paused, no new picture comes: draw the last one again in the new raster.
        if (shown && player?.isPlaying != true) picture()
    }

    override fun release() = post {
        released = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        grabber?.release()
        grabber = null
        if (networkHeld) {
            networkHeld = false
            network.release()
        }
        thread.quitSafely()
    }

    private fun post(action: () -> Unit) {
        handler.post { if (!released) action() }
    }

    private fun useNetwork(on: Boolean) {
        if (on == networkHeld || (on && released)) return
        networkHeld = on
        if (on) network.acquire() else network.release()
    }

    /** Finds the streams of the video page (network, on [work]) and starts at [resumeAt]. */
    private fun lookUp() = work.execute { resolve() }

    /** On [work]. An Error from NewPipe or Rhino must not take the app, and with it the glasses, down. */
    private fun resolve() {
        val result = try {
            NewPipeCatalog.resolve(request.src)
        } catch (e: Throwable) {
            val message = NewPipeCatalog.explain(e)
            post { fail(message) }
            return
        }
        val want = request.height / request.profile.cell
        val chosen = StreamChooser.plans(result.options, result.hls, result.live, want, request.sound)
        post {
            if (chosen.isEmpty()) {
                fail("Keine abspielbare Fassung gefunden")
                return@post
            }
            durationMs = result.durationMs
            live = result.live
            plans = chosen
            plan = 0
            start()
        }
    }

    private fun start() {
        val p = player ?: createPlayer().also { player = it }
        val chosen = plans[plan]
        log("Video: ${chosen.label}")
        // Live: at the live edge. Otherwise where it should go on.
        if (live || resumeAt <= 0) p.setMediaSource(source(chosen), true) else p.setMediaSource(source(chosen), resumeAt)
        p.playWhenReady = !wantPause
        p.prepare()
    }

    private fun createPlayer(): ExoPlayer {
        val g = GlFrameGrabber(handler) { picture() }
        grabber = g
        val selector = DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, StreamChooser.MAX_HEIGHT)
                .setForceLowestBitrate(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !request.sound)
                .build(),
        )
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, 1_500, 3_000)
            .build()
        return ExoPlayer.Builder(context)
            .setLooper(thread.looper)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .build()
            .apply {
                setVideoSurface(g.surface)
                setWakeMode(C.WAKE_MODE_NETWORK)
                if (request.sound) {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                        true,
                    )
                } else {
                    volume = 0f
                }
                addListener(events)
                handler.postDelayed(ticker, REPORT_MS)
            }
    }

    private fun source(plan: PlaybackPlan): MediaSource {
        val hls = plan.hls
        if (hls != null) {
            return HlsMediaSource.Factory(http(hls)).createMediaSource(MediaItem.fromUri(hls.toUri()))
        }
        val video = progressive(plan.video!!.url)
        val audio = plan.audio?.let { progressive(it.url) } ?: return video
        return MergingMediaSource(video, audio)
    }

    private fun progressive(url: String): MediaSource {
        val factory: DataSource.Factory = if (YoutubeClients.isGooglevideo(url)) GooglevideoDataSource.Factory(url) else http(url)
        return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url.toUri()))
    }

    private fun http(url: String): DataSource.Factory {
        val headers = if (YoutubeClients.isGooglevideo(url)) YoutubeClients.headers(url) else mapOf("User-Agent" to NewPipeCatalog.USER_AGENT)
        return DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"])
            .setDefaultRequestProperties(headers - "User-Agent")
            .setConnectTimeoutMs(GooglevideoDataSource.TIMEOUT_MS)
            .setReadTimeoutMs(GooglevideoDataSource.TIMEOUT_MS)
    }

    /** A decoded picture is on the GPU; read it if the profile wants one now. */
    private fun picture() {
        val g = grabber ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPictureAt < converter.profile.frameMs) return
        lastPictureAt = now
        val grid = converter.grid
        if (luma.size != grid.size) luma = ByteArray(grid.size)
        g.read(grid.width, grid.height, picture, luma)
        listener.onFrame(converter.convert(luma, picture))
        if (!shown) {
            // The first picture can come while ExoPlayer still fills its buffer: its state says what it is.
            shown = true
            sync()
        }
    }

    private fun fit(size: VideoSize): Rect {
        if (size.width <= 0 || size.height <= 0) return Rect(0, 0, converter.grid.width, converter.grid.height)
        return converter.pictureRect((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
    }

    private val events = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            picture = fit(videoSize)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = sync()

        override fun onIsPlayingChanged(isPlaying: Boolean) = sync()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // A live stream paused for longer than its window: on at the live edge.
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (!live) resumeAt = p.currentPosition
            val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            log("Video: ${plans.getOrNull(plan)?.label} geht nicht – ${error.errorCodeName}${code?.let { " (HTTP $it)" } ?: ""}")
            when {
                plan + 1 < plans.size -> {
                    plan++
                    start()
                }
                !lookedUpAgain && !isDirectMedia(request.src) -> {
                    // The addresses may have expired or belong to another network: look the page up again.
                    lookedUpAgain = true
                    report(VideoState.LOADING, resumeAt)
                    lookUp()
                }
                else -> fail(explain(error, code))
            }
        }
    }

    private fun sync() {
        val p = player ?: return
        // After an error ExoPlayer still reports IDLE; the video is over, and so is its network.
        if (lastState == VideoState.ERROR) return
        val state = when (p.playbackState) {
            Player.STATE_ENDED -> VideoState.ENDED
            Player.STATE_BUFFERING, Player.STATE_IDLE -> if (shown) VideoState.BUFFERING else VideoState.LOADING
            else -> when {
                p.isPlaying -> VideoState.PLAYING
                !shown -> VideoState.LOADING
                else -> VideoState.PAUSED
            }
        }
        handler.removeCallbacks(idle)
        when (state) {
            VideoState.ENDED -> useNetwork(false)
            VideoState.PAUSED -> handler.postDelayed(idle, IDLE_MS)
            else -> useNetwork(true)
        }
        report(state, p.currentPosition)
    }

    /** Reports a new state; after an error nothing follows (ExoPlayer still reports IDLE after it). */
    private fun report(state: VideoState, positionMs: Long) {
        if (state == lastState || lastState == VideoState.ERROR) return
        lastState = state
        listener.onState(state, positionMs, duration())
    }

    private fun fail(message: String) {
        if (lastState == VideoState.ERROR) return
        lastState = VideoState.ERROR
        useNetwork(false)
        listener.onState(VideoState.ERROR, player?.currentPosition ?: 0, duration(), message)
    }

    private fun duration(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d != C.TIME_UNSET && d > 0) d else durationMs
    }

    private fun explain(error: PlaybackException, http: Int?): String = when {
        http == 403 -> "YouTube lässt das Video nicht laden (HTTP 403)"
        http != null -> "Das Video lädt nicht (HTTP $http)"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung"
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Die Uhr kann dieses Video nicht entschlüsseln"
        else -> "Das Video lässt sich nicht abspielen (${error.errorCodeName})"
    }

    // Last, so every field above is set before the video thread starts on it.
    init {
        handler.post {
            report(VideoState.LOADING, request.startMs)
            useNetwork(true)
            work.execute {
                network.awaitReady()
                if (isDirectMedia(request.src)) {
                    val direct = if (request.src.contains(".m3u8")) PlaybackPlan(hls = request.src) else PlaybackPlan(video = StreamOption(request.src, StreamOption.Kind.MUXED))
                    post {
                        plans = listOf(direct)
                        start()
                    }
                } else {
                    resolve()
                }
            }
        }
    }

    companion object {
        const val REPORT_MS = 1000L

        /** After this long in pause WLAN/LTE goes; it comes back with the next picture wanted. */
        const val IDLE_MS = 20_000L
        const val BUFFER_MIN_MS = 15_000
        const val BUFFER_MAX_MS = 30_000

        /** A file or playlist rather than a page a portal has to be asked about. */
        fun isDirectMedia(src: String): Boolean {
            val path = src.toUri().path.orEmpty().lowercase()
            return listOf(".mp4", ".m4v", ".webm", ".mkv", ".m3u8", ".3gp").any { path.endsWith(it) }
        }
    }
}

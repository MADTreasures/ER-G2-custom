package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.desktop.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The watch's own test video (`test:muster`, 16:9, one minute): a grey ramp with all 16 levels, a bar
 * that sweeps across in 4 s, a bouncing ball, a hand that turns once in 10 s and the position as a bar at
 * the bottom. It shows on the glasses whether pictures arrive smoothly, without internet or YouTube.
 */
object TestPattern {
    const val SRC = "test:muster"
    const val DURATION_MS = 60_000L
    const val WIDTH = 16
    const val HEIGHT = 9

    /** Paints the picture at [positionMs] into [picture] of [luma] (a grid [gridW] points wide). */
    fun render(luma: ByteArray, gridW: Int, picture: Rect, positionMs: Long) {
        val w = picture.w
        val h = picture.h
        val t = positionMs / 1000.0
        val ramp = maxOf(1, h / 8)
        val bar = (positionMs % 4000) * w / 4000
        val ballR = maxOf(2, h / 9)
        val ballX = bounce(t * 0.37, ballR, w - ballR)
        val ballY = bounce(t * 0.53, ramp + ballR, h - ballR - maxOf(2, h / 12))
        val cx = w / 2.0
        val cy = (ramp + h) / 2.0
        val angle = t / 10.0 * 2 * PI
        val hand = h / 3.0
        val dx = sin(angle)
        val dy = -cos(angle)
        val progress = (positionMs * w / DURATION_MS).toInt()
        val footer = maxOf(2, h / 16)
        for (y in 0 until h) {
            val row = (picture.y + y) * gridW + picture.x
            for (x in 0 until w) {
                var v = when {
                    y < ramp -> x * 16 / w * 17
                    y >= h - footer -> if (x < progress) 200 else 40
                    else -> 30 + 60 * (y - ramp) / (h - ramp)
                }
                if (y in ramp until h - footer) {
                    if (abs(x - bar) <= maxOf(1, w / 80)) v = 150
                    val bx = x - ballX
                    val by = y - ballY
                    if (bx * bx + by * by <= ballR * ballR) v = 255
                    // The hand: points near the segment from the centre along (dx, dy).
                    val px = x - cx
                    val py = y - cy
                    val along = px * dx + py * dy
                    val across = abs(px * dy - py * dx)
                    if (along in 0.0..hand && across <= maxOf(1.0, h / 60.0)) v = 230
                }
                luma[row + x] = v.toByte()
            }
        }
    }

    /** A value that runs back and forth between [lo] and [hi], [speed] times per second. */
    private fun bounce(phase: Double, lo: Int, hi: Int): Int {
        if (hi <= lo) return lo
        val f = phase % 2.0
        val u = if (f < 1.0) f else 2.0 - f
        return lo + (u * (hi - lo)).toInt()
    }
}

/**
 * Plays [TestPattern] like a video: pictures every [VideoProfile.frameMs] on [scheduler], the position
 * once a second, pause, jump, end after a minute. Methods may be called from any thread.
 */
class TestPatternPlayer(
    private val request: VideoRequest,
    private val listener: VideoListener,
    private val scheduler: Scheduler,
    private val nowMs: () -> Long,
) : VideoPlayer {
    private val converter = FrameConverter(request.width, request.height, request.profile).apply { adjust = false }

    // Only touched on the scheduler's thread.
    private var position = request.startMs.coerceIn(0, TestPattern.DURATION_MS)
    private var started = false
    private var wantPause = false
    private var playing = false
    private var released = false
    private var ended = false
    private var tickedAt = 0L
    private var reportedAt = Long.MIN_VALUE / 2
    private var token = 0

    init {
        scheduler.post {
            if (released) return@post
            listener.onState(VideoState.LOADING, position, TestPattern.DURATION_MS)
            scheduler.postDelayed(START_MS) {
                if (released) return@postDelayed
                started = true
                if (wantPause) {
                    frame()
                    listener.onState(VideoState.PAUSED, position, TestPattern.DURATION_MS)
                } else {
                    play()
                }
            }
        }
    }

    override fun pause() = scheduler.post {
        if (released) return@post
        wantPause = true
        if (!playing) return@post
        advance()
        playing = false
        token++
        listener.onState(VideoState.PAUSED, position, TestPattern.DURATION_MS)
    }

    override fun resume() = scheduler.post {
        if (released) return@post
        wantPause = false
        if (!started || playing) return@post
        if (ended) {
            position = 0
            ended = false
            converter.reset()
        }
        play()
    }

    override fun seekTo(positionMs: Long) = scheduler.post {
        if (released) return@post
        position = positionMs.coerceIn(0, TestPattern.DURATION_MS - 1)
        ended = false
        tickedAt = nowMs()
        converter.reset()
        if (!started) return@post
        frame()
        listener.onState(if (playing) VideoState.PLAYING else VideoState.PAUSED, position, TestPattern.DURATION_MS)
    }

    override fun setProfile(profile: VideoProfile) = scheduler.post {
        if (released) return@post
        converter.profile = profile
        if (started) frame()
    }

    override fun release() = scheduler.post {
        released = true
        playing = false
        token++
    }

    private fun play() {
        playing = true
        tickedAt = nowMs()
        reportedAt = Long.MIN_VALUE / 2
        tick(++token)
    }

    private fun tick(mine: Int) {
        if (released || !playing || mine != token) return
        advance()
        frame()
        if (position >= TestPattern.DURATION_MS) {
            playing = false
            ended = true
            listener.onState(VideoState.ENDED, position, TestPattern.DURATION_MS)
            return
        }
        val now = nowMs()
        if (now - reportedAt >= REPORT_MS) {
            reportedAt = now
            listener.onState(VideoState.PLAYING, position, TestPattern.DURATION_MS)
        }
        scheduler.postDelayed(converter.profile.frameMs) { tick(mine) }
    }

    private fun advance() {
        val now = nowMs()
        position = (position + (now - tickedAt)).coerceAtMost(TestPattern.DURATION_MS)
        tickedAt = now
    }

    private fun frame() {
        val grid = converter.grid
        val luma = ByteArray(grid.size)
        val picture = converter.pictureRect(TestPattern.WIDTH, TestPattern.HEIGHT)
        TestPattern.render(luma, grid.width, picture, position)
        listener.onFrame(converter.convert(luma, picture))
    }

    companion object {
        /** A moment of "loading", as a real video has. */
        const val START_MS = 300L
        const val REPORT_MS = 1000L
    }
}

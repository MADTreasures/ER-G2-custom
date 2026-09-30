package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's own test video, in virtual time. */
class TestPatternPlayerTest {
    private val scheduler = FakeScheduler()
    private val frames = mutableListOf<GrayRaster>()
    private val states = mutableListOf<Pair<VideoState, Long>>()
    private val listener = object : VideoListener {
        override fun onFrame(raster: GrayRaster) {
            frames += raster
        }

        override fun onState(state: VideoState, positionMs: Long, durationMs: Long, message: String?) {
            assertEquals(TestPattern.DURATION_MS, durationMs)
            states += Pair(state, positionMs)
        }
    }

    private fun player(profile: VideoProfile = VideoProfile.BALANCED, startMs: Long = 0) =
        TestPatternPlayer(VideoRequest(TestPattern.SRC, 416, 234, profile, startMs = startMs), listener, scheduler) { scheduler.now }

    @Test
    fun `loads, then sends pictures at the profile's pace and the position every second`() {
        player(VideoProfile.BALANCED)
        scheduler.runPending()
        assertEquals(listOf(Pair(VideoState.LOADING, 0L)), states)
        scheduler.advanceBy(TestPatternPlayer.START_MS)
        assertEquals(VideoState.PLAYING, states.last().first)
        assertEquals(1, frames.size)
        scheduler.advanceBy(2_000)
        // One picture every 500 ms.
        assertEquals(5, frames.size)
        assertTrue(frames.all { it.width == 416 && it.height == 234 })
        assertEquals(listOf(0L, 1_000L, 2_000L), states.filter { it.first == VideoState.PLAYING }.map { it.second })
    }

    @Test
    fun `pictures move, and use the whole grey range`() {
        player(VideoProfile.STABLE)
        scheduler.advanceBy(TestPatternPlayer.START_MS + 1_000)
        val (a, b) = frames.takeLast(2)
        assertTrue(!a.pixels.contentEquals(b.pixels))
        assertEquals(255, b.pixels.maxOf { it.toInt() and 0xFF })
    }

    @Test
    fun `pause stops pictures, resume goes on where it was`() {
        val p = player(VideoProfile.FAST)
        scheduler.advanceBy(TestPatternPlayer.START_MS + 1_000)
        p.pause()
        scheduler.runPending()
        val count = frames.size
        assertEquals(Pair(VideoState.PAUSED, 1_000L), states.last())
        scheduler.advanceBy(5_000)
        assertEquals(count, frames.size)
        p.resume()
        scheduler.advanceBy(250)
        assertTrue(frames.size > count)
        assertEquals(Pair(VideoState.PLAYING, 1_000L), states.last())
    }

    @Test
    fun `a pause before the start shows the first picture and waits`() {
        val p = player(startMs = 20_000)
        p.pause()
        scheduler.advanceBy(TestPatternPlayer.START_MS + 3_000)
        assertEquals(listOf(Pair(VideoState.LOADING, 20_000L), Pair(VideoState.PAUSED, 20_000L)), states)
        assertEquals(1, frames.size)
    }

    @Test
    fun `seek jumps and shows the new picture at once`() {
        val p = player()
        scheduler.advanceBy(TestPatternPlayer.START_MS)
        p.pause()
        p.seekTo(30_000)
        scheduler.runPending()
        assertEquals(Pair(VideoState.PAUSED, 30_000L), states.last())
        p.seekTo(999_999)
        scheduler.runPending()
        assertEquals(TestPattern.DURATION_MS - 1, states.last().second)
    }

    @Test
    fun `it ends after a minute, and resume starts again`() {
        val p = player(VideoProfile.STABLE, startMs = 58_000)
        scheduler.advanceBy(TestPatternPlayer.START_MS + 3_000)
        assertEquals(Pair(VideoState.ENDED, TestPattern.DURATION_MS), states.last())
        val count = frames.size
        scheduler.advanceBy(3_000)
        assertEquals(count, frames.size)
        p.resume()
        scheduler.runPending()
        assertEquals(Pair(VideoState.PLAYING, 0L), states.last())
    }

    @Test
    fun `nothing after release`() {
        val p = player()
        scheduler.advanceBy(TestPatternPlayer.START_MS)
        p.release()
        scheduler.runPending()
        val count = frames.size to states.size
        p.resume()
        p.seekTo(1_000)
        scheduler.advanceBy(5_000)
        assertEquals(count, frames.size to states.size)
    }
}

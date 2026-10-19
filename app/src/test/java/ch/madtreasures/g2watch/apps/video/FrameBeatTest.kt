package ch.madtreasures.g2watch.apps.video

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

/** Which decoded pictures the player takes for its profile's beat. */
class FrameBeatTest {
    /** Decoded pictures every [videoMs] for [seconds]; the times taken for a beat of [frameMs]. */
    private fun taken(videoMs: Double, frameMs: Long, seconds: Int = 2, beat: FrameBeat = FrameBeat()): List<Long> =
        (0 until (seconds * 1000 / videoMs).toInt()).map { (it * videoMs).roundToLong() }.filter { beat.take(it, frameMs) }

    @Test
    fun `the rate holds when the video's pictures do not divide it`() {
        // 25 a second at 100 ms: 10 a second, not 8.
        assertEquals(20, taken(40.0, 100).size)
        // 30 a second: every third.
        assertEquals(listOf(0L, 100L, 200L, 300L), taken(100.0 / 3, 100).take(4))
        // 24 a second at 66 ms: every picture would be 24, every second 12; on average 15.
        assertEquals(30.0, taken(1000.0 / 24, 66).size.toDouble(), 1.0)
    }

    @Test
    fun `after a gap the beat starts anew instead of catching up`() {
        val beat = FrameBeat()
        assertEquals(true, beat.take(0, 100))
        // A pause of five seconds: the next picture is taken, the one right after it is not.
        assertEquals(true, beat.take(5_000, 100))
        assertEquals(false, beat.take(5_040, 100))
        assertEquals(true, beat.take(5_100, 100))
        // After a jump the next picture comes at once.
        beat.restart()
        assertEquals(true, beat.take(5_120, 100))
    }
}

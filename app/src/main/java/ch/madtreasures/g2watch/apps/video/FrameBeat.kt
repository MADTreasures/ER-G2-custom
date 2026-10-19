package ch.madtreasures.g2watch.apps.video

/**
 * Which decoded pictures to take for a picture every `frameMs`: the first one at or after each beat. So
 * the rate holds when the video's own pictures do not divide it (25 a second at 100 ms: 80 and 120 ms
 * apart in turn, 10 a second on average, not 8). After a gap (pause, buffering) the beat starts anew
 * instead of catching up.
 */
internal class FrameBeat {
    private var next = Long.MIN_VALUE / 2

    /** True if the picture at [nowMs] is to be taken; then the next beat is set. */
    fun take(nowMs: Long, frameMs: Long): Boolean {
        if (nowMs < next) return false
        next = if (nowMs - next >= frameMs) nowMs + frameMs else next + frameMs
        return true
    }

    /** The next picture is taken at once (after a jump or a new profile). */
    fun restart() {
        next = Long.MIN_VALUE / 2
    }
}

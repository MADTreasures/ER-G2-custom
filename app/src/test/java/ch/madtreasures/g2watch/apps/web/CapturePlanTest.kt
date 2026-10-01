package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.AFTER_SCROLL_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.AFTER_TAP_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.CHANGED_GAP_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.IDLE_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.LOADING_EVERY_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.MIN_GAP_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.PREVIEW_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.READER_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.READER_WAIT_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.RESUME_MS
import ch.madtreasures.g2watch.apps.web.CapturePlan.Companion.SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the browser takes pictures of a page (05 §10): after events, thinned out, never while resting. */
class CapturePlanTest {
    private val plan = CapturePlan()

    /** Takes every picture due up to [until], in 10 ms steps; returns when they were taken. */
    private fun run(from: Long, until: Long): List<Long> {
        val taken = ArrayList<Long>()
        var t = from
        while (t <= until) {
            if (plan.take(t)) taken += t
            t += 10
        }
        return taken
    }

    @Test
    fun `nothing happens without a reason`() {
        assertNull(plan.nextAt())
        assertFalse(plan.take(10_000))
    }

    @Test
    fun `a loading page shows a first picture once it painted, then now and then, and one when it is there`() {
        plan.started(0, waitForReader = false)
        plan.painted(1_000)
        assertEquals(1_000 + PREVIEW_MS, plan.nextAt())
        assertEquals(listOf(1_000 + PREVIEW_MS), run(0, 2_000))
        // Loading goes on: not before LOADING_EVERY_MS after the last picture.
        plan.progressed(2_500)
        assertNull(plan.nextAt())
        plan.progressed(1_600 + LOADING_EVERY_MS)
        assertEquals(listOf(1_600 + LOADING_EVERY_MS), run(2_500, 5_000))
        plan.stopped(6_000)
        assertEquals(listOf(6_000 + SETTLE_MS), run(5_000, 8_000))
        assertNull(plan.nextAt())
    }

    @Test
    fun `after the wearer acted the picture follows, never closer than the minimum gap`() {
        plan.acted(0, AFTER_SCROLL_MS)
        assertEquals(listOf(AFTER_SCROLL_MS), run(0, 1_000))
        plan.acted(AFTER_SCROLL_MS + 10, 0)
        assertEquals(AFTER_SCROLL_MS + MIN_GAP_MS, plan.nextAt())
        // Several actions in a row make one picture, after the earliest wish.
        plan.take(AFTER_SCROLL_MS + MIN_GAP_MS)
        plan.acted(2_000, AFTER_TAP_MS)
        plan.acted(2_100, AFTER_SCROLL_MS)
        assertEquals(listOf(2_100 + AFTER_SCROLL_MS), run(2_000, 4_000))
    }

    @Test
    fun `changes of the page are thinned out and stop when the wearer is away`() {
        plan.acted(0, 0)
        assertTrue(plan.take(0))
        plan.changed(100)
        assertEquals(CHANGED_GAP_MS, plan.nextAt())
        plan.changed(150)
        assertEquals(listOf(CHANGED_GAP_MS), run(100, 5_000))
        // A carousel turning a minute after the last touch: no more pictures.
        plan.changed(IDLE_MS + 10)
        assertNull(plan.nextAt())
        // While a page loads, its changes count anyway.
        plan.started(IDLE_MS + 100, waitForReader = false)
        plan.changed(IDLE_MS + 200)
        assertTrue(plan.nextAt() != null)
    }

    @Test
    fun `a resting page takes no pictures and gets a fresh one when it wakes up`() {
        plan.acted(0, AFTER_TAP_MS)
        plan.activate(100, on = false)
        assertNull(plan.nextAt())
        assertEquals(emptyList<Long>(), run(0, 5_000))
        plan.activate(5_000, on = true)
        assertEquals(listOf(5_000 + RESUME_MS), run(5_000, 6_000))
    }

    @Test
    fun `in reading mode the page waits for the decision, but not for ever`() {
        plan.started(0, waitForReader = true)
        plan.painted(300)
        plan.progressed(3_000)
        assertNull(plan.nextAt())
        plan.stopped(4_000)
        plan.readerDecided(4_100)
        // Not at once: the reading view needs a moment to lay out.
        assertEquals(listOf(4_100 + READER_MS), run(4_100, 6_000))

        // No script on the page: after the wait the page shows as it is.
        plan.started(10_000, waitForReader = true)
        plan.stopped(11_000)
        assertEquals(listOf(11_000 + READER_WAIT_MS), run(10_000, 14_000))
    }
}

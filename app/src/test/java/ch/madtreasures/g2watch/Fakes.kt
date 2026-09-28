package ch.madtreasures.g2watch

import ch.madtreasures.g2watch.desktop.GlassesDisplay
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.TextPainter

/** A [Scheduler] on virtual time: nothing runs until the test says so. */
class FakeScheduler : Scheduler {
    var now = 0L
        private set

    /** True while one of this scheduler's tasks runs, i.e. "on this thread". */
    var inTask = false
        private set

    private class Task(val due: Long, val seq: Long, val action: () -> Unit)

    private val tasks = ArrayList<Task>()
    private var seq = 0L

    override fun post(action: () -> Unit) {
        tasks += Task(now, seq++, action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        tasks += Task(now + delayMs, seq++, action)
    }

    /** Runs everything due now, including what those tasks post for now. */
    fun runPending() {
        while (true) {
            val next = nextDue(now) ?: return
            tasks.remove(next)
            val outer = inTask
            inTask = true
            try {
                next.action()
            } finally {
                inTask = outer
            }
        }
    }

    /** Moves the clock forward, running each task at its due time. */
    fun advanceBy(ms: Long) {
        val end = now + ms
        runPending()
        while (true) {
            val next = nextDue(end) ?: break
            now = maxOf(now, next.due)
            runPending()
        }
        now = end
        runPending()
    }

    private fun nextDue(limit: Long): Task? =
        tasks.filter { it.due <= limit }.minWithOrNull(compareBy<Task>({ it.due }, { it.seq }))
}

/** Records what the desktop sends to the glasses. */
class FakeDisplay : GlassesDisplay {
    data class Config(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val z: Int, val colorKey: Boolean)

    class Submit(val id: String, val w: Int, val h: Int, val fingerprint: String, val pixels: ByteArray)

    val configs = mutableListOf<Config>()
    val submits = mutableListOf<Submit>()

    fun submitsOf(id: String) = submits.filter { it.id == id }

    fun clear() {
        configs.clear()
        submits.clear()
    }

    override fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean) {
        configs += Config(id, x, y, width, height, zOrder, colorKey)
    }

    override fun submit(id: String, pixels: ByteArray, width: Int, height: Int, fingerprint: String) {
        submits += Submit(id, width, height, fingerprint, pixels)
    }
}

/**
 * Text as one block per character whose height depends on the character, so different strings
 * give different pixels. Remembers everything it drew.
 */
class FakeText : TextPainter {
    val drawn = mutableListOf<String>()

    override fun measure(text: String, sizePx: Int, bold: Boolean): Int = text.length * sizePx / 2

    override fun draw(target: GrayRaster, text: String, x: Int, y: Int, sizePx: Int, value: Int, bold: Boolean) {
        drawn += text
        val cell = sizePx / 2
        text.forEachIndexed { i, c ->
            target.fillRect(x + i * cell, y, cell - 1, c.code % sizePx + 1, value)
        }
    }
}

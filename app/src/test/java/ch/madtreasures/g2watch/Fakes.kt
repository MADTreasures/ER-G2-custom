package ch.madtreasures.g2watch

import ch.madtreasures.g2watch.desktop.GlassesDisplay
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.TextPainter
import com.faceclaw.app.ArrayByteReader
import com.faceclaw.app.SurfaceCompositor

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

/** Records what the desktop sends to the glasses, and keeps each surface's content like the compositor does. */
class FakeDisplay : GlassesDisplay {
    data class Config(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val z: Int, val colorKey: Boolean)

    /** One update: the [w] × [h] block at ([x], [y]) of surface [id]. */
    class Submit(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val fingerprint: String, val pixels: ByteArray)

    val configs = mutableListOf<Config>()
    val submits = mutableListOf<Submit>()

    /** Every [setDepth] call: surface id and depth. */
    val depths = mutableListOf<Pair<String, Int>>()

    private val surfaces = mutableMapOf<String, GrayRaster>()

    fun submitsOf(id: String) = submits.filter { it.id == id }

    /** What surface [id] holds after all updates so far. */
    fun content(id: String): GrayRaster = surfaces.getValue(id)

    fun clear() {
        configs.clear()
        submits.clear()
    }

    override fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean) {
        configs += Config(id, x, y, width, height, zOrder, colorKey)
        val old = surfaces[id]
        if (old == null || old.width != width || old.height != height) surfaces[id] = GrayRaster(width, height)
    }

    override fun submit(id: String, pixels: ByteArray, x: Int, y: Int, width: Int, height: Int, fingerprint: String) {
        require(pixels.size == width * height)
        val surface = surfaces.getValue(id)
        require(x >= 0 && y >= 0 && x + width <= surface.width && y + height <= surface.height)
        submits += Submit(id, x, y, width, height, fingerprint, pixels)
        for (row in 0 until height) System.arraycopy(pixels, row * width, surface.pixels, (y + row) * surface.width + x, width)
    }

    override fun setDepth(id: String, depth: Int) {
        require(id in surfaces)
        depths += id to depth
    }
}

/**
 * [GlassesDisplay] over Faceclaw's real compositor, used the way [ch.madtreasures.g2watch.glasses.CoreDisplay]
 * uses it inside the session: shows which stereo depth the glasses would get and what each lens shows.
 */
class CompositorDisplay : GlassesDisplay {
    private val compositor = SurfaceCompositor().also { it.configureScreen(SCREEN_W, SCREEN_H) }
    private var composite: SurfaceCompositor.Composite? = null

    /** The depth of the last composite's root display list, as the glasses would run it. */
    val screenDepth: Int get() = composite!!.shellScene.screenDepth

    /** The last composite, before the glasses shift it. */
    val screen: ByteArray get() = composite!!.screenGray

    /** What one lens shows of the last composite, after the glasses ran its root display list. */
    fun lens(right: Boolean): ByteArray = composite!!.let { it.shellScene.preview(it.screenGray, SCREEN_W, SCREEN_H, right) }

    override fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean) {
        val transparency = if (colorKey) SurfaceCompositor.TRANSPARENCY_COLOR_KEY else SurfaceCompositor.TRANSPARENCY_OPAQUE
        compositor.configureSurface(id, x, y, width, height, zOrder, transparency)
    }

    override fun submit(id: String, pixels: ByteArray, x: Int, y: Int, width: Int, height: Int, fingerprint: String) {
        composite = compositor.applyAndComposite(id, ArrayByteReader(pixels), x, y, width, height, fingerprint)
    }

    override fun setDepth(id: String, depth: Int) {
        compositor.setSurfaceDepth(id, depth)
        compositor.setSurfaceVisible(id, true)
        composite = compositor.composite()
    }

    private companion object {
        const val SCREEN_W = 640
        const val SCREEN_H = 480
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

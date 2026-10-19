package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.ThreadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The desktop as last rendered: its visible band and the pointer on it. The glasses get both in one
 * picture, the pointer drawn in and cut off at the stage's edge. For tests and tools; the watch itself
 * shows no picture of the glasses.
 */
class DesktopFrame(
    val band: Rect,
    /** band.w × band.h gray pixels, without the pointer. */
    val pixels: ByteArray,
    /** Increases whenever [pixels] change. */
    val version: Long,
    val pointerX: Int,
    val pointerY: Int,
    /** The pointer as the glasses show it there, normal or negative per pixel ([PointerSprite]). */
    val pointerPixels: ByteArray,
) {
    fun withPointer(x: Int, y: Int, pointerPixels: ByteArray) = DesktopFrame(band, pixels, version, x, y, pointerPixels)
}

/**
 * Owns the desktop on one thread and mirrors it to the glasses as one opaque full-screen surface,
 * with the pointer drawn into it. That surface is the only one, so the screen's stereo depth
 * ([Depth], [setDepth]) always applies, also while the pointer is shown. Moving the pointer re-sends
 * only the block around its old and new place, so Faceclaw's planner sends just the few pixel rows
 * there. Pointer frames are coalesced to at most one per [POINTER_INTERVAL_MS]; the desktop is
 * re-rendered only when something on it changed, and the pointer is redrawn against it at once.
 */
class DesktopController(
    private val text: TextPainter,
    private val scheduler: Scheduler = ThreadScheduler("G2Watch-desktop"),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    depth: Depth = Depth.DEFAULT,
) : AppScreen {
    val layout = DesktopLayout.centered()

    private val desktop = Desktop(layout)
    private val pointer = PointerPosition(layout.stage)

    /** The desktop without the pointer, as [DesktopRenderer] drew it. */
    private val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)

    /** [raster] with the pointer drawn in: what the desktop surface on the glasses holds. */
    private val screen = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
    private val renderer = DesktopRenderer(text)

    // Everything below is only touched on the scheduler thread.
    private var display: GlassesDisplay? = null
    private var desktopDirty = true
    private var desktopVersion = 0L
    private var rasterHash = 0L

    /** The block of [screen] the pointer covers, or null while it is hidden. */
    private var drawnPointer: Rect? = null
    private var sentFingerprint: String? = null
    private var pointerFlushScheduled = false
    private var lastPointerFlushMs = Long.MIN_VALUE / 2
    private var pointerPixels = PointerSprite.normal
    private var apps: AppInput? = null
    private var pointerShown = true
    private var lastAppPointer: Pair<Int, Int>? = null

    private val _frame = MutableStateFlow(
        DesktopFrame(layout.band, ByteArray(layout.band.w * layout.band.h), 0, pointer.x, pointer.y, pointerPixels),
    )

    /** The desktop as last rendered, for tests and tools. */
    val frame: StateFlow<DesktopFrame> = _frame.asStateFlow()

    private val _speed = MutableStateFlow(1f)

    /** Pointer speed factor, adjustable with the crown and in the watch settings. */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _depth = MutableStateFlow(depth)

    /** The screen's stereo depth ("Bildtiefe"), set in the watch settings. */
    val depth: StateFlow<Depth> = _depth.asStateFlow()

    init {
        scheduler.post { flushDesktop() }
    }

    /**
     * Starts mirroring to [display]: configures the surface, sends the current picture and sets the
     * depth. Every session has a compositor of its own, starting at depth 0, so this runs on each attach.
     */
    fun attach(display: GlassesDisplay) = scheduler.post {
        this.display = display
        display.configureSurface(DESKTOP, 0, 0, DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT, DESKTOP_Z, colorKey = false)
        sentFingerprint = null
        desktopDirty = true
        flushDesktop()
        display.setDepth(DESKTOP, _depth.value.disparity)
    }

    fun detach() = scheduler.post { display = null }

    /** Where input for the apps goes: the app host. */
    fun connectApps(input: AppInput) = scheduler.post { apps = input }

    /**
     * Moves the pointer by a delta in glasses pixels. Over an app, pushing on past the top or bottom
     * edge of the visible band scrolls the app's page.
     */
    fun moveBy(dx: Float, dy: Float) = scheduler.post {
        if (pointer.moveBy(dx, dy)) afterPointerMoved()
        val push = pointer.overflowY.roundToInt()
        if (push != 0 && desktop.app != null) apps?.scrollBy(push)
    }

    /** The arrow keys on the bare desktop (from the app host). */
    override fun movePointer(dx: Float, dy: Float) = moveBy(dx, dy)

    fun centerPointer() = scheduler.post {
        pointer.center()
        afterPointerMoved()
    }

    /** A click at the pointer; over an app it goes to the app host. */
    override fun click() = scheduler.post {
        val app = desktop.app
        if (app != null) {
            when (desktop.hitTest(pointer.x, pointer.y)) {
                Target.AppBack -> apps?.back()
                Target.AppTitle -> apps?.openMenu()
                else -> {
                    val area = layout.appArea(app.fullScreen)
                    if (area.contains(pointer.x, pointer.y)) apps?.clickAt(pointer.x - area.x, pointer.y - area.y)
                }
            }
            return@post
        }
        when (desktop.click(pointer.x, pointer.y)) {
            ClickEffect.NONE -> return@post
            ClickEffect.REDRAW -> Unit
            ClickEffect.OPEN_APPS -> {
                apps?.openLauncher()
                return@post
            }
        }
        desktop.updateHover(pointer.x, pointer.y)
        invalidate()
    }

    /** Closes the open window; over an app, back in the app. */
    override fun back() = scheduler.post {
        if (desktop.app != null) {
            apps?.back()
            return@post
        }
        if (desktop.back()) {
            desktop.updateHover(pointer.x, pointer.y)
            invalidate()
        }
    }

    /** Shows [view] (an app with its header) instead of the desktop, or updates it. */
    override fun showApp(view: AppView) = scheduler.post {
        val wasApp = desktop.app != null
        desktop.app = view
        // Shown in pointer mode, hidden in gesture mode; the redraw below takes it in or out.
        pointerShown = view.pointer
        desktop.updateHover(pointer.x, pointer.y)
        if (!wasApp) lastAppPointer = null
        forwardPointer()
        invalidate()
    }

    /** Back to the desktop tiles. */
    override fun closeApp() = scheduler.post {
        if (desktop.app == null) return@post
        desktop.app = null
        pointerShown = true
        desktop.updateHover(pointer.x, pointer.y)
        invalidate()
    }

    /** Tells the app host where the pointer is in the app area, when that changed. */
    private fun forwardPointer() {
        val app = desktop.app ?: return
        val area = layout.appArea(app.fullScreen)
        val at = if (area.contains(pointer.x, pointer.y)) Pair(pointer.x - area.x, pointer.y - area.y) else Pair(-1, -1)
        if (at == lastAppPointer) return
        lastAppPointer = at
        apps?.pointerAt(at.first, at.second)
    }

    fun setSpeed(value: Float) = scheduler.post { applySpeed(value) }

    /** Moves the whole picture nearer or farther; the glasses show it at once. */
    fun setDepth(value: Depth) = scheduler.post {
        if (value == _depth.value) return@post
        _depth.value = value
        display?.setDepth(DESKTOP, value.disparity)
    }

    fun updateStatus(transform: (DesktopStatus) -> DesktopStatus) = scheduler.post {
        val next = transform(desktop.status)
        if (next != desktop.status) {
            desktop.status = next
            invalidate()
        }
    }

    /** Keeps the clock in the top bar current. */
    fun startClock() = scheduler.post { tickClock() }

    private fun tickClock() {
        val t = now()
        val time = t.format(TIME)
        val date = t.format(DATE)
        if (time != desktop.status.time || date != desktop.status.date) {
            desktop.status = desktop.status.copy(time = time, date = date)
            invalidate()
        }
        scheduler.postDelayed(CLOCK_INTERVAL_MS) { tickClock() }
    }

    private fun applySpeed(value: Float) {
        _speed.value = value.coerceIn(PointerMotion.MIN_SPEED, PointerMotion.MAX_SPEED)
    }

    private fun afterPointerMoved() {
        if (desktop.updateHover(pointer.x, pointer.y)) invalidate()
        forwardPointer()
        renderPointer()
        publishPointer()
        schedulePointerFlush()
    }

    /** Draws the pointer against the current desktop; keeps the old array if nothing changed. */
    private fun renderPointer() {
        val next = PointerSprite.render(raster, pointer.x, pointer.y)
        if (!next.contentEquals(pointerPixels)) pointerPixels = next
    }

    private fun invalidate() {
        if (desktopDirty) return
        desktopDirty = true
        scheduler.post { flushDesktop() }
    }

    private fun flushDesktop() {
        if (!desktopDirty) return
        desktopDirty = false
        renderer.render(desktop, raster)
        rasterHash = raster.contentHash()
        renderPointer()
        publishDesktop()
        // What lies under the pointer may have changed; it follows in the same frame, not at the next move.
        raster.pixels.copyInto(screen.pixels)
        drawnPointer = drawPointer()
        send(FULL_SCREEN)
    }

    private fun schedulePointerFlush() {
        if (pointerFlushScheduled) return
        pointerFlushScheduled = true
        val wait = (lastPointerFlushMs + POINTER_INTERVAL_MS - nowMs()).coerceAtLeast(0L)
        scheduler.postDelayed(wait) {
            pointerFlushScheduled = false
            flushPointer()
        }
    }

    /** Moves the pointer in [screen] and sends the block that changed, unless the glasses show it like this already. */
    private fun flushPointer() {
        lastPointerFlushMs = nowMs()
        // Attaching draws everything anew, so without glasses there is nothing to keep up to date.
        if (display == null || fingerprint() == sentFingerprint) return
        val old = drawnPointer
        old?.let { screen.copyFrom(raster, it) }
        val new = drawPointer()
        drawnPointer = new
        val changed = if (old != null && new != null) old.union(new) else old ?: new ?: return
        send(changed)
    }

    /** Draws the pointer into [screen], cut off at the stage's edge; null while hidden. */
    private fun drawPointer(): Rect? =
        if (pointerShown) PointerSprite.drawInto(screen, pointerPixels, pointer.x, pointer.y, layout.stage) else null

    /** Identifies what [screen] holds: the desktop, and the pointer with where it is. */
    private fun fingerprint(): String {
        val base = "desktop:" + java.lang.Long.toHexString(rasterHash)
        return if (pointerShown) "$base:p${pointer.x},${pointer.y}:${PointerSprite.fingerprint(pointerPixels)}" else base
    }

    /** Sends the block [rect] of [screen], unless the glasses already show what it holds now. */
    private fun send(rect: Rect) {
        val d = display ?: return
        val fingerprint = fingerprint()
        if (fingerprint == sentFingerprint) return
        val pixels = if (rect == FULL_SCREEN) screen.pixels.copyOf() else screen.copyRegion(rect.x, rect.y, rect.w, rect.h)
        d.submit(DESKTOP, pixels, rect.x, rect.y, rect.w, rect.h, fingerprint)
        sentFingerprint = fingerprint
        lastPointerFlushMs = nowMs()
    }

    private fun publishDesktop() {
        val band = layout.band
        desktopVersion++
        _frame.value = DesktopFrame(band, raster.copyRegion(band.x, band.y, band.w, band.h), desktopVersion, pointer.x, pointer.y, pointerPixels)
    }

    private fun publishPointer() {
        _frame.value = _frame.value.withPointer(pointer.x, pointer.y, pointerPixels)
    }

    companion object {
        const val DESKTOP = "desktop"
        const val DESKTOP_Z = 0

        /** At most ~30 pointer frames per second; the core drops frames that are superseded anyway. */
        const val POINTER_INTERVAL_MS = 33L
        const val CLOCK_INTERVAL_MS = 10_000L
        const val SPEED_STEP = 0.2f

        private val FULL_SCREEN = Rect(0, 0, DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
        private val DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
    }
}

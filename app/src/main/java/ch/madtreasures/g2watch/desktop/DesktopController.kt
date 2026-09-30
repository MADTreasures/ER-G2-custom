package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.ThreadScheduler
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.host.AppFrame
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.AppScreen
import ch.madtreasures.g2watch.apps.host.GlassesInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The desktop as last rendered: its visible band and the pointer on it, exactly as the glasses get
 * them. For tests and tools; the watch itself shows no picture of the glasses.
 */
class DesktopFrame(
    val band: Rect,
    /** band.w × band.h gray pixels. */
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
 * Owns the desktop on one thread and mirrors it to the glasses. The desktop is an opaque
 * full-screen surface; the pointer is a small color-key surface on top of it. Moving the
 * pointer changes that surface's position and, where it crosses lit pixels, its negative
 * pixels, so Faceclaw's planner sends just the few pixel rows around the old and new position.
 * Pointer frames are coalesced to at most one per [POINTER_INTERVAL_MS]; the desktop is
 * re-rendered only when something on it changed, and the pointer is redrawn against it at once.
 *
 * The "Apps" tile hands the window area to the [AppHost] (03 §5): its pictures arrive as
 * [AppFrame]s ([showApps]), and pointer, clicks, Back and gestures go to it while it is open. In
 * gesture mode the pointer is hidden and the watch touchpad reports gestures instead.
 */
class DesktopController(
    private val text: TextPainter,
    private val scheduler: Scheduler = ThreadScheduler("G2Watch-desktop"),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : AppScreen {
    val layout = DesktopLayout.centered()

    private val desktop = Desktop(layout)
    private val pointer = PointerPosition(layout.band)
    private val raster = GrayRaster(DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT)
    private val renderer = DesktopRenderer(text)

    // Everything below is only touched on the scheduler thread.
    private var display: GlassesDisplay? = null
    private var desktopDirty = true
    private var desktopVersion = 0L
    private var sentDesktopFingerprint: String? = null
    private var pointerFlushScheduled = false
    private var lastPointerFlushMs = Long.MIN_VALUE / 2
    private var pointerPixels = PointerSprite.normal
    private var sentPointerFingerprint: String? = null
    private var sentPointerX = 0
    private var sentPointerY = 0
    private var pointerShown = true
    private var apps: AppHost? = null

    private val _frame = MutableStateFlow(
        DesktopFrame(layout.band, ByteArray(layout.band.w * layout.band.h), 0, pointer.x, pointer.y, pointerPixels),
    )

    /** The desktop as last rendered, for tests and tools. */
    val frame: StateFlow<DesktopFrame> = _frame.asStateFlow()

    private val _speed = MutableStateFlow(1f)

    /** Pointer speed factor, adjustable with the crown and in the watch settings. */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _touchMode = MutableStateFlow(InputMode.POINTER)

    /** What the watch touchpad is now: a pointer, or gestures for an app in gesture mode. */
    val touchMode: StateFlow<InputMode> = _touchMode.asStateFlow()

    init {
        scheduler.post { flushDesktop() }
    }

    /** The app host behind the "Apps" tile; it draws through [showApps]. */
    fun connectApps(host: AppHost) = scheduler.post {
        apps = host
        host.screen = this
    }

    /** Starts mirroring to [display]; configures both surfaces and sends the current picture. */
    fun attach(display: GlassesDisplay) = scheduler.post {
        this.display = display
        display.configureSurface(DESKTOP, 0, 0, DesktopLayout.SCREEN_WIDTH, DesktopLayout.SCREEN_HEIGHT, DESKTOP_Z, colorKey = false)
        configurePointer(display)
        if (!pointerShown) display.setSurfaceVisible(POINTER, false)
        sentDesktopFingerprint = null
        sentPointerFingerprint = null
        desktopDirty = true
        flushDesktop()
    }

    fun detach() = scheduler.post { display = null }

    /** Moves the pointer by a delta in glasses pixels. */
    fun moveBy(dx: Float, dy: Float) = scheduler.post {
        if (pointer.moveBy(dx, dy)) afterPointerMoved()
    }

    fun centerPointer() = scheduler.post {
        pointer.center()
        afterPointerMoved()
    }

    /** A click at the pointer. */
    fun click() = scheduler.post { clickAtPointer() }

    private fun clickAtPointer() {
        if (desktop.appsOpen) {
            val host = apps ?: return
            when (desktop.hitTest(pointer.x, pointer.y)) {
                Target.AppBack -> host.back()
                Target.AppMenu -> host.openMenu()
                Target.AppArea -> desktop.appArea.let { host.click(pointer.x - it.x, pointer.y - it.y) }
                else -> Unit
            }
            return
        }
        when (desktop.click(pointer.x, pointer.y)) {
            ClickEffect.NONE -> return
            ClickEffect.REDRAW -> Unit
            ClickEffect.OPEN_APPS -> apps?.open()
        }
        desktop.updateHover(pointer.x, pointer.y)
        invalidate()
    }

    /** Back: closes the open window; in the apps it goes to the app host (previous page, launcher, desktop). */
    fun back() = scheduler.post { backNow() }

    private fun backNow() {
        if (desktop.appsOpen) {
            apps?.back()
            return
        }
        if (desktop.back()) {
            desktop.updateHover(pointer.x, pointer.y)
            invalidate()
        }
    }

    /** "Fenster schließen" in the watch settings: closes a window, or leaves the apps altogether. */
    fun closeWindow() = scheduler.post {
        if (desktop.appsOpen) apps?.leave() else backNow()
    }

    /**
     * A gesture from a temple or the ring (03 §5.1). The apps get every gesture; the desktop itself
     * knows tap (click at the pointer) and double tap (close the window).
     */
    fun glassesGesture(input: GlassesInput) = scheduler.post {
        val host = apps
        if (desktop.appsOpen && host != null) {
            host.gesture(input.gesture, input.source)
            return@post
        }
        when (input.gesture) {
            GestureKind.CLICK -> clickAtPointer()
            GestureKind.DOUBLE_CLICK -> backNow()
            else -> Unit
        }
    }

    /** A gesture of the watch touchpad in gesture mode ([touchMode]); only apps use those. */
    fun watchGesture(kind: GestureKind) = scheduler.post {
        if (desktop.appsOpen) apps?.gesture(kind, InputSource.WATCH)
    }

    // --- AppScreen, called on the app thread --------------------------------------------------------

    override fun showApps(frame: AppFrame) = scheduler.post {
        if (!desktop.appsOpen) return@post
        desktop.appFrame = frame
        setPointerShown(frame.input == InputMode.POINTER)
        desktop.updateHover(pointer.x, pointer.y)
        invalidate()
    }

    override fun closeApps() = scheduler.post {
        desktop.closeApps()
        setPointerShown(true)
        desktop.updateHover(pointer.x, pointer.y)
        invalidate()
    }

    /** Shows or hides the pointer surface, and switches the watch touchpad along. */
    private fun setPointerShown(shown: Boolean) {
        _touchMode.value = if (shown) InputMode.POINTER else InputMode.GESTURES
        if (shown == pointerShown) return
        pointerShown = shown
        val d = display ?: return
        d.setSurfaceVisible(POINTER, shown)
        if (shown) {
            sentPointerFingerprint = null
            sendPointer(d)
        }
    }

    fun setSpeed(value: Float) = scheduler.post { applySpeed(value) }

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
        if (desktop.appsOpen) desktop.appArea.let { apps?.pointerAt(pointer.x - it.x, pointer.y - it.y) }
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
        renderPointer()
        publishDesktop()
        val d = display ?: return
        val fingerprint = "desktop:" + java.lang.Long.toHexString(raster.contentHash())
        if (fingerprint != sentDesktopFingerprint) {
            d.submit(DESKTOP, raster.pixels.copyOf(), raster.width, raster.height, fingerprint)
            sentDesktopFingerprint = fingerprint
        }
        // What lies under the pointer may have changed; it follows now, not at the next move.
        sendPointer(d)
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

    private fun flushPointer() {
        lastPointerFlushMs = nowMs()
        val d = display ?: return
        sendPointer(d)
    }

    /** Sends the pointer unless the glasses already show it like this, at this position (or it is hidden). */
    private fun sendPointer(d: GlassesDisplay) {
        if (!pointerShown) return
        val fingerprint = PointerSprite.fingerprint(pointerPixels)
        if (fingerprint == sentPointerFingerprint && pointer.x == sentPointerX && pointer.y == sentPointerY) return
        configurePointer(d)
        d.submit(POINTER, pointerPixels, PointerSprite.width, PointerSprite.height, fingerprint)
        sentPointerFingerprint = fingerprint
        sentPointerX = pointer.x
        sentPointerY = pointer.y
        lastPointerFlushMs = nowMs()
    }

    private fun configurePointer(d: GlassesDisplay) {
        d.configureSurface(POINTER, pointer.x, pointer.y, PointerSprite.width, PointerSprite.height, POINTER_Z, colorKey = true)
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
        const val POINTER = "pointer"
        const val DESKTOP_Z = 0
        const val POINTER_Z = 100

        /** At most ~30 pointer frames per second; the core drops frames that are superseded anyway. */
        const val POINTER_INTERVAL_MS = 33L
        const val CLOCK_INTERVAL_MS = 10_000L
        const val SPEED_STEP = 0.2f

        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
        private val DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
    }
}

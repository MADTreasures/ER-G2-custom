package ch.madtreasures.g2watch.desktop

/** What the pointer can be over: the header of the app on the glasses. */
sealed interface Target {
    /** The back arrow in the header of an app. */
    data object AppBack : Target

    /** The title in the header of an app: opens the app menu. */
    data object AppTitle : Target
}

/** Live values shown in the top bar. */
data class DesktopStatus(
    val time: String = "--:--",
    val watchBattery: Int? = null,
    val glassesBattery: Int? = null,
    val glassesCharging: Boolean = false,
)

/**
 * Geometry of the desktop on the 640×480 glasses screen. [band] is the part the wearer actually
 * sees: like Faceclaw's default layout a 288 px high strip in the middle, because the optics do not
 * show the whole panel height. Everything, the pointer included, lives in [stage], the middle 576 px
 * of the band: exactly where Even's firmware puts its own picture at the middle depth (x 32…607).
 * Stock never lights a pixel outside x 24…615, so the panel edges may look different to each eye
 * (each lens has its own factory offset), and a stereo depth of ±16 crops 8 columns per lens.
 */
class DesktopLayout(val band: Rect) {
    val stage = Rect(band.x + APP_AREA_X, band.y, APP_AREA_WIDTH, band.h)
    val topBar = Rect(stage.x, band.y, stage.w, TOP_BAR_HEIGHT)

    /** In an app's header: the back arrow and the title (app name and page). */
    val appBack = Rect(stage.x, band.y, 36, TOP_BAR_HEIGHT)
    val appTitle = Rect(appBack.right + 4, band.y, 224, TOP_BAR_HEIGHT)

    /** Where an app draws: 576 × 260 below the header, or 576 × 288 without it (02 §4). */
    fun appArea(fullScreen: Boolean): Rect =
        if (fullScreen) Rect(band.x + APP_AREA_X, band.y, APP_AREA_WIDTH, band.h)
        else Rect(band.x + APP_AREA_X, band.y + TOP_BAR_HEIGHT, APP_AREA_WIDTH, band.h - TOP_BAR_HEIGHT)

    companion object {
        const val SCREEN_WIDTH = 640
        const val SCREEN_HEIGHT = 480
        const val BAND_HEIGHT = 288
        const val TOP_BAR_HEIGHT = 28
        const val APP_AREA_X = 32
        const val APP_AREA_WIDTH = 576

        /** Space between the stage's edge and the top bar's texts, the same as a page's margin. */
        const val BAR_MARGIN = 16

        /** Faceclaw's default: the band centred vertically (y = 96). */
        fun centered(): DesktopLayout =
            DesktopLayout(Rect(0, (SCREEN_HEIGHT - BAND_HEIGHT) / 2, SCREEN_WIDTH, BAND_HEIGHT))
    }
}

/**
 * Desktop state: the app on the glasses and what the pointer hovers in its header. Not thread-safe;
 * [DesktopController] owns it on its own thread.
 */
class Desktop(val layout: DesktopLayout) {
    /** The app on the glasses, from the app host: the launcher at home. Null only until the host shows it. */
    var app: AppView? = null
    var hover: Target? = null
        private set
    var status: DesktopStatus = DesktopStatus()

    /** The element at ([x], [y]): the back arrow or the title of an app's header. The launcher has neither. */
    fun hitTest(x: Int, y: Int): Target? {
        val view = app ?: return null
        if (view.fullScreen || view.home) return null
        if (layout.appBack.contains(x, y)) return Target.AppBack
        if (layout.appTitle.contains(x, y)) return Target.AppTitle
        return null
    }

    /** Updates the hover highlight; true if it changed (the desktop needs a redraw). */
    fun updateHover(x: Int, y: Int): Boolean {
        val target = hitTest(x, y)
        if (target == hover) return false
        hover = target
        return true
    }
}

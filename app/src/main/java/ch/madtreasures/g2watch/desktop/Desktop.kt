package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.apps.host.AppFrame

/**
 * The little programs on the desktop. [APPS] opens the app host's launcher (03 §5); the pointer
 * settings that had a tile here are in the watch settings (speed, centre).
 */
enum class AppId(val title: String) {
    APPS("Apps"),
    CLOCK("Uhr"),
    NOTE("Notiz"),
    COUNTER("Zähler"),
    INFO("Info"),
    HELP("Hilfe"),
}

enum class ButtonId(val label: String) {
    MINUS("−"),
    PLUS("+"),
    RESET("0"),
}

/** What the pointer can be over. */
sealed interface Target {
    data class Tile(val app: AppId) : Target
    data object Close : Target
    data class Button(val id: ButtonId) : Target

    /** The "‹" in the header while the apps are open. */
    data object AppBack : Target

    /** The page name in the header: opens the app menu. */
    data object AppMenu : Target

    /** The app area; the app host decides what lies there. */
    data object AppArea : Target
}

/** What a click did, beyond redrawing the desktop. */
enum class ClickEffect { NONE, REDRAW, OPEN_APPS }

/** Live values shown in the top bar and the info window. */
data class DesktopStatus(
    val time: String = "--:--",
    val date: String = "",
    val watchBattery: Int? = null,
    val glassesBattery: Int? = null,
    val glassesCharging: Boolean = false,
    val connection: String = "nicht verbunden",
    val firmware: String = "–",
)

/**
 * Geometry of the desktop on the 640×480 glasses screen. Everything lives in [band], the part
 * the wearer actually sees: like Faceclaw's default layout a 288 px high strip in the middle,
 * because the optics do not show the whole panel height.
 */
class DesktopLayout(val band: Rect) {
    val topBar = Rect(band.x, band.y, band.w, TOP_BAR_HEIGHT)
    private val content = Rect(band.x, band.y + TOP_BAR_HEIGHT, band.w, band.h - TOP_BAR_HEIGHT)

    val tiles: List<Pair<AppId, Rect>> = run {
        val cols = 3
        val rows = 2
        val tileW = 176
        val tileH = 100
        val gap = 20
        val left = content.x + (content.w - (cols * tileW + (cols - 1) * gap)) / 2
        val top = content.y + (content.h - (rows * tileH + (rows - 1) * gap)) / 2
        AppId.entries.mapIndexed { i, app ->
            app to Rect(left + (i % cols) * (tileW + gap), top + (i / cols) * (tileH + gap), tileW, tileH)
        }
    }

    val window = Rect(content.x + 28, content.y + 4, content.w - 56, content.h - 12)

    /** Where the app host's pages go: below the header (576 × 260), or the whole band without it. */
    val appArea = Rect(band.x + (band.w - APP_WIDTH) / 2, content.y, APP_WIDTH, content.h)
    val appAreaFull = Rect(appArea.x, band.y, APP_WIDTH, band.h)

    /** Header targets while the apps are open: "‹" and the page name. */
    val appBack = Rect(band.x, band.y, 44, TOP_BAR_HEIGHT)
    val appTitle = Rect(band.x + 44, band.y, band.w / 2 - 44 - 40, TOP_BAR_HEIGHT)
    val titleBar = Rect(window.x, window.y, window.w, TITLE_HEIGHT)
    val closeButton = Rect(window.right - 40, window.y + 3, 36, TITLE_HEIGHT - 6)
    val windowBody = Rect(window.x + 12, window.y + TITLE_HEIGHT + 8, window.w - 24, window.h - TITLE_HEIGHT - 16)

    /** The clickable buttons inside the window of [app]. */
    fun buttons(app: AppId): List<Pair<ButtonId, Rect>> {
        val row = windowBody.bottom - BUTTON_HEIGHT
        return when (app) {
            AppId.COUNTER -> buttonRow(row, listOf(ButtonId.MINUS, ButtonId.RESET, ButtonId.PLUS), 90)
            else -> emptyList()
        }
    }

    private fun buttonRow(y: Int, ids: List<ButtonId>, width: Int): List<Pair<ButtonId, Rect>> {
        val gap = 24
        val total = ids.size * width + (ids.size - 1) * gap
        val left = windowBody.x + (windowBody.w - total) / 2
        return ids.mapIndexed { i, id -> id to Rect(left + i * (width + gap), y, width, BUTTON_HEIGHT) }
    }

    companion object {
        const val SCREEN_WIDTH = 640
        const val SCREEN_HEIGHT = 480
        const val BAND_HEIGHT = 288
        const val TOP_BAR_HEIGHT = 28
        const val TITLE_HEIGHT = 32
        const val BUTTON_HEIGHT = 40
        const val APP_WIDTH = 576

        /** Faceclaw's default: the band centred vertically (y = 96). */
        fun centered(): DesktopLayout =
            DesktopLayout(Rect(0, (SCREEN_HEIGHT - BAND_HEIGHT) / 2, SCREEN_WIDTH, BAND_HEIGHT))
    }
}

/**
 * Desktop state: which window is open, what the pointer hovers, and the demo apps' data. While
 * [AppId.APPS] is open, the window area belongs to the app host: [appFrame] is its latest picture.
 * Not thread-safe; [DesktopController] owns it on its own thread.
 */
class Desktop(val layout: DesktopLayout) {
    var openApp: AppId? = null
        private set
    var hover: Target? = null
        private set
    var counter: Int = 0
        private set
    var status: DesktopStatus = DesktopStatus()

    /** The app host's picture while the apps are open; null until the first one arrives. */
    var appFrame: AppFrame? = null

    val appsOpen: Boolean get() = openApp == AppId.APPS

    /** The part of the screen the app host draws into right now. */
    val appArea: Rect get() = if (appFrame?.fullscreen == true) layout.appAreaFull else layout.appArea

    /** The element at ([x], [y]). An open window is modal: only its own controls answer. */
    fun hitTest(x: Int, y: Int): Target? {
        val app = openApp
        if (app == AppId.APPS) {
            val fullscreen = appFrame?.fullscreen == true
            return when {
                !fullscreen && layout.appBack.contains(x, y) -> Target.AppBack
                !fullscreen && appFrame?.hasMenu == true && layout.appTitle.contains(x, y) -> Target.AppMenu
                appArea.contains(x, y) -> Target.AppArea
                else -> null
            }
        }
        if (app != null) {
            if (layout.closeButton.contains(x, y)) return Target.Close
            return layout.buttons(app).firstOrNull { it.second.contains(x, y) }?.let { Target.Button(it.first) }
        }
        return layout.tiles.firstOrNull { it.second.contains(x, y) }?.let { Target.Tile(it.first) }
    }

    /** Updates the hover highlight; true if it changed (the desktop needs a redraw). */
    fun updateHover(x: Int, y: Int): Boolean {
        val target = hitTest(x, y)
        if (target == hover) return false
        hover = target
        return true
    }

    /** A click on the desktop itself; while the apps are open, [DesktopController] asks the app host instead. */
    fun click(x: Int, y: Int): ClickEffect = when (val target = hitTest(x, y)) {
        null, Target.AppBack, Target.AppMenu, Target.AppArea -> ClickEffect.NONE
        is Target.Tile -> {
            openApp = target.app
            if (target.app == AppId.APPS) ClickEffect.OPEN_APPS else ClickEffect.REDRAW
        }
        Target.Close -> {
            openApp = null
            ClickEffect.REDRAW
        }
        is Target.Button -> {
            when (target.id) {
                ButtonId.MINUS -> counter--
                ButtonId.PLUS -> counter++
                ButtonId.RESET -> counter = 0
            }
            ClickEffect.REDRAW
        }
    }

    /** Closes the open window; true if there was one. The apps close through [closeApps]. */
    fun back(): Boolean {
        if (openApp == null || openApp == AppId.APPS) return false
        openApp = null
        return true
    }

    /** The app host is done: back to the tiles. */
    fun closeApps() {
        if (openApp == AppId.APPS) openApp = null
        appFrame = null
    }
}

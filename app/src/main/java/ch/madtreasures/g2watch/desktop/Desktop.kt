package ch.madtreasures.g2watch.desktop

/**
 * The tiles on the desktop. "Apps" opens the launcher of the app host; the pointer speed that used to
 * have a window of its own is set on the watch (crown, settings).
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

    /** The back arrow in the header of an app. */
    data object AppBack : Target

    /** The title in the header of an app: opens the app menu. */
    data object AppTitle : Target
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

    /** In an app's header: the back arrow and the title (app name and page). */
    val appBack = Rect(band.x + APP_AREA_X - 8, band.y, 36, TOP_BAR_HEIGHT)
    val appTitle = Rect(appBack.right + 4, band.y, 232, TOP_BAR_HEIGHT)

    /** Where an app draws: 576 × 260 below the header, or 576 × 288 without it (02 §4). */
    fun appArea(fullScreen: Boolean): Rect =
        if (fullScreen) Rect(band.x + APP_AREA_X, band.y, APP_AREA_WIDTH, band.h)
        else Rect(band.x + APP_AREA_X, band.y + TOP_BAR_HEIGHT, APP_AREA_WIDTH, band.h - TOP_BAR_HEIGHT)
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
        const val APP_AREA_X = 32
        const val APP_AREA_WIDTH = 576

        /** Faceclaw's default: the band centred vertically (y = 96). */
        fun centered(): DesktopLayout =
            DesktopLayout(Rect(0, (SCREEN_HEIGHT - BAND_HEIGHT) / 2, SCREEN_WIDTH, BAND_HEIGHT))
    }
}

/**
 * Desktop state: which window is open, what the pointer hovers, and the demo apps' data.
 * Not thread-safe; [DesktopController] owns it on its own thread.
 */
class Desktop(val layout: DesktopLayout) {
    var openApp: AppId? = null
        private set

    /** The app on the glasses (from the app host); while set, the desktop shows it instead of the tiles. */
    var app: AppView? = null
    var hover: Target? = null
        private set
    var counter: Int = 0
        private set
    var status: DesktopStatus = DesktopStatus()

    /** The element at ([x], [y]). An open window is modal: only its own controls answer. */
    fun hitTest(x: Int, y: Int): Target? {
        this.app?.let { view ->
            if (view.fullScreen) return null
            if (layout.appBack.contains(x, y)) return Target.AppBack
            if (layout.appTitle.contains(x, y)) return Target.AppTitle
            return null
        }
        val app = openApp
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

    fun click(x: Int, y: Int): ClickEffect = when (val target = hitTest(x, y)) {
        null, Target.AppBack, Target.AppTitle -> ClickEffect.NONE
        Target.Tile(AppId.APPS) -> ClickEffect.OPEN_APPS
        is Target.Tile -> {
            openApp = target.app
            ClickEffect.REDRAW
        }
        Target.Close -> {
            openApp = null
            ClickEffect.REDRAW
        }
        is Target.Button -> when (target.id) {
            ButtonId.MINUS -> {
                counter--
                ClickEffect.REDRAW
            }
            ButtonId.PLUS -> {
                counter++
                ClickEffect.REDRAW
            }
            ButtonId.RESET -> {
                counter = 0
                ClickEffect.REDRAW
            }
        }
    }

    /** Closes the open window; true if there was one. */
    fun back(): Boolean {
        if (openApp == null) return false
        openApp = null
        return true
    }
}

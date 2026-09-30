package ch.madtreasures.g2watch.desktop

/**
 * What the glasses show while an app (or the launcher) has the screen: the header with back arrow,
 * title and page name, and below it the app area the app host drew. Immutable; the app host hands
 * a new one to [DesktopController.showApp] whenever the picture changes.
 */
class AppView(
    /** App name, shown bold next to the back arrow. */
    val title: String,
    /** Name of the page, shown after the title when it differs from it. */
    val subtitle: String?,
    /** No header: the app area covers the whole visible band. */
    val fullScreen: Boolean,
    /** [width] × [height] grey pixels of the app area. */
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    /** Whether the pointer is shown (pointer mode) or hidden (gesture mode). */
    val pointer: Boolean,
) {
    init {
        require(pixels.size == width * height)
    }
}

/**
 * Where the desktop sends the wearer's input for the app on screen: the app host. Called on the
 * desktop thread; the host moves the work to its own thread. Coordinates are relative to the app area.
 */
interface AppInput {
    /** The "Apps" tile: open the launcher. */
    fun openLauncher()

    /** The pointer is at ([x], [y]) in the app area; (-1, -1) when it left the area. */
    fun pointerAt(x: Int, y: Int)

    /** A click (double tap on the watch) at ([x], [y]) in the app area. */
    fun clickAt(x: Int, y: Int)

    /** The pointer pushes against the top (negative) or bottom edge: scroll the page by [dy] pixels. */
    fun scrollBy(dy: Int)

    /** The back arrow in the header, or back from the watch. */
    fun back()

    /** A click on the title in the header: the app menu. */
    fun openMenu()
}

/** What the app host drives on the desktop; implemented by [DesktopController]. Any thread. */
interface AppScreen {
    /** Shows (or updates) the app on the glasses. */
    fun showApp(view: AppView)

    /** Back to the desktop tiles. */
    fun closeApp()

    /** A tap on a temple while no app is open: a click at the pointer. */
    fun click()

    /** A double tap on a temple while no app is open: closes the open window. */
    fun back()
}

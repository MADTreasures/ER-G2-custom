package ch.madtreasures.g2watch.desktop

/**
 * What the glasses show: the header with back arrow, title and page name, and below it the app area
 * the app host drew; at home, the launcher. Immutable; the app host hands a new one to
 * [DesktopController.showApp] whenever the picture changes.
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
    /** The launcher, the glasses' home: nothing lies below it, so its header has no back arrow and no menu. */
    val home: Boolean = false,
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
    /** A click while nothing is on the glasses: the launcher again. */
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

    /**
     * A finger came down on the watch's face, or a mouse button went down ([click]), with the pointer at
     * ([x], [y]) in the app area. Held on an app of the launcher, it lifts that app to move it (03 §5);
     * a mouse button clicks at once elsewhere, and on an app of the launcher when it comes up soon enough.
     */
    fun pressAt(x: Int, y: Int, click: Boolean) = Unit

    /** The finger or the mouse button came up: an app being moved lands where it is. */
    fun release() = Unit
}

/** What the app host drives on the desktop; implemented by [DesktopController]. Any thread. */
interface AppScreen {
    /** Shows (or updates) the app on the glasses. */
    fun showApp(view: AppView)

    /** Nothing to show (the launcher is not up yet, or it crashed): the top bar alone. */
    fun closeApp()
}

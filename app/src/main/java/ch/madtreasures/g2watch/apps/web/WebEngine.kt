package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebField
import ch.madtreasures.g2watch.apps.WebState
import ch.madtreasures.g2watch.desktop.GrayRaster

/**
 * A page to open ([ch.madtreasures.g2watch.apps.WebAction.Open]) and paint at [width] × [height], the
 * size of its image block.
 */
data class WebRequest(
    val url: String,
    val width: Int,
    val height: Int,
    val reader: Boolean = false,
    val contrast: WebContrast = WebContrast.OUTLINE,
)

/** What a page reports, the fields of [ch.madtreasures.g2watch.apps.AppEvent.Web]. */
data class WebStatus(
    val state: WebState,
    val url: String,
    val title: String = "",
    val progress: Int = 0,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val reader: Boolean = false,
    val readable: Boolean = false,
    val field: WebField? = null,
    val message: String? = null,
)

/** What an open page reports; called from the engine's threads. */
interface WebListener {
    /** A new picture of exactly the requested size; the engine never touches it again. */
    fun onFrame(raster: GrayRaster)

    fun onState(status: WebStatus)
}

/**
 * An open web page; its methods may be called from any thread. Coordinates and distances are pixels
 * of the picture.
 */
interface WebPage {
    /** Loads [url] as the next page of the history; [reader] as in [WebRequest]. */
    fun open(url: String, reader: Boolean)

    fun back()

    fun forward()

    fun reload()

    fun scrollBy(dy: Int)

    fun tap(x: Int, y: Int)

    /** Writes [text] into the field with the cursor; with [enter] then presses Enter. */
    fun type(text: String, enter: Boolean)

    fun setReader(on: Boolean)

    fun setContrast(contrast: WebContrast)

    /** False while the app is hidden or the glasses are away: no pictures, the page rests. */
    fun setActive(active: Boolean)

    /** Ends the page and frees what it holds; no callbacks afterwards. */
    fun release()
}

/**
 * The browser part of the platform (05 §10, M7): pages in image blocks. On the watch it is
 * [GeckoWebEngine] (GeckoView paints into an unseen surface, web-raster makes the glasses' picture);
 * tests use fakes.
 */
interface WebEngine {
    fun open(request: WebRequest, listener: WebListener): WebPage

    companion object {
        /** Where there is no browser engine: every page reports an error. */
        val NONE: WebEngine = object : WebEngine {
            override fun open(request: WebRequest, listener: WebListener): WebPage {
                listener.onState(WebStatus(WebState.ERROR, request.url, message = "Web-Seiten gibt es hier nicht"))
                return Closed
            }
        }
    }

    /** A page that does nothing, for engines that could not open one. */
    object Closed : WebPage {
        override fun open(url: String, reader: Boolean) = Unit

        override fun back() = Unit

        override fun forward() = Unit

        override fun reload() = Unit

        override fun scrollBy(dy: Int) = Unit

        override fun tap(x: Int, y: Int) = Unit

        override fun type(text: String, enter: Boolean) = Unit

        override fun setReader(on: Boolean) = Unit

        override fun setContrast(contrast: WebContrast) = Unit

        override fun setActive(active: Boolean) = Unit

        override fun release() = Unit
    }
}

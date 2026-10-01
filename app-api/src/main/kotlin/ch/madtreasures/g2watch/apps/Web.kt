package ch.madtreasures.g2watch.apps

/**
 * What an app asks of the web page in an image block ([AppContext.web], 05 §10). The watch loads the
 * page in its browser engine, paints it into an unseen surface of the block's size and turns it into
 * the glasses' picture (dark ground, readable text). Coordinates and distances are pixels of the block.
 * Since interface version 2.
 */
sealed interface WebAction {
    /** The JSON name (`"action"`). */
    val json: String

    /**
     * Loads [url] (`https://` or `http://`) in the block, as the next page of its history. With [reader]
     * pages that are articles show in reading mode: their text alone, large, light on dark; this holds
     * for the following pages too, until [Reader] changes it.
     */
    data class Open(val url: String, val reader: Boolean = false) : WebAction {
        override val json get() = "open"
    }

    /** The previous page of the block's history. */
    data object Back : WebAction {
        override val json get() = "back"
    }

    data object Forward : WebAction {
        override val json get() = "forward"
    }

    data object Reload : WebAction {
        override val json get() = "reload"
    }

    /** Scrolls the page by [dy] pixels, positive towards its end, as a finger would. */
    data class Scroll(val dy: Int) : WebAction {
        override val json get() = "scroll"
    }

    /** Touches the page at ([x], [y]) like a finger: follows a link, presses a button, puts the cursor in a field. */
    data class Tap(val x: Int, val y: Int) : WebAction {
        override val json get() = "tap"
    }

    /**
     * Writes [text] into the field that has the cursor ([AppEvent.Web.field]), in place of what it held;
     * with [enter] it then presses Enter, which sends a search or a form.
     */
    data class Type(val text: String, val enter: Boolean = true) : WebAction {
        override val json get() = "type"
    }

    /** Reading mode on or off, for this page and the following ones. */
    data class Reader(val on: Boolean) : WebAction {
        override val json get() = "reader"
    }

    /** How text on pictures is set apart ([WebContrast]), from the next picture on. */
    data class Contrast(val contrast: WebContrast) : WebAction {
        override val json get() = "contrast"
    }

    /** Ends the page and frees the engine; the block keeps its last picture. */
    data object Stop : WebAction {
        override val json get() = "stop"
    }
}

/** State of a web page, as [AppEvent.Web] reports it. */
enum class WebState(val json: String) {
    /** Loading a page; pictures may already come. */
    LOADING("loading"),

    /** The page is there. */
    READY("ready"),

    /** The page could not be loaded or its engine ended; [AppEvent.Web.message] says why (German). */
    ERROR("error"),
    ;

    companion object {
        fun of(json: String?): WebState? = entries.firstOrNull { it.json == json }
    }
}

/**
 * A text field of the page that has the cursor ([AppEvent.Web.field]): ask the wearer with
 * [AppContext.askText] and send the answer with [WebAction.Type].
 */
data class WebField(
    /** What the field is for: its label, placeholder or name; may be empty. */
    val label: String,
    /** What it holds now; always empty for a password field. */
    val value: String = "",
    val password: Boolean = false,
    /** A text area with several lines. */
    val multiline: Boolean = false,
)

/**
 * How text on a busy ground (a photo, a crowded window) is set apart on the glasses (05 §10.1);
 * [label] is the German name for a setting.
 */
enum class WebContrast(val json: String, val label: String) {
    /** Dark letters with a thin light outline; the picture stays visible around them. The default. */
    OUTLINE("outline", "Umriss"),

    /** Light letters with a see-through rim. */
    HALO("halo", "Leuchtschrift"),

    /** A light plate behind the line with dark letters. */
    PLATE("plate", "Platte"),
    ;

    companion object {
        fun of(json: String?): WebContrast? = entries.firstOrNull { it.json == json }
    }
}

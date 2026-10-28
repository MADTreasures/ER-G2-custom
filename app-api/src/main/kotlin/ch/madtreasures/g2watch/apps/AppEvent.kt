package ch.madtreasures.g2watch.apps

/**
 * Something that happens to an app (02 §6.1). The JSON form (see [AppJson]) is the same for watch and
 * remote apps. Apps ignore kinds they do not know, so the platform can add new ones.
 */
sealed interface AppEvent {
    /** The session started; show a page within 2 s. [Visible] follows at once. */
    data object Start : AppEvent

    data object Visible : AppEvent

    /** Another app or the launcher covers this one; it keeps running. */
    data object Hidden : AppEvent

    /** The session ends; the app has 1 s to clean up. */
    data object Stop : AppEvent

    /** A button without a target was clicked. */
    data class Click(val page: String, val block: String) : AppEvent

    /** The host already flipped the switch on the glasses; set it back with a patch to refuse. */
    data class Toggle(val page: String, val block: String, val on: Boolean) : AppEvent

    /** The host already ticked (or unticked) row [index] of a checklist. */
    data class Check(val page: String, val block: String, val index: Int, val done: Boolean) : AppEvent

    /** A button with a page target was clicked; the host already shows [to]. */
    data class Navigate(val from: String, val to: String, val block: String) : AppEvent

    /** Back on [page]; the host changes the page (or ends the app on its first page) right after. */
    data class Back(val page: String) : AppEvent

    /** The wearer chose one of the app's own entries in the app menu. */
    data class Menu(val item: String) : AppEvent

    /** A raw gesture: with [InputMode.GESTURES], or after subscribing to [Sensor.GESTURES]. */
    data class Gesture(val gesture: GestureKind, val source: InputSource) : AppEvent

    data class Timer(val tag: String) : AppEvent

    /** Acceleration from the glasses (kind "sensor", sensor "imu"). From M6. */
    data class Imu(val x: Float, val y: Float, val z: Float, val t: Long) : AppEvent

    /** Heading in degrees (kind "sensor", sensor "compass"). From M6. */
    data class Compass(val heading: Float, val t: Long) : AppEvent

    /**
     * Position of the watch from its GPS (kind "sensor", sensor "location", since v0.12.0): [acc] in metres,
     * [t] in ms since 1970. Since interface version 5 also the speed in m/s, the direction of travel in
     * degrees (0 = north, clockwise) and the height above sea level in metres, each null when the watch
     * does not know it. The constructor of version 1 to 4 stays ([JvmOverloads]), for packages built against them.
     */
    data class Location @JvmOverloads constructor(
        val lat: Double,
        val lon: Double,
        val acc: Float,
        val t: Long,
        val speed: Float? = null,
        val bearing: Float? = null,
        val alt: Double? = null,
    ) : AppEvent

    /** 50 ms of 16 kHz mono PCM from the glasses' microphone. Never sent as JSON. From M6. */
    class Audio(val pcm: ShortArray, val seq: Int) : AppEvent {
        override fun toString() = "Audio(seq=$seq, ${pcm.size} samples)"
    }

    /** The wearer's answer to [AppContext.askText] with [tag]; [text] is null when the wearer cancelled. */
    data class TextInput(val tag: String, val text: String?) : AppEvent

    /**
     * The video in image block [block] changed its state, or (while playing) about once a second, with
     * where it is. [durationMs] is 0 when unknown (live); [message] explains [VideoState.ERROR].
     */
    data class Video(
        val block: String,
        val state: VideoState,
        val positionMs: Long,
        val durationMs: Long,
        val message: String? = null,
    ) : AppEvent

    /** A command was refused ([CommandException.code]). Only remote apps get this; watch apps see it in the log. */
    data class Error(val command: String, val code: String, val message: String) : AppEvent

    /**
     * The web page in image block [block] ([AppContext.web]) changed: it loads, is there or failed, has
     * a new address or title, a text field got the cursor ([field]). Its pictures go into the block by
     * themselves. Since interface version 2.
     */
    data class Web(
        val block: String,
        val state: WebState,
        val url: String,
        val title: String = "",
        /** 0–100 while loading. */
        val progress: Int = 0,
        val canBack: Boolean = false,
        val canForward: Boolean = false,
        /** The page shows in reading mode. */
        val reader: Boolean = false,
        /** The page is an article that reading mode can show. */
        val readable: Boolean = false,
        /** The text field that has the cursor, or null. */
        val field: WebField? = null,
        /** Why the page failed ([WebState.ERROR]), or a note such as a link the watch cannot open (German). */
        val message: String? = null,
    ) : AppEvent

    /**
     * A click at ([x], [y]) on image block [block] of [page], in pixels of the block: a double tap on the
     * watch with the pointer on the block, or a tap on a temple while the pointer is on it. Comes for
     * blocks that show a web page. Since interface version 2.
     */
    data class ImageClick(val page: String, val block: String, val x: Int, val y: Int) : AppEvent

    /**
     * The wearer scrolls by [dy] pixels (positive: further down) over image block [block] of [page]
     * where the page itself does not scroll: a temple swipe, or the pointer pushed past the top or
     * bottom edge. Comes for blocks that show a web page. Since interface version 2.
     */
    data class ImageScroll(val page: String, val block: String, val dy: Int) : AppEvent

    /**
     * A key of a Bluetooth keyboard on the watch, after subscribing to [Sensor.KEYS] (the host keeps
     * Escape, Alt+Left and the keyboard's own back key for back, ContextMenu and F10 for the app menu).
     * [key] is the name a web page sees as `KeyboardEvent.key`: a [Keys] name such as "Enter" or
     * "ArrowUp", or the character the key stands for in the keyboard's layout, Shift and AltGr applied
     * ("a", "A", "@", "é"; with Ctrl still "l" for Ctrl+L). [text] is what the key types into a field,
     * empty for keys that type nothing and for shortcuts with Ctrl, Alt or Meta. [repeat] marks the
     * repeats while a key is held; an app decides which keys may repeat (a toggle should not). Since
     * interface version 3.
     */
    data class Key(
        val key: String,
        val text: String = "",
        val shift: Boolean = false,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
        val repeat: Boolean = false,
    ) : AppEvent

    /**
     * The connection to an app on the phone ([AppContext.phone]): it stands ([PhoneState.CONNECTED]),
     * the partner sent [text] ([PhoneState.MESSAGE]), or it ended or did not come about
     * ([PhoneState.DISCONNECTED], [message] says why, German). Since interface version 4.
     */
    data class Phone(val state: PhoneState, val text: String? = null, val message: String? = null) : AppEvent

    /**
     * How far the download [tag] ([AppFiles.download]) got: [bytes] of [total] ([total] is −1 while
     * unknown). [DownloadState.FAILED] comes with the reason in [message] (German). Since interface
     * version 5.
     */
    data class Download(
        val tag: String,
        val state: DownloadState,
        val bytes: Long,
        val total: Long,
        val message: String? = null,
    ) : AppEvent

    /**
     * The music players on the watch ([AppContext.media] after [MediaAction.Follow]): the one that plays or
     * played last first. [volume] of [maxVolume] is the watch's media volume; [output] where the sound goes
     * ("Kopfhörer: Pixel Buds", "Lautsprecher der Uhr"), null when nothing can play it; [message] why there are
     * no players (German), e.g. no access to the media controls yet. Comes again on every change. Since
     * interface version 6.
     */
    data class Media(
        val players: List<MediaInfo>,
        val volume: Int,
        val maxVolume: Int,
        val output: String?,
        val message: String? = null,
    ) : AppEvent

    /**
     * The app's own stream ([AppContext.player]) changed: its [state], and while it plays what the station
     * says plays now ([title], e.g. "Queen - Bohemian Rhapsody") and its name ([station]); [message] why it
     * failed or paused (German). Since interface version 6.
     */
    data class Player(
        val state: PlayerState,
        val url: String,
        val title: String? = null,
        val station: String? = null,
        val message: String? = null,
    ) : AppEvent
}

/**
 * Names of keys that type nothing, for [AppEvent.Key] and [WebAction.Key]: the `KeyboardEvent.key`
 * values of the web (W3C UI Events). A key that types something is named by its character. More names
 * may come; an app ignores names it does not know. Since interface version 3.
 */
object Keys {
    const val ENTER = "Enter"
    const val ESCAPE = "Escape"
    const val BACKSPACE = "Backspace"
    const val DELETE = "Delete"
    const val TAB = "Tab"

    /** The space bar is named by its character, like any key that types. */
    const val SPACE = " "
    const val ARROW_UP = "ArrowUp"
    const val ARROW_DOWN = "ArrowDown"
    const val ARROW_LEFT = "ArrowLeft"
    const val ARROW_RIGHT = "ArrowRight"
    const val PAGE_UP = "PageUp"
    const val PAGE_DOWN = "PageDown"
    const val HOME = "Home"
    const val END = "End"
    const val INSERT = "Insert"
    const val CONTEXT_MENU = "ContextMenu"
    const val F1 = "F1"
    const val F2 = "F2"
    const val F3 = "F3"
    const val F4 = "F4"
    const val F5 = "F5"
    const val F6 = "F6"
    const val F7 = "F7"
    const val F8 = "F8"
    const val F9 = "F9"
    const val F10 = "F10"
    const val F11 = "F11"
    const val F12 = "F12"
    const val MEDIA_PLAY_PAUSE = "MediaPlayPause"
    const val MEDIA_PLAY = "MediaPlay"
    const val MEDIA_PAUSE = "MediaPause"
    const val MEDIA_STOP = "MediaStop"
    const val MEDIA_NEXT = "MediaTrackNext"
    const val MEDIA_PREVIOUS = "MediaTrackPrevious"
    const val MEDIA_FAST_FORWARD = "MediaFastForward"
    const val MEDIA_REWIND = "MediaRewind"
    const val BROWSER_BACK = "BrowserBack"
    const val BROWSER_FORWARD = "BrowserForward"
}

/** Gesture names as in 02 §6.1. */
enum class GestureKind(val json: String) {
    CLICK("click"),
    DOUBLE_CLICK("doubleClick"),
    SCROLL_UP("scrollUp"),
    SCROLL_DOWN("scrollDown"),
    LONG_PRESS("longPress"),
    LONG_PRESS_RELEASE("longPressRelease"),

    /** Tap, then hold: opens the app menu. */
    SHORT_THEN_LONG_PRESS("shortThenLongPress"),

    /** A touch begins (ring), for games. */
    PRESS("press"),
    HEAD_UP("headUp"),

    /** Only from the watch. */
    SWIPE_LEFT("swipeLeft"),

    /** Only from the watch; in gesture mode it means back. */
    SWIPE_RIGHT("swipeRight"),
    ;

    companion object {
        fun of(json: String?): GestureKind? = entries.firstOrNull { it.json == json }
    }
}

/** Where a gesture came from. Swipes on a temple arrive without a side ([UNKNOWN]). */
enum class InputSource(val json: String) {
    WATCH("watch"),
    LEFT("left"),
    RIGHT("right"),
    RING("ring"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(json: String?): InputSource? = entries.firstOrNull { it.json == json }
    }
}

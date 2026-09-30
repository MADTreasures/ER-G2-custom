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

    /** Position of the watch (kind "sensor", sensor "location"). From M6. */
    data class Location(val lat: Double, val lon: Double, val acc: Float, val t: Long) : AppEvent

    /** 50 ms of 16 kHz mono PCM from the glasses' microphone. Never sent as JSON. From M6. */
    class Audio(val pcm: ShortArray, val seq: Int) : AppEvent {
        override fun toString() = "Audio(seq=$seq, ${pcm.size} samples)"
    }

    /** A command was refused ([CommandException.code]). Only remote apps get this; watch apps see it in the log. */
    data class Error(val command: String, val code: String, val message: String) : AppEvent
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

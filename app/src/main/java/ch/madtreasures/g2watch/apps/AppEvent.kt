package ch.madtreasures.g2watch.apps

/**
 * Something that happens to an app (02 §6.1). Apps ignore kinds they do not know, so the platform
 * can add new ones. The JSON form (`kind`, …) is in [AppJson].
 */
sealed interface AppEvent {
    /** The session begins; the app shows its first page within 2 s. [Visible] follows at once. */
    data object Start : AppEvent

    /** The app is on the glasses (again). */
    data object Visible : AppEvent

    /** Another app or the launcher covers it; it keeps running (timers only with [Permission.BACKGROUND]). */
    data object Hidden : AppEvent

    /** The session ends; the app has 1 s to clean up. */
    data object Stop : AppEvent

    data class Click(val page: String, val block: String) : AppEvent

    /** The host already flipped the switch on the glasses; `patch` it back to refuse. */
    data class Toggle(val page: String, val block: String, val on: Boolean) : AppEvent

    /** A row of a `checks` list was ticked or unticked; already shown by the host. */
    data class Check(val page: String, val block: String, val index: Int, val done: Boolean) : AppEvent

    /** A button with a page target was clicked; the host already shows [to]. */
    data class Navigate(val from: String, val to: String, val block: String) : AppEvent

    /** Back was triggered on [page]; the host goes to the previous page afterwards (or ends the app). */
    data class Back(val page: String) : AppEvent

    /** An own entry of the app menu was chosen (see [AppContext.menu]). */
    data class Menu(val item: String) : AppEvent

    /** A raw gesture: always with [InputMode.GESTURES], otherwise only when subscribed ([Sensor.GESTURES]). */
    data class Gesture(val gesture: GestureKind, val source: InputSource) : AppEvent

    data class Timer(val tag: String) : AppEvent

    /** Acceleration of the glasses (M6). JSON: `kind` "sensor", `sensor` "imu". */
    data class Imu(val x: Float, val y: Float, val z: Float, val t: Long) : AppEvent

    /** Heading in degrees from the glasses' magnetometer (M6). */
    data class Compass(val heading: Float, val t: Long) : AppEvent

    /** GPS position of the watch (M6). */
    data class Location(val lat: Double, val lon: Double, val acc: Float, val t: Long) : AppEvent

    /** 50 ms of the glasses microphone, 16 kHz mono (M6). Never sent as JSON. */
    class Audio(val pcm: ShortArray, val seq: Int) : AppEvent

    /** A command of a remote app was refused (only remote apps get this; watch apps see the log). */
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

    /** Tap, then hold: opens the app menu; apps see it only as a gesture event. */
    SHORT_THEN_LONG_PRESS("shortThenLongPress"),

    /** A touch begins (ring); for games. */
    PRESS("press"),
    HEAD_UP("headUp"),

    /** Watch only. */
    SWIPE_LEFT("swipeLeft"),

    /** Watch only. The host treats it as Back, so apps do not get it as a gesture. */
    SWIPE_RIGHT("swipeRight"),
    ;

    companion object {
        fun of(json: String): GestureKind? = entries.firstOrNull { it.json == json }
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
        fun of(json: String): InputSource? = entries.firstOrNull { it.json == json }
    }
}

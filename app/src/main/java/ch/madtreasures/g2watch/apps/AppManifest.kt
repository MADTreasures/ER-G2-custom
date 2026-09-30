package ch.madtreasures.g2watch.apps

/**
 * What an app is: the Kotlin form of `g2app.json` (docs/app-entwicklung/02 §2). Built-in watch apps
 * carry it as an object; an invalid manifest fails at construction, so a broken app never ships.
 */
data class AppManifest(
    /** Reverse domain, lower case: `ch.madtreasures.stoppuhr`. At most 64 characters. */
    val id: String,
    /** Shown in the header and the launcher; at most 20 characters. */
    val name: String,
    /** `x.y.z`. A new version asks for the permissions again. */
    val version: String,
    val input: InputMode = InputMode.POINTER,
    val permissions: Set<Permission> = emptySet(),
    /** Asset path of a Baukasten export (`apps/<id>/ui.json`); loaded before [AppEvent.Start]. */
    val ui: String? = null,
    /** One sentence for app lists. */
    val description: String = "",
) {
    init {
        require(ID.matches(id) && id.length <= 64) { "app id \"$id\" must look like ch.example.app" }
        require(name.isNotBlank() && name.length <= 20) { "app name \"$name\" must have 1 to 20 characters" }
        require(VERSION.matches(version)) { "app version \"$version\" must be x.y.z" }
    }

    companion object {
        val ID = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    }
}

/** How the wearer operates an app (02 §7). */
enum class InputMode(val json: String) {
    /** Pointer on the watch touchpad plus focus on the temples; the host handles clicks. */
    POINTER("pointer"),

    /** No pointer: the app gets raw gestures from watch, temples and ring (games, Even Hub apps). */
    GESTURES("gestures"),
    ;

    companion object {
        fun of(json: String?): InputMode? = entries.firstOrNull { it.json == json }
    }
}

/** What an app may use beyond drawing (02 §8). [label] is what the wearer is asked about. */
enum class Permission(val json: String, val label: String) {
    NETWORK("network", "Internet"),
    MIC("mic", "Mikrofon der Brille"),
    IMU("imu", "Bewegungssensor"),
    COMPASS("compass", "Kompass"),
    LOCATION("location", "Standort"),
    BUZZER("buzzer", "Summer der Brille"),
    BACKGROUND("background", "Im Hintergrund laufen"),
    ;

    companion object {
        fun of(json: String?): Permission? = entries.firstOrNull { it.json == json }
    }
}

package ch.madtreasures.g2watch.apps

/**
 * What the host needs to know about an app before it runs (02 §2). Watch apps carry it as this
 * object; the JSON form `g2app.json` is for remote apps.
 */
data class AppManifest(
    /** Reverse domain, lower case, e.g. `ch.madtreasures.stoppuhr`. */
    val id: String,
    /** Shown in the header and the launcher; at most [MAX_NAME] characters. */
    val name: String,
    /** `x.y.z`; a new version asks for the permissions again. */
    val version: String,
    val input: InputMode = InputMode.POINTER,
    val permissions: Set<Permission> = emptySet(),
    /** Asset path of a Baukasten export, loaded before `start` (e.g. `apps/<id>/ui.json`). */
    val ui: String? = null,
    /** One sentence for app lists. */
    val description: String = "",
) {
    init {
        require(id.length <= MAX_ID && ID.matches(id)) { "App-Kennung „$id“ ist ungültig" }
        require(name.isNotBlank() && name.length <= MAX_NAME) { "App-Name „$name“: 1–$MAX_NAME Zeichen" }
        require(VERSION.matches(version)) { "Version „$version“: erwartet x.y.z" }
    }

    companion object {
        const val MAX_NAME = 20
        const val MAX_ID = 64
        val ID = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    }
}

/** How the wearer operates an app (02 §7). */
enum class InputMode(val json: String) {
    /** Pointer on the watch touchpad, focus moved by temple swipes; the default. */
    POINTER("pointer"),

    /** No pointer; watch and temples deliver raw gestures (games, EvenHub apps). */
    GESTURES("gestures"),
}

/** What an app may use beyond drawing (02 §8). Asked once on the glasses per app version. */
enum class Permission(val json: String, val label: String) {
    NETWORK("network", "Internet"),
    MIC("mic", "Mikrofon"),
    IMU("imu", "Bewegungssensor"),
    COMPASS("compass", "Kompass"),
    LOCATION("location", "Standort"),
    BUZZER("buzzer", "Summer"),
    BACKGROUND("background", "Hintergrund"),
    ;

    companion object {
        fun of(json: String): Permission? = entries.firstOrNull { it.json == json }
    }
}

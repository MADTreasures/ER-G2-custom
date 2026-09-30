package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonObject

/**
 * Something an app asks the host to do, in the form that also travels over the wire from a remote app
 * (02 §6.2, 04 §5.3). Watch apps call [AppContext]; the host turns those calls into these commands, so
 * both kinds of app take the same path. Timers, storage, fetch and log stay with the app's runtime and
 * are no commands.
 */
sealed interface AppCommand {
    /** The command name in JSON (`"c"`), also used in error reports. */
    val name: String

    data class DefinePages(val pages: List<Page>) : AppCommand {
        override val name get() = "definePages"
    }

    data class Show(val page: String) : AppCommand {
        override val name get() = "show"
    }

    data class Replace(val page: String) : AppCommand {
        override val name get() = "replace"
    }

    /** [changes]: block id → the fields to change, in their JSON form (see [PatchBuilder]). */
    data class Patch(val page: String, val changes: JsonObject) : AppCommand {
        override val name get() = "patch"
    }

    data class SetBlocks(val page: String, val blocks: List<Block>) : AppCommand {
        override val name get() = "setBlocks"
    }

    data class Toast(val text: String, val ms: Int = 2000) : AppCommand {
        override val name get() = "toast"
    }

    data class Vibrate(val pattern: Vibration) : AppCommand {
        override val name get() = "vibrate"
    }

    data class Menu(val items: List<MenuItem>) : AppCommand {
        override val name get() = "menu"
    }

    data class Buzz(val notes: List<BuzzNote>) : AppCommand {
        override val name get() = "buzz"
    }

    data class Subscribe(val sensor: Sensor, val rate: Int = 0) : AppCommand {
        override val name get() = "subscribe"
    }

    data class Unsubscribe(val sensor: Sensor) : AppCommand {
        override val name get() = "unsubscribe"
    }

    data class Audio(val on: Boolean) : AppCommand {
        override val name get() = "audio"
    }

    data object Close : AppCommand {
        override val name get() = "close"
    }
}

/** An own entry of an app in the app menu (02 §5); [text] at most 32 bytes of UTF-8. */
data class MenuItem(val id: String, val text: String)

/** One step for the glasses' buzzer: frequency, duty cycle in percent, duration. */
data class BuzzNote(val freqHz: Int, val dutyPercent: Int, val ms: Int)

enum class Vibration(val json: String) {
    TICK("tick"),
    DOUBLE("double"),
    LONG("long"),
    ;

    companion object {
        fun of(json: String?): Vibration? = entries.firstOrNull { it.json == json }
    }
}

/** What an app can subscribe to; [permission] is what it needs for that. */
enum class Sensor(val json: String, val permission: Permission?) {
    IMU("imu", Permission.IMU),
    COMPASS("compass", Permission.COMPASS),
    LOCATION("location", Permission.LOCATION),
    GESTURES("gestures", null),
    ;

    companion object {
        fun of(json: String?): Sensor? = entries.firstOrNull { it.json == json }
    }
}

/**
 * A command the host refused. [code] is one of [UNKNOWN_PAGE], [UNKNOWN_BLOCK], [PERMISSION_DENIED],
 * [BAD_VALUE] (the `cmd.error` codes of 04 §5.7); [message] is German, for the log and the wearer.
 */
class CommandException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        const val UNKNOWN_PAGE = "unknown_page"
        const val UNKNOWN_BLOCK = "unknown_block"
        const val PERMISSION_DENIED = "permission_denied"
        const val BAD_VALUE = "bad_value"

        fun unknownPage(id: String) = CommandException(UNKNOWN_PAGE, "Unbekannte Seite „$id“")
        fun unknownBlock(id: String, page: String) = CommandException(UNKNOWN_BLOCK, "Unbekannter Baustein „$id“ auf Seite „$page“")
        fun badValue(message: String) = CommandException(BAD_VALUE, message)
        fun denied(permission: Permission) =
            CommandException(PERMISSION_DENIED, "Keine Berechtigung: ${permission.label}")
    }
}

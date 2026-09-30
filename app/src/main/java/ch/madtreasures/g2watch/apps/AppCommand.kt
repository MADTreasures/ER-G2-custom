package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonObject

/**
 * A command of 02 §6.2 as data. [AppContext] calls become commands, and so will the `cmd` messages
 * of remote apps (M5); the host checks and applies both the same way. JSON form in [AppJson].
 * Timers, storage and fetch stay with the app's runtime and have no wire form.
 */
sealed interface AppCommand {
    /** The command name, `c` in JSON. */
    val name: String

    data class DefinePages(val pages: List<Page>) : AppCommand {
        override val name: String get() = "definePages"
    }

    data class Show(val page: String) : AppCommand {
        override val name: String get() = "show"
    }

    data class Replace(val page: String) : AppCommand {
        override val name: String get() = "replace"
    }

    /** [changes]: block id → fields to change, as in the JSON form. */
    data class Patch(val page: String, val changes: Map<String, JsonObject>) : AppCommand {
        override val name: String get() = "patch"
    }

    data class SetBlocks(val page: String, val blocks: List<Block>) : AppCommand {
        override val name: String get() = "setBlocks"
    }

    data class Toast(val text: String, val ms: Int = 2000) : AppCommand {
        override val name: String get() = "toast"
    }

    data class Vibrate(val pattern: Vibration) : AppCommand {
        override val name: String get() = "vibrate"
    }

    data class Menu(val items: List<MenuItem>) : AppCommand {
        override val name: String get() = "menu"
    }

    data class Buzz(val notes: List<BuzzNote>) : AppCommand {
        override val name: String get() = "buzz"
    }

    data class Timer(val tag: String, val ms: Long, val repeat: Boolean) : AppCommand {
        override val name: String get() = "timer"
    }

    data class CancelTimer(val tag: String) : AppCommand {
        override val name: String get() = "cancelTimer"
    }

    data class Subscribe(val sensor: Sensor, val rate: Int = 0) : AppCommand {
        override val name: String get() = "subscribe"
    }

    data class Unsubscribe(val sensor: Sensor) : AppCommand {
        override val name: String get() = "unsubscribe"
    }

    data class Audio(val on: Boolean) : AppCommand {
        override val name: String get() = "audio"
    }

    data object Close : AppCommand {
        override val name: String get() = "close"
    }
}

/** Why the host refused a command; the codes of `cmd.error` (04 §5.3). */
enum class CommandError(val code: String) {
    UNKNOWN_PAGE("unknown_page"),
    UNKNOWN_BLOCK("unknown_block"),
    PERMISSION_DENIED("permission_denied"),
    BAD_VALUE("bad_value"),
}

/** A refused command: carries the code for the log and for remote apps. */
class CommandException(val error: CommandError, message: String) : IllegalArgumentException(message)

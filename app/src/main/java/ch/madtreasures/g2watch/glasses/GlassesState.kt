package ch.madtreasures.g2watch.glasses

enum class Stage(val label: String, val busy: Boolean = false) {
    IDLE("Nicht verbunden"),

    /** Read-only probe: connect, pair if needed, read the firmware versions. Draws nothing. */
    CHECKING("Firmware wird geprüft", busy = true),
    CONNECTING("Verbinde", busy = true),
    RECONNECTING("Verbinde neu", busy = true),
    CONNECTED("Verbunden"),
    CHARGING("Brille lädt"),
    DISCONNECTING("Trenne", busy = true),

    /** The probe found firmware the app does not drive; no session was started. */
    INCOMPATIBLE("Firmware passt nicht"),
    FAILED("Fehler"),
    ;

    /** A Faceclaw session exists (it may be between reconnects). */
    val hasSession: Boolean get() = this == CONNECTING || this == RECONNECTING || this == CONNECTED || this == CHARGING
}

/** What the watch UI shows about the glasses. */
data class GlassesState(
    val stage: Stage = Stage.IDLE,
    val title: String? = null,
    /** The latest status line from the probe or the session. */
    val detail: String = "",
    val firmware: FirmwareVerdict? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val wearing: Boolean? = null,
    /** The last tap or swipe on a temple or the ring, for the status display. */
    val lastInput: String? = null,
    /** Display frames the glasses acknowledged in this session. */
    val framesSent: Long = 0,
    /** Transfer time of the most recent frame. */
    val transmitMs: Int? = null,
    /** How long the latest frames took from the watch to the glasses, for the settings log. */
    val transfer: TransferStats? = null,
)

/**
 * Transfer times of the last frames from the watch to the glasses, as Faceclaw measures them
 * (from sending the first tile to the glasses' acknowledgement).
 */
data class TransferStats(
    val lastMs: Int,
    val avgMs: Int,
    val minMs: Int,
    val maxMs: Int,
    /** Oldest first, at most [WINDOW] values. */
    val recent: List<Int>,
) {
    companion object {
        const val WINDOW = 30

        fun of(values: Collection<Int>): TransferStats? {
            if (values.isEmpty()) return null
            val recent = values.toList().takeLast(WINDOW)
            return TransferStats(
                lastMs = recent.last(),
                avgMs = (recent.sum().toFloat() / recent.size).let { kotlin.math.round(it).toInt() },
                minMs = recent.min(),
                maxMs = recent.max(),
                recent = recent,
            )
        }
    }
}

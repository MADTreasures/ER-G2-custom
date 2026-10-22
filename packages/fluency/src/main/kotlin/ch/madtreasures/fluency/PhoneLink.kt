package ch.madtreasures.fluency

/** Where the sound Fluency translates comes from. */
enum class Source(val json: String, val label: String) {
    /** The watch's microphone; the watch app streams it to the phone. */
    WATCH("watch", "Uhr-Mikrofon"),

    /** The phone's own microphone: people or media near the phone. */
    PHONE("phone", "Handy-Mikrofon"),
    ;

    companion object {
        fun of(json: String?): Source? = entries.firstOrNull { it.json == json }
    }
}

/** What the connection to the phone reports; delivered on the app thread ([FluencyApp.onLink]). */
sealed interface LinkEvent {
    data object Connected : LinkEvent

    /** One message from Fluency ([Protocol]). */
    data class Message(val text: String) : LinkEvent

    /** The connection ended or did not come about; [reason] is German, for the wearer. */
    data class Disconnected(val reason: String? = null) : LinkEvent
}

/**
 * The connection to Fluency on the phone. The interface of the watch app (version 3) has no way to the
 * phone yet (PLATTFORM.md): [NoPhoneLink] stands in until it has, and a later version of this package
 * gets a real link from it. All calls come from the app thread.
 */
interface PhoneLink {
    /** False while the watch app cannot reach the phone at all. */
    val available: Boolean

    /** Starts connecting; the outcome comes as [LinkEvent]. Does nothing while connected or connecting. */
    fun open()

    /** One message to Fluency ([Protocol]); only while connected. */
    fun send(message: String)

    /** Streams the watch's microphone to Fluency, or stops it. */
    fun microphone(on: Boolean)

    fun close()
}

/** No way to the phone: what interface version 3 offers. */
object NoPhoneLink : PhoneLink {
    override val available = false

    override fun open() = Unit

    override fun send(message: String) = Unit

    override fun microphone(on: Boolean) = Unit

    override fun close() = Unit
}

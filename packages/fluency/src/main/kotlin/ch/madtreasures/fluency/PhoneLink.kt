package ch.madtreasures.fluency

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.PhoneAction
import ch.madtreasures.g2watch.apps.PhoneState

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
 * The connection to Fluency on the phone: [WatchPhoneLink] through the watch app, [NoPhoneLink] without
 * one, a fake in the tests. All calls come from the app thread.
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

/**
 * The connection to Fluency through the watch app (interface version 4, [AppContext.phone]): G2 Handy on
 * the phone passes it on to Fluency's link service (PLATTFORM.md §6). The watch app reports what happens
 * as [AppEvent.Phone]; [event] says what that means here.
 */
class WatchPhoneLink(private val ui: AppContext) : PhoneLink {
    override val available = true

    override fun open() = ui.phone(PhoneAction.Connect(PARTNER))

    override fun send(message: String) = ui.phone(PhoneAction.Send(message))

    override fun microphone(on: Boolean) = ui.phone(PhoneAction.Microphone(on))

    override fun close() = ui.phone(PhoneAction.Disconnect)

    companion object {
        /** Fluency's package name on the phone. */
        const val PARTNER = "ch.madtreasures.fluency"

        fun event(phone: AppEvent.Phone): LinkEvent = when (phone.state) {
            PhoneState.CONNECTED -> LinkEvent.Connected
            PhoneState.MESSAGE -> LinkEvent.Message(phone.text.orEmpty())
            PhoneState.DISCONNECTED -> LinkEvent.Disconnected(phone.message)
        }
    }
}

/** No way to the phone (the tests of the pages and the Probelauf). */
object NoPhoneLink : PhoneLink {
    override val available = false

    override fun open() = Unit

    override fun send(message: String) = Unit

    override fun microphone(on: Boolean) = Unit

    override fun close() = Unit
}

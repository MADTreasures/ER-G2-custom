package ch.madtreasures.g2watch.apps.phone

import ch.madtreasures.g2watch.apps.PhoneState

/** What one connection to the phone reports, from the engine's threads. */
interface PhoneListener {
    /** Connected, a [text] from the partner, or ended ([message] says why, German). */
    fun onState(state: PhoneState, text: String?, message: String?)

    /** Something the wearer should know that does not end the connection (German), e.g. the microphone is not allowed. */
    fun onNotice(message: String) {}
}

/** One app's connection to a partner app on the phone; every call may come from any thread. */
interface PhoneLine {
    fun send(text: String)

    /** The watch's microphone to the partner, or no more. */
    fun microphone(on: Boolean)

    /** Ends the connection; no callbacks afterwards. */
    fun close()
}

/**
 * The way from the watch apps to apps on the phone ([ch.madtreasures.g2watch.apps.AppContext.phone]): the
 * Wear OS Data Layer to G2 Handy, which passes the connection on ([PhoneLinks] on the watch).
 */
interface PhoneEngine {
    /** Connects the app [app] (in [version]) to the phone app [partner]; what happens comes to [listener]. */
    fun connect(app: String, version: String, partner: String, listener: PhoneListener): PhoneLine

    companion object {
        /** No way to the phone (a watch without Google Play Services, tests): every connection fails at once. */
        val NONE: PhoneEngine = object : PhoneEngine {
            override fun connect(app: String, version: String, partner: String, listener: PhoneListener): PhoneLine {
                listener.onState(PhoneState.DISCONNECTED, null, "Diese Uhr-App erreicht das Handy nicht")
                return Closed
            }
        }
    }

    /** A connection that is over. */
    object Closed : PhoneLine {
        override fun send(text: String) = Unit

        override fun microphone(on: Boolean) = Unit

        override fun close() = Unit
    }
}

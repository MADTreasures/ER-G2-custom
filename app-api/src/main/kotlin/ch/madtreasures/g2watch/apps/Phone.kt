package ch.madtreasures.g2watch.apps

/**
 * What an app asks of its connection to an app on the phone ([AppContext.phone]). The watch reaches the
 * phone it is paired with; there "G2 Handy" passes the connection on to the partner app, named by its
 * Android package name, which offers it (packages/fluency/PLATTFORM.md §5–§6, Fluency for example).
 * Messages are text (JSON or whatever the two apps agree on), and the watch's microphone can stream to
 * the partner. One connection per app; it ends with the app. Since interface version 4.
 */
sealed interface PhoneAction {
    /** The JSON name (`"action"`). */
    val json: String

    /**
     * Connects to the app [partner] (its Android package name, e.g. `ch.madtreasures.fluency`) on the
     * phone; the outcome comes as [AppEvent.Phone]: [PhoneState.CONNECTED], or [PhoneState.DISCONNECTED]
     * with the reason, also when the app lacks [Permission.PHONE]. Connected or connecting to this partner
     * already, it does nothing; another partner replaces the connection. The host does not reconnect by
     * itself.
     */
    data class Connect(val partner: String) : PhoneAction {
        override val json get() = "connect"
    }

    /** One message to the partner, at most [MAX_TEXT_BYTES] of UTF-8; only while connected. Order is kept. */
    data class Send(val text: String) : PhoneAction {
        override val json get() = "send"
    }

    /**
     * Streams the watch's microphone to the partner (16 kHz mono PCM), or stops it. Needs
     * [Permission.WATCH_MIC]; only while connected and while the app is on the glasses. When the app is
     * hidden, the microphone stops and does not start again by itself.
     */
    data class Microphone(val on: Boolean) : PhoneAction {
        override val json get() = "microphone"
    }

    /** Ends the connection; no event follows. */
    data object Disconnect : PhoneAction {
        override val json get() = "disconnect"
    }

    companion object {
        /** The most text one message may carry. */
        const val MAX_TEXT_BYTES = 64 * 1024
    }
}

/** State of the connection to the phone, as [AppEvent.Phone] reports it. Since interface version 4. */
enum class PhoneState(val json: String) {
    /** The partner accepted the connection. */
    CONNECTED("connected"),

    /** A message from the partner, in [AppEvent.Phone.text]. */
    MESSAGE("message"),

    /** The connection ended or did not come about; [AppEvent.Phone.message] says why (German). */
    DISCONNECTED("disconnected"),
    ;

    companion object {
        fun of(json: String?): PhoneState? = entries.firstOrNull { it.json == json }
    }
}

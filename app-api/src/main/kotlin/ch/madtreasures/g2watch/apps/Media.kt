package ch.madtreasures.g2watch.apps

/**
 * What an app asks of the music players on the watch ([AppContext.media]): Spotify, YouTube Music, a podcast
 * app, whatever plays music there. The sound comes from the watch, through headphones paired with it or its
 * speaker; the glasses have none. The watch app reads the players through Android's media controls, which it
 * may only after the wearer gave it access once (03 §15). Since interface version 6.
 */
sealed interface MediaAction {
    /** The JSON name (`"action"`). */
    val json: String

    /**
     * [AppEvent.Media] now and on every change (another song, play or pause, the volume, headphones on or
     * off), until [Unfollow] or the end of the app. Without access to the watch's media controls the event
     * holds no players and its message says why; play, pause, next and previous still reach the player that
     * played last, as the keys of headphones would.
     */
    data object Follow : MediaAction {
        override val json get() = "follow"
    }

    data object Unfollow : MediaAction {
        override val json get() = "unfollow"
    }

    /** Plays in the player [app] (its Android package name), or in the one that plays or played last. */
    data class Play(val app: String? = null) : MediaAction {
        override val json get() = "play"
    }

    data class Pause(val app: String? = null) : MediaAction {
        override val json get() = "pause"
    }

    /**
     * Pauses when [app] (or the player that plays or played last) plays, else plays: what the button of
     * headphones does. For when the app does not know whether it plays, e.g. without access (see [Follow]).
     */
    data class PlayPause(val app: String? = null) : MediaAction {
        override val json get() = "playPause"
    }

    data class Next(val app: String? = null) : MediaAction {
        override val json get() = "next"
    }

    data class Previous(val app: String? = null) : MediaAction {
        override val json get() = "previous"
    }

    /** Jumps to [positionMs] in what plays. */
    data class Seek(val positionMs: Long, val app: String? = null) : MediaAction {
        override val json get() = "seek"
    }

    /**
     * Asks [app] to find and play [query] (a song, an artist, a playlist), as a voice assistant asks it. Not
     * every player answers; whether it plays shows in the next [AppEvent.Media]. At most [MAX_QUERY] characters.
     */
    data class Search(val query: String, val app: String) : MediaAction {
        override val json get() = "search"
    }

    /** Opens [app] on the watch's screen: to sign in there, choose music or load it for offline. */
    data class Open(val app: String) : MediaAction {
        override val json get() = "open"
    }

    /** Louder ([steps] above 0) or quieter: the watch's media volume, which the app's own stream plays at too. */
    data class Volume(val steps: Int) : MediaAction {
        override val json get() = "volume"
    }

    companion object {
        const val MAX_QUERY = 200

        /** Android package names, as `app` takes them. */
        val APP = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/** One player on the watch, as [AppEvent.Media] reports it. Since interface version 6. */
data class MediaInfo(
    /** Android package name, e.g. `com.spotify.music`. */
    val app: String,
    /** Its name on the watch, e.g. "Spotify". */
    val appName: String,
    val state: MediaState,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** 0 when unknown, or for a live stream. */
    val durationMs: Long = 0,
    /** Where it was at [positionAt] (ms since 1970); while it plays it moves on at [speed]. */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    val speed: Float = 1f,
    /** The cover in grey, `data:image/png;base64,…`, at most [ARTWORK_SIZE] square; null without one. */
    val artwork: String? = null,
    val canNext: Boolean = true,
    val canPrevious: Boolean = true,
    val canSeek: Boolean = false,
    val canSearch: Boolean = false,
) {
    /** Where it is at [nowMs] (ms since 1970): counted on while it plays, never past the end. */
    fun positionNow(nowMs: Long): Long {
        val moved = if (state == MediaState.PLAYING && positionAt > 0) ((nowMs - positionAt) * speed).toLong().coerceAtLeast(0) else 0
        val at = positionMs + moved
        return if (durationMs > 0) at.coerceAtMost(durationMs) else at
    }

    companion object {
        const val ARTWORK_SIZE = 120
    }
}

/** State of a player on the watch ([MediaInfo.state]). Since interface version 6. */
enum class MediaState(val json: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    STOPPED("stopped"),
    ERROR("error"),
    ;

    companion object {
        fun of(json: String?): MediaState? = entries.firstOrNull { it.json == json }
    }
}

/**
 * The app's own audio stream on the watch ([AppContext.player]): internet radio, an audio file. One stream per
 * app; it goes on while the app is hidden and ends with the app. Needs [Permission.NETWORK]. It plays at the
 * watch's media volume ([MediaAction.Volume]) and gives way when another player starts, and the other way
 * round. Since interface version 6.
 */
sealed interface PlayerAction {
    /** The JSON name (`"action"`). */
    val json: String

    /**
     * Plays [url] (`https://`, or `http://` as many radio stations still stream; MP3, AAC, HLS …); [title]
     * names it on the watch. A new [Play] replaces the stream. [AppEvent.Player] reports how it goes.
     */
    data class Play(val url: String, val title: String = "") : PlayerAction {
        override val json get() = "play"
    }

    data object Pause : PlayerAction {
        override val json get() = "pause"
    }

    data object Resume : PlayerAction {
        override val json get() = "resume"
    }

    /** Ends the stream; no event follows. */
    data object Stop : PlayerAction {
        override val json get() = "stop"
    }
}

/** State of the app's own stream, as [AppEvent.Player] reports it. Since interface version 6. */
enum class PlayerState(val json: String) {
    /** Connecting, filling the buffer. */
    LOADING("loading"),
    PLAYING("playing"),

    /** Paused by the app, or because another player took over the sound. */
    PAUSED("paused"),

    /** The stream stalls (slow network); it goes on by itself. */
    BUFFERING("buffering"),

    /** A file played to its end. */
    ENDED("ended"),

    /** It failed; [AppEvent.Player.message] says why (German). */
    ERROR("error"),
    ;

    companion object {
        fun of(json: String?): PlayerState? = entries.firstOrNull { it.json == json }
    }
}

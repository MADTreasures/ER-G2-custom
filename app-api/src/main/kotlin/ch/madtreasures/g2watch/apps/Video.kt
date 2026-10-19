package ch.madtreasures.g2watch.apps

/**
 * How the watch turns a video into pictures for the glasses (03 §10). The glasses show 16 grey levels
 * and receive about 41 KiB/s (01 §1). A profile sets the raster (points of [cell] × [cell] pixels in
 * [levels] grey levels) and how often the watch makes a picture ([frameMs]); the link then sets how many
 * arrive: while it is busy, a newer picture replaces the waiting one, nothing piles up.
 *
 * Chosen with `VideoBudgetTest` (Faceclaw's own wire path to the custom firmware, made-up videos in the
 * YouTube app's 512 × 288 block; simulated, not measured on the glasses): in the fine raster an ordinary
 * scene costs about 3 KB per picture, so 10 a second take two thirds of the link; fine detail everywhere
 * (leaves, crowds) costs about 20 KB, and the link carries 2 a second, 4 in the coarse raster.
 */
enum class VideoProfile(val json: String, val label: String, val frameMs: Long, val cell: Int, val levels: Int) {
    /** Up to 5 pictures a second in the fine raster: ordinary scenes leave over half of the link free. */
    STABLE("stable", "Stabil", 200, 2, 16),

    /** Up to 10 pictures a second in the fine raster; ordinary scenes keep the pace (default). */
    BALANCED("balanced", "Ausgewogen", 100, 2, 16),

    /** Up to 15 pictures a second on a coarser raster, which also keeps fine detail moving at about 4. */
    FAST("fast", "Schnell", 66, 3, 16),
    ;

    /** The next profile, for a button that cycles through them. */
    val next: VideoProfile get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(json: String?): VideoProfile? = entries.firstOrNull { it.json == json }
    }
}

/** What an app asks of the video in an image block ([AppContext.video]). */
sealed interface VideoAction {
    /** The JSON name (`"action"`). */
    val json: String

    /**
     * Starts [src] from [startMs]: a video page (YouTube, PeerTube, media.ccc.de …), a video file or HLS
     * address (`https://`), or a test picture of the watch (`test:muster`, needs no internet). With
     * [sound] the watch plays the sound on its speaker or headphones.
     */
    data class Play(
        val src: String,
        val profile: VideoProfile = VideoProfile.BALANCED,
        val sound: Boolean = false,
        val startMs: Long = 0,
    ) : VideoAction {
        override val json get() = "play"
    }

    data object Pause : VideoAction {
        override val json get() = "pause"
    }

    data object Resume : VideoAction {
        override val json get() = "resume"
    }

    /** Jumps to [positionMs] (clamped to the video). */
    data class Seek(val positionMs: Long) : VideoAction {
        override val json get() = "seek"
    }

    /** Changes the profile of the running video without starting over. */
    data class Profile(val profile: VideoProfile) : VideoAction {
        override val json get() = "profile"
    }

    /** Ends playback; the block keeps the last picture. */
    data object Stop : VideoAction {
        override val json get() = "stop"
    }
}

/** State of a video, as [AppEvent.Video] reports it. */
enum class VideoState(val json: String) {
    /** Looking up the video and filling the buffer; no picture yet. */
    LOADING("loading"),
    PLAYING("playing"),
    PAUSED("paused"),

    /** Waiting for data in the middle of the video. */
    BUFFERING("buffering"),
    ENDED("ended"),

    /** Playback failed; the event's message says why (German). */
    ERROR("error"),
    ;

    companion object {
        fun of(json: String?): VideoState? = entries.firstOrNull { it.json == json }
    }
}

/** A video found by [AppContext.videoSearch]; [url] goes into [VideoAction.Play]. */
data class VideoItem(
    val url: String,
    val title: String,
    val channel: String = "",
    /** Length in seconds; 0 when unknown (live streams). */
    val durationS: Long = 0,
    val live: Boolean = false,
    /** Views; -1 when unknown. */
    val views: Long = -1,
    /** When it was uploaded, as the portal words it ("vor 3 Tagen"); may be empty. */
    val uploaded: String = "",
)

/** The answer of [AppContext.videoSearch]: the hits, or [error] (German) when the search failed. */
data class VideoSearchResult(val items: List<VideoItem>, val error: String? = null) {
    val ok: Boolean get() = error == null
}

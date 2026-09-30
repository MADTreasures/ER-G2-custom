package ch.madtreasures.g2watch.apps.video

/** One stream a video page offers (from NewPipeExtractor), reduced to what the choice needs. */
data class StreamOption(
    val url: String,
    val kind: Kind,
    /** Video height in pixels; 0 for audio. */
    val height: Int = 0,
    /** RFC 6381 codec string as the portal names it ("avc1.4d400c", "vp9", "mp4a.40.2", "opus"); may be empty. */
    val codec: String = "",
    val mime: String = "",
    /** Bits per second; 0 when unknown. */
    val bitrate: Int = 0,
    /** For audio: the original language track (not dubbed or described). */
    val original: Boolean = true,
) {
    enum class Kind { VIDEO_ONLY, MUXED, AUDIO }
}

/** One way to play a video: a progressive [video] file (with separate [audio]), or an [hls] playlist. */
data class PlaybackPlan(val video: StreamOption? = null, val audio: StreamOption? = null, val hls: String? = null) {
    val label: String
        get() = when {
            hls != null -> "HLS"
            video != null -> "${video.height}p ${StreamChooser.codecName(video)}" + if (audio != null) " + Ton" else ""
            else -> "?"
        }
}

/**
 * Picks the streams for the glasses (03 §10): the picture ends up as a raster of about 200 × 110 points,
 * so the smallest video that still has [wantHeight] lines is enough, and H.264 comes first because every
 * watch decodes it in hardware. The plans come in the order to try them; the player moves on to the next
 * one when a stream fails (e.g. HTTP 403).
 */
object StreamChooser {
    /** Never more than this many lines: more costs data and battery and shows nothing more. */
    const val MAX_HEIGHT = 480

    fun plans(options: List<StreamOption>, hls: String?, live: Boolean, wantHeight: Int, sound: Boolean): List<PlaybackPlan> {
        val out = ArrayList<PlaybackPlan>()
        val hlsPlan = hls?.takeIf { it.startsWith("https://") }?.let { PlaybackPlan(hls = it) }
        if (live) return listOfNotNull(hlsPlan)
        val audio = if (sound) bestAudio(options) else null
        bestVideo(options.filter { it.kind == StreamOption.Kind.VIDEO_ONLY }, wantHeight)?.let {
            // Without a separate sound track, sound comes from HLS or the muxed file below.
            if (!sound || audio != null) out += PlaybackPlan(video = it, audio = audio)
        }
        hlsPlan?.let { out += it }
        bestVideo(options.filter { it.kind == StreamOption.Kind.MUXED }, wantHeight)?.let { out += PlaybackPlan(video = it) }
        if (sound && audio == null) {
            // No sound track anywhere: the picture alone is still worth it.
            bestVideo(options.filter { it.kind == StreamOption.Kind.VIDEO_ONLY }, wantHeight)?.let { out += PlaybackPlan(video = it) }
        }
        return out.distinct()
    }

    /** The smallest supported video with at least [wantHeight] lines, else the largest below; H.264 first. */
    fun bestVideo(videos: List<StreamOption>, wantHeight: Int): StreamOption? {
        val usable = videos.filter { it.url.startsWith("https://") && it.height in 1..MAX_HEIGHT && codecRank(it) < UNSUPPORTED }
        if (usable.isEmpty()) return null
        val bestCodec = usable.minOf { codecRank(it) }
        val same = usable.filter { codecRank(it) == bestCodec }
        return same.filter { it.height >= wantHeight }.minWithOrNull(compareBy({ it.height }, { it.bitrate }))
            ?: same.maxWithOrNull(compareBy({ it.height }, { -it.bitrate }))
    }

    /** AAC of the original language with the lowest bitrate from 48 kbit/s on; else the smallest Opus. */
    fun bestAudio(options: List<StreamOption>): StreamOption? {
        val audio = options.filter { it.kind == StreamOption.Kind.AUDIO && it.url.startsWith("https://") }
        val original = audio.filter { it.original }.ifEmpty { audio }
        val aac = original.filter { isAac(it) }
        val pool = aac.ifEmpty { original }
        return pool.filter { it.bitrate >= 48_000 }.minByOrNull { it.bitrate } ?: pool.maxByOrNull { it.bitrate }
    }

    private const val UNSUPPORTED = 9

    /** 0 = H.264, 1 = VP9, 9 = not played (AV1 has no hardware decoder on most watches). */
    fun codecRank(s: StreamOption): Int {
        val c = s.codec.lowercase()
        return when {
            c.startsWith("avc") || (c.isEmpty() && s.mime == "video/mp4") -> 0
            c.startsWith("vp9") || c.startsWith("vp09") || (c.isEmpty() && s.mime == "video/webm") -> 1
            else -> UNSUPPORTED
        }
    }

    private fun isAac(s: StreamOption) = s.codec.lowercase().startsWith("mp4a") || (s.codec.isEmpty() && s.mime in setOf("audio/mp4", "audio/m4a"))

    fun codecName(s: StreamOption): String = when (codecRank(s)) {
        0 -> "H.264"
        1 -> "VP9"
        else -> s.codec
    }
}

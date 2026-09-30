package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.video.StreamOption.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which streams the watch loads for the glasses (03 §10). */
class StreamChooserTest {
    private fun video(height: Int, codec: String, kind: Kind = Kind.VIDEO_ONLY, bitrate: Int = height * 1000) =
        StreamOption("https://rr1.googlevideo.com/videoplayback?itag=$height$codec", kind, height, codec, if (codec.startsWith("avc")) "video/mp4" else "video/webm", bitrate)

    private fun audio(bitrate: Int, codec: String = "mp4a.40.2", original: Boolean = true) =
        StreamOption("https://rr1.googlevideo.com/videoplayback?audio=$bitrate$codec$original", Kind.AUDIO, 0, codec, "audio/mp4", bitrate, original)

    private val youtube = listOf(
        video(144, "avc1.4d400c"), video(240, "avc1.4d4015"), video(360, "avc1.4d401e"), video(1080, "avc1.640028"),
        video(144, "vp9"), video(240, "vp9"),
        video(144, "av01.0.00M.08"),
        video(360, "avc1.42001E", Kind.MUXED),
        audio(48_000), audio(128_000), audio(50_000, "opus"), audio(48_000, original = false),
    )

    @Test
    fun `the smallest H264 picture that still has enough lines`() {
        assertEquals(144, StreamChooser.bestVideo(youtube.filter { it.kind == Kind.VIDEO_ONLY }, 117)!!.height)
        assertEquals(240, StreamChooser.bestVideo(youtube.filter { it.kind == Kind.VIDEO_ONLY }, 145)!!.height)
        assertEquals("avc1.4d400c", StreamChooser.bestVideo(youtube.filter { it.kind == Kind.VIDEO_ONLY }, 100)!!.codec)
        // More lines than any has: the largest up to 480.
        assertEquals(360, StreamChooser.bestVideo(youtube.filter { it.kind == Kind.VIDEO_ONLY }, 700)!!.height)
    }

    @Test
    fun `VP9 when there is no H264, AV1 never`() {
        assertEquals("vp9", StreamChooser.bestVideo(listOf(video(240, "vp9"), video(144, "av01.0.00M.08")), 100)!!.codec)
        assertNull(StreamChooser.bestVideo(listOf(video(144, "av01.0.00M.08")), 100))
    }

    @Test
    fun `without sound the picture only, then HLS, then the muxed file`() {
        val plans = StreamChooser.plans(youtube, "https://manifest.googlevideo.com/hls", live = false, wantHeight = 117, sound = false)
        assertEquals(listOf("144p H.264", "HLS", "360p H.264"), plans.map { it.label })
        assertNull(plans[0].audio)
    }

    @Test
    fun `with sound the smallest AAC track of the original language joins the picture`() {
        val plans = StreamChooser.plans(youtube, null, live = false, wantHeight = 117, sound = true)
        assertEquals("144p H.264 + Ton", plans[0].label)
        assertEquals(48_000, plans[0].audio!!.bitrate)
        assertEquals(true, plans[0].audio!!.original)
        assertEquals(listOf("144p H.264 + Ton", "360p H.264"), plans.map { it.label })
    }

    @Test
    fun `sound without any sound track still shows the picture`() {
        val silent = youtube.filter { it.kind == Kind.VIDEO_ONLY }
        assertEquals(listOf("144p H.264"), StreamChooser.plans(silent, null, live = false, wantHeight = 117, sound = true).map { it.label })
    }

    @Test
    fun `live streams play from HLS only`() {
        assertEquals(listOf("HLS"), StreamChooser.plans(youtube, "https://manifest.googlevideo.com/live", live = true, wantHeight = 117, sound = false).map { it.label })
        assertEquals(emptyList<PlaybackPlan>(), StreamChooser.plans(youtube, null, live = true, wantHeight = 117, sound = false))
    }

    @Test
    fun `addresses that are not https are never used`() {
        val plain = listOf(StreamOption("http://example.org/a.mp4", Kind.VIDEO_ONLY, 144, "avc1", "video/mp4"))
        assertEquals(emptyList<PlaybackPlan>(), StreamChooser.plans(plain, "http://example.org/a.m3u8", live = false, wantHeight = 100, sound = false))
    }
}

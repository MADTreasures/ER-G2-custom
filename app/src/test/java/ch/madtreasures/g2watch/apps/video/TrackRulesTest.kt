package ch.madtreasures.g2watch.apps.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Which picture ExoPlayer plays from a stream with several (HLS, live videos) under [ExoVideoPlayer.trackRules] (Robolectric). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackRulesTest {
    /** A video decoder that plays everything. */
    private val decoder = object : RendererCapabilities {
        override fun getName() = "video"

        override fun getTrackType() = C.TRACK_TYPE_VIDEO

        override fun supportsFormat(format: Format) =
            RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)

        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }

    /** The lines of the picture played from variants of [heights] (bitrate growing with them) when [want] lines are asked for. */
    private fun played(want: Int, heights: List<Int>): Int {
        val context = RuntimeEnvironment.getApplication()
        val selector = DefaultTrackSelector(context)
        // Before init, as ExoVideoPlayer does before the player is built: init takes the rules the player uses.
        selector.setParameters(ExoVideoPlayer.trackRules(selector.buildUponParameters(), want, sound = false))
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        val variants = heights.map { h ->
            Format.Builder().setId("${h}p").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(h * 16 / 9).setHeight(h)
                .setAverageBitrate(h * 1_000).setPeakBitrate(h * 1_000).build()
        }
        val result = selector.selectTracks(arrayOf(decoder), TrackGroupArray(TrackGroup(*variants.toTypedArray())), MediaSource.MediaPeriodId(Any()), Timeline.EMPTY)
        return result.selections[0]!!.selectedFormat.height
    }

    @Test
    fun `the lowest bitrate that still has the lines the raster wants`() {
        val youtube = listOf(144, 240, 360, 480, 720, 1080)
        // The YouTube app's fine raster (144 rows) asks for 288 lines, the coarse one (96 rows) for 240;
        // the lowest bitrate alone would be 144p.
        assertEquals(360, played(StreamChooser.wantHeight(144), youtube))
        assertEquals(240, played(StreamChooser.wantHeight(96), youtube))
        // Nothing large enough: still a picture rather than none.
        assertEquals(144, played(StreamChooser.wantHeight(144), listOf(144)))
    }
}

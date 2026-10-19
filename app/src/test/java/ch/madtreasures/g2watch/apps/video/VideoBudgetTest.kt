package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a video costs on the link to the glasses (≈ 41 KiB/s, 01 §1) and how many pictures a second get
 * through, simulated with Faceclaw's own path to the custom firmware ([GlassesWire]: 32-row bands, run-length
 * boxes, the transport's zlib stream and packets) and a link that keeps only the newest waiting picture
 * ([LinkRun]). Made-up videos in the YouTube app's block; not measured on the glasses.
 */
class VideoBudgetTest {
    private fun run(profile: VideoProfile, video: SyntheticVideo) =
        LinkRun(video, profile.cell, profile.frameMs, VideoBlock.YOUTUBE).also { println("%-10s %s".format(profile.label, it)) }

    @Test
    fun `ordinary scenes get every picture of every profile, the default at least 8 a second`() {
        for (profile in VideoProfile.entries) {
            for (video in listOf(SyntheticVideo.LANDSCAPE, SyntheticVideo.TALK)) {
                val r = run(profile, video)
                assertTrue("$profile $video: $r", r.shown >= r.made * 0.95)
                if (profile == VideoProfile.STABLE) assertTrue("$profile $video: $r", r.linkShare <= 0.5)
            }
        }
        assertTrue(run(VideoProfile.BALANCED, SyntheticVideo.LANDSCAPE).shown >= 8.0)
    }

    @Test
    fun `fine detail everywhere fills the link but keeps moving, faster in the coarse raster`() {
        for (profile in VideoProfile.entries) {
            val r = run(profile, SyntheticVideo.DETAIL)
            assertTrue("$profile: $r", r.shown >= if (profile.cell >= 3) 4.0 else 2.0)
            assertTrue("$profile: $r", r.linkShare <= 1.1)
        }
    }

    @Test
    fun `a still picture costs nothing after the first`() {
        val converter = FrameConverter(VideoBlock.YOUTUBE.w, VideoBlock.YOUTUBE.h, VideoProfile.BALANCED)
        val grid = converter.grid
        val picture = converter.pictureRect(SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT)
        val luma = ByteArray(grid.size)
        FrameConverter.scaleInto(SyntheticVideo.LANDSCAPE.frame(0), SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT, SyntheticVideo.WIDTH, luma, grid.width, picture)
        val first = converter.convert(luma, picture)
        // Codec noise of one luma step does not flip any level, picture after picture.
        repeat(20) { n ->
            val noisy = ByteArray(luma.size) { i -> ((luma[i].toInt() and 0xFF) + if ((i + n) % 3 == 0) 1 else -1).coerceIn(0, 255).toByte() }
            assertArrayEquals("picture $n", first.pixels, converter.convert(noisy, picture).pixels)
        }
    }
}

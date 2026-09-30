package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.desktop.GrayRaster
import com.faceclaw.app.BleImageOptimizer
import com.faceclaw.app.BmpUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

/**
 * What a video costs on the link to the glasses (≈ 41 KiB/s, 01 §1), simulated with Faceclaw's own wire
 * encoding: the changed rectangle of the screen, run-length coded (mode 3), through the persistent zlib
 * stream of the transport. Not measured on the glasses.
 */
class VideoBudgetTest {
    private val screenW = 640
    private val screenH = 480

    /** Where the video block of the YouTube app sits on the screen. */
    private val left = 112
    private val top = 104
    private val blockW = 416
    private val blockH = 234

    /** Bytes per second on the wire for [video] with raster points of [cell] and [levels] grey levels, a picture every [frameMs]. */
    private fun bytesPerSecond(video: SyntheticVideo, cell: Int, levels: Int, frameMs: Long, seconds: Int = 6): Double {
        val converter = FrameConverter(blockW, blockH, VideoProfile.BALANCED)
        converter.look(cell, levels)
        val grid = converter.grid
        val picture = converter.pictureRect(SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT)
        val luma = ByteArray(grid.size)
        val deflater = Deflater()
        val screen = GrayRaster(screenW, screenH)
        var previous = BmpUtil.pack4bppFromGray8(screen.pixels, screenW, screenH)
        val pictures = (seconds * 1000 / frameMs).toInt()
        var total = 0L
        for (n in 0..pictures) {
            val index = (n * frameMs * SyntheticVideo.FPS / 1000).toInt()
            FrameConverter.scaleInto(video.frame(index), SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT, SyntheticVideo.WIDTH, luma, grid.width, picture)
            val raster = converter.convert(luma, picture)
            for (y in 0 until blockH) System.arraycopy(raster.pixels, y * blockW, screen.pixels, (top + y) * screenW + left, blockW)
            val packed = BmpUtil.pack4bppFromGray8(screen.pixels, screenW, screenH)
            val plan = BleImageOptimizer.buildIncrementalImagePayload(previous, packed, screenW, screenH, n + 1)
            previous = packed
            // The first picture fills an empty block; count only the running video.
            if (n == 0 || plan == null) continue
            total += deflated(deflater, plan.payload)
        }
        deflater.end()
        return total * 1000.0 / (pictures * frameMs)
    }

    private fun deflated(deflater: Deflater, input: ByteArray): Int {
        deflater.setInput(input)
        val out = ByteArray(input.size + 1024)
        var size = 0
        while (true) {
            val n = deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH)
            size += n
            if (n < out.size) break
        }
        return size
    }

    @Test
    fun `every profile leaves the link most of its capacity, even for fine detail`() {
        for (profile in VideoProfile.entries) {
            for (video in SyntheticVideo.entries) {
                val bps = bytesPerSecond(video, profile.cell, profile.levels, profile.frameMs)
                println("%-10s %-9s %6.0f B/s".format(profile.label, video, bps))
                assertTrue("${profile.label} $video: ${bps.toInt()} B/s", bps <= MAX_BYTES_PER_SECOND)
                if (video != SyntheticVideo.DETAIL) assertTrue("${profile.label} $video: ${bps.toInt()} B/s", bps <= TYPICAL_BYTES_PER_SECOND)
            }
        }
    }

    @Test
    fun `a still picture costs nothing after the first`() {
        val converter = FrameConverter(blockW, blockH, VideoProfile.STABLE)
        val grid = converter.grid
        val picture = converter.pictureRect(SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT)
        val luma = ByteArray(grid.size)
        FrameConverter.scaleInto(SyntheticVideo.LANDSCAPE.frame(0), SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT, SyntheticVideo.WIDTH, luma, grid.width, picture)
        val first = converter.convert(luma, picture)
        // A little sensor noise does not flip any level.
        val noisy = ByteArray(luma.size) { i -> ((luma[i].toInt() and 0xFF) + (if (i % 3 == 0) 2 else -2)).coerceIn(0, 255).toByte() }
        val second = converter.convert(noisy, picture)
        assertArrayEquals(first.pixels, second.pixels)
    }

    private companion object {
        /** About 40 % of the ≈ 41 KiB/s the link carries: room for everything else and a slower watch. */
        const val MAX_BYTES_PER_SECOND = 16 * 1024.0

        /** Ordinary scenes (not leaves or crowds) stay at an eighth. */
        const val TYPICAL_BYTES_PER_SECOND = 5 * 1024.0
    }
}

package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.desktop.GrayRaster
import com.faceclaw.app.BmpUtil
import com.faceclaw.app.CfwTransport
import com.faceclaw.app.ConnectionOptions
import com.faceclaw.app.ProtocolCondition
import com.faceclaw.app.ProtocolDeflater
import com.faceclaw.app.ProtocolLock
import com.faceclaw.app.ProtocolPlatform
import com.faceclaw.app.ResourceCacheState
import com.faceclaw.app.ScenePlanner
import com.faceclaw.app.ShellScene
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater

/**
 * Faceclaw's way from the watch to the glasses on the custom firmware, for budget tests: the 640 × 480
 * screen packed to 4 bits, the real [ScenePlanner] (one run-length box per changed band of 32 rows, then
 * PRESENT), every command one message through the real [CfwTransport] (the persistent zlib stream, BLE
 * packets for [mtu]). [send] returns the bytes the watch writes for one screen.
 */
class GlassesWire(private val mtu: Int = MTU) {
    private val planner = ScenePlanner(ResourceCacheState())
    private val transport = CfwTransport(JvmPlatform)
    private val options = ConnectionOptions()
    private var frameId = 1

    fun send(screen: GrayRaster): Int {
        val packed = BmpUtil.pack4bppFromGray8(screen.pixels, screen.width, screen.height)
        val plan = planner.plan(
            packed, screen.width, screen.height, null, ShellScene(emptyList()), frameId,
            options.TEXTURE_CACHE_FRAMES, options.INCREMENTAL_FRAMES, options.MULTI_RECT_FRAMES, ConnectionOptions.MULTI_RECT_MAX_RECTS,
        )
        frameId = plan.nextFid
        return plan.commands.sumOf { command -> transport.encode(command, 0, CfwTransport.BOTH, mtu).sumOf { it.size } }
    }

    companion object {
        /** The ATT MTU the glasses grant is not known (g2-kit names 244): 230 bytes of a message per packet. */
        const val MTU = 244
    }

    private object JvmPlatform : ProtocolPlatform {
        override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000

        override fun createLock(): ProtocolLock = object : ProtocolLock {
            private val lock = ReentrantLock()

            override fun lock() = lock.lock()

            override fun unlock() = lock.unlock()
        }

        override fun createDeflater(): ProtocolDeflater = object : ProtocolDeflater {
            private val deflater = Deflater()

            override fun reset() = deflater.reset()

            override fun syncFlush(message: ByteArray): ByteArray {
                deflater.setInput(message)
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                do {
                    val n = deflater.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
                    out.write(chunk, 0, n)
                } while (n == chunk.size)
                return out.toByteArray()
            }

            override fun close() = deflater.end()
        }

        override fun createCondition(): ProtocolCondition = error("not needed")
    }
}

/** Where a video block sits on the 640 × 480 screen, and how large it is. */
data class VideoBlock(val x: Int, val y: Int, val w: Int, val h: Int) {
    companion object {
        /** The YouTube app: 512 × 288, centred and without margins in the full-screen app area (576 × 288 at 32, 96). */
        val YOUTUBE = VideoBlock(64, 96, 512, 288)
    }
}

/**
 * A made-up [video] played into [block] like on the watch: a picture every [frameMs], converted by
 * [FrameConverter] in raster points of [cell] (16 levels), and sent over a link of [LINK_BYTES_PER_SECOND].
 * While the link is busy, a newer picture replaces the waiting one, as Faceclaw's core keeps only the
 * newest frame and plans it at the last moment against the last one sent. Not measured on the glasses.
 */
class LinkRun(
    val video: SyntheticVideo,
    val cell: Int,
    val frameMs: Long,
    val block: VideoBlock = VideoBlock.YOUTUBE,
    val seconds: Int = 6,
) {
    /** Pictures the watch made and pictures the link took, per second. */
    val made: Double
    val shown: Double

    /** Bytes on the wire per picture shown, and the share of the link's time in use. */
    val bytesPerPicture: Double
    val linkShare: Double

    init {
        val converter = FrameConverter(block.w, block.h, VideoProfile.BALANCED)
        converter.look(cell, 16)
        val grid = converter.grid
        val picture = converter.pictureRect(SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT)
        val luma = ByteArray(grid.size)
        val wire = GlassesWire()
        val screen = GrayRaster(SCREEN_W, SCREEN_H)
        val count = (seconds * 1000 / frameMs).toInt()
        val end = count * frameMs.toDouble()
        var linkFreeAt = 0.0
        var waiting: GrayRaster? = null
        var waitingSince = 0.0
        var sent = 0
        var bytes = 0L

        fun take(until: Double) {
            val next = waiting ?: return
            val start = maxOf(linkFreeAt, waitingSince)
            if (start > until) return
            val n = wire.send(next)
            linkFreeAt = start + n * 1000.0 / LINK_BYTES_PER_SECOND
            bytes += n
            sent++
            waiting = null
        }

        for (n in 0..count) {
            val t = n * frameMs.toDouble()
            take(t)
            FrameConverter.scaleInto(video.frame((n * frameMs * SyntheticVideo.FPS / 1000).toInt()), SyntheticVideo.WIDTH, SyntheticVideo.HEIGHT, SyntheticVideo.WIDTH, luma, grid.width, picture)
            val raster = converter.convert(luma, picture, frameMs)
            for (y in 0 until block.h) System.arraycopy(raster.pixels, y * block.w, screen.pixels, (block.y + y) * SCREEN_W + block.x, block.w)
            val copy = GrayRaster(SCREEN_W, SCREEN_H).also { screen.pixels.copyInto(it.pixels) }
            if (n == 0) {
                // The first picture fills an empty block; count only the running video.
                wire.send(copy)
                continue
            }
            waiting = copy
            waitingSince = t
            take(t)
        }
        made = count * 1000.0 / end
        shown = sent * 1000.0 / end
        bytesPerPicture = if (sent == 0) 0.0 else bytes.toDouble() / sent
        linkShare = bytes * 1000.0 / LINK_BYTES_PER_SECOND / end
    }

    override fun toString() = "%-9s c%d %4d ms: %5.1f made, %5.1f shown/s, %6.0f B/picture, link %3.0f %%"
        .format(video, cell, frameMs, made, shown, bytesPerPicture, linkShare * 100)

    companion object {
        const val SCREEN_W = 640
        const val SCREEN_H = 480

        /** Sustained throughput on the custom firmware with window 3 (01 §1; Faceclaw's figure for 2.2.9). */
        const val LINK_BYTES_PER_SECOND = 41 * 1024.0
    }
}

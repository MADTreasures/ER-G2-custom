package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.render.Levels
import ch.madtreasures.g2watch.desktop.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video pictures to the raster of the glasses (03 §10). */
class FrameConverterTest {
    @Test
    fun `the grid is the block in cells, centred`() {
        val c = FrameConverter(416, 234, VideoProfile.BALANCED)
        assertEquals(208, c.grid.width)
        assertEquals(117, c.grid.height)
        c.profile = VideoProfile.FAST
        // 416 / 3 = 138 cells, 2 pixels left over: one on each side.
        assertEquals(138, c.grid.width)
        assertEquals(78, c.grid.height)
        assertEquals(1, c.grid.offsetX)
        // The YouTube app's block in the default profile: 256 × 144 points.
        val big = FrameConverter(512, 288, VideoProfile.BALANCED)
        assertEquals(256, big.grid.width)
        assertEquals(144, big.grid.height)
    }

    @Test
    fun `every profile has all 16 levels, the default the fine raster`() {
        assertTrue(VideoProfile.entries.all { it.levels == 16 })
        assertEquals(2, VideoProfile.BALANCED.cell)
        assertTrue(VideoProfile.entries.all { it.frameMs in 66..200 })
    }

    @Test
    fun `pictures are letterboxed, not stretched`() {
        val c = FrameConverter(416, 234, VideoProfile.STABLE)
        assertEquals(Rect(0, 0, 208, 117), c.pictureRect(1280, 720))
        // 4:3 in 16:9: bars left and right.
        assertEquals(Rect(26, 0, 156, 117), c.pictureRect(640, 480))
        // Very wide: bars above and below.
        assertEquals(Rect(0, 29, 208, 58), c.pictureRect(2560, 720))
        assertEquals(Rect(0, 0, 208, 117), c.pictureRect(0, 0))
    }

    @Test
    fun `every point becomes a cell of one of the profile's levels, the rest stays black`() {
        for (profile in VideoProfile.entries) {
            val c = FrameConverter(100, 60, profile)
            val g = c.grid
            val picture = Rect(2, 1, g.width - 4, g.height - 2)
            val luma = ByteArray(g.size) { i -> ((i * 37) % 256).toByte() }
            val raster = c.convert(luma, picture)
            val allowed = (0 until profile.levels).map { q -> Levels.of((q * 15 + (profile.levels - 1) / 2) / (profile.levels - 1)) }.toSet()
            val used = raster.pixels.map { it.toInt() and 0xFF }.toSet()
            assertTrue("$profile uses $used", allowed.containsAll(used))
            // A cell is uniform.
            val x0 = g.offsetX + 5 * g.cell
            val y0 = g.offsetY + 3 * g.cell
            val v = raster[x0, y0]
            for (dy in 0 until g.cell) for (dx in 0 until g.cell) assertEquals(v, raster[x0 + dx, y0 + dy])
            // Outside the picture: black.
            assertEquals(0, raster[g.offsetX, g.offsetY])
            assertEquals(0, raster[raster.width - 1, raster.height - 1])
        }
    }

    @Test
    fun `a dull picture is stretched to the full range`() {
        val c = FrameConverter(64, 32, VideoProfile.STABLE)
        val g = c.grid
        // Only 100 to 180: the darkest part becomes black (see-through), the brightest the top level.
        val luma = ByteArray(g.size) { i -> (100 + (i % g.width) * 80 / (g.width - 1)).toByte() }
        val raster = c.convert(luma)
        val values = raster.pixels.map { it.toInt() and 0xFF }
        assertEquals(0, values.min())
        assertEquals(255, values.max())
    }

    @Test
    fun `small wobbles do not flip levels, real changes do`() {
        val c = FrameConverter(64, 32, VideoProfile.STABLE)
        val g = c.grid
        val ramp = ByteArray(g.size) { i -> ((i % g.width) * 255 / (g.width - 1)).toByte() }
        val first = c.convert(ramp)
        // Codec noise of one luma step, picture after picture.
        repeat(10) { n ->
            val wobble = ByteArray(g.size) { i -> ((ramp[i].toInt() and 0xFF) + if ((i + n) % 2 == 0) 1 else -1).coerceIn(0, 255).toByte() }
            assertEquals(first.pixels.toList(), c.convert(wobble).pixels.toList())
        }
        val inverted = ByteArray(g.size) { i -> (255 - (ramp[i].toInt() and 0xFF)).toByte() }
        val flipped = c.convert(inverted)
        assertTrue(flipped[0, 0] > first[0, 0])
    }

    @Test
    fun `the contrast follows per second, whatever the picture rate`() {
        fun after(pictures: Int, elapsedMs: Long): ClosedFloatingPointRange<Float> {
            val c = FrameConverter(64, 32, VideoProfile.BALANCED)
            val g = c.grid
            // From a dull picture (100 to 180) to the full range (0 to 255).
            c.convert(ByteArray(g.size) { i -> (100 + (i % g.width) * 80 / (g.width - 1)).toByte() })
            val full = ByteArray(g.size) { i -> ((i % g.width) * 255 / (g.width - 1)).toByte() }
            repeat(pictures) { c.convert(full, elapsedMs = elapsedMs) }
            return c.contrast
        }
        val fast = after(10, 100)
        val slow = after(5, 200)
        // One second: three quarters of the way, at 10 pictures a second as at 5.
        assertEquals(25f, fast.start, 0.5f)
        assertEquals(236.25f, fast.endInclusive, 0.5f)
        assertEquals(fast.start, slow.start, 0.5f)
        assertEquals(fast.endInclusive, slow.endInclusive, 0.5f)
        // And it gets there, to within the dead band.
        val late = after(50, 100)
        assertEquals(0f, late.start, FrameConverter.DEAD_BAND)
        assertEquals(255f, late.endInclusive, FrameConverter.DEAD_BAND)
    }

    @Test
    fun `a still scene keeps its tone curve, a new one moves it`() {
        val c = FrameConverter(64, 32, VideoProfile.BALANCED)
        val g = c.grid
        fun scene(offset: Int) = ByteArray(g.size) { i -> (60 + offset + (i % g.width) * 120 / (g.width - 1)).toByte() }
        c.convert(scene(0))
        val settled = c.contrast
        // Exposure and codec noise: the whole picture a few steps brighter or darker.
        for (offset in listOf(3, -2, 4, -4, 1, 0, 2, -3)) {
            c.convert(scene(offset), elapsedMs = 100)
            assertEquals(settled, c.contrast)
        }
        c.convert(scene(40), elapsedMs = 100)
        assertTrue(c.contrast.start > settled.start)
    }

    @Test
    fun `area scaling averages the source`() {
        val src = ByteArray(4 * 2) { i -> if (i % 2 == 0) 0 else 200.toByte() }
        val out = ByteArray(2)
        FrameConverter.scaleInto(src, 4, 2, 4, out, 2, Rect(0, 0, 2, 1))
        assertEquals(100, out[0].toInt() and 0xFF)
        assertEquals(100, out[1].toInt() and 0xFF)
    }
}

package ch.madtreasures.g2watch.glasses

import ch.madtreasures.g2watch.desktop.GlassesDisplay
import com.faceclaw.app.ArrayByteReader
import com.faceclaw.app.FrameTimingsCore
import com.faceclaw.app.GlassesSessionCore
import com.faceclaw.app.SurfaceCompositor

/**
 * [GlassesDisplay] over the compositor inside Faceclaw's session. Every submit updates a block of
 * one surface; the session compares the new composite with what the glasses show and sends only
 * the changed region.
 */
class CoreDisplay(private val core: GlassesSessionCore, private val timings: FrameTimingsCore) : GlassesDisplay {

    override fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean) {
        val transparency = if (colorKey) SurfaceCompositor.TRANSPARENCY_COLOR_KEY else SurfaceCompositor.TRANSPARENCY_OPAQUE
        core.configureSurface(id, x, y, width, height, zOrder, transparency)
    }

    override fun submit(id: String, pixels: ByteArray, x: Int, y: Int, width: Int, height: Int, fingerprint: String) {
        val frameId = timings.startFrame("g2watch:$id")
        core.submitSurfaceFrame(ArrayByteReader(pixels), id, x, y, width, height, fingerprint, 0, frameId, null)
    }

    override fun setDepth(id: String, depth: Int) {
        core.setSurfaceDepth(id, depth)
        // The depth applies from the next composite; showing the (already shown) surface composites at once.
        core.setSurfaceVisible(id, true)
    }
}

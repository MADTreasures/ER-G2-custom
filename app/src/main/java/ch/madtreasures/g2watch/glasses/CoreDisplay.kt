package ch.madtreasures.g2watch.glasses

import ch.madtreasures.g2watch.desktop.GlassesDisplay
import com.faceclaw.app.ArrayByteReader
import com.faceclaw.app.FrameTimingsCore
import com.faceclaw.app.GlassesSessionCore
import com.faceclaw.app.SurfaceCompositor

/**
 * [GlassesDisplay] over the compositor inside Faceclaw's session. Every submit replaces a whole
 * surface; the session compares the new composite with what the glasses show and sends only
 * the changed region.
 */
class CoreDisplay(private val core: GlassesSessionCore, private val timings: FrameTimingsCore) : GlassesDisplay {

    override fun configureSurface(id: String, x: Int, y: Int, width: Int, height: Int, zOrder: Int, colorKey: Boolean) {
        val transparency = if (colorKey) SurfaceCompositor.TRANSPARENCY_COLOR_KEY else SurfaceCompositor.TRANSPARENCY_OPAQUE
        core.configureSurface(id, x, y, width, height, zOrder, transparency)
    }

    override fun submit(id: String, pixels: ByteArray, width: Int, height: Int, fingerprint: String) {
        val frameId = timings.startFrame("g2watch:$id")
        core.submitSurfaceFrame(ArrayByteReader(pixels), id, 0, 0, width, height, fingerprint, 0, frameId, null)
    }
}

package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.webraster.Contrast
import ch.madtreasures.g2watch.webraster.GlassesRasterizer
import ch.madtreasures.g2watch.webraster.LayoutParser
import ch.madtreasures.g2watch.webraster.RasterOptions
import kotlinx.serialization.json.JsonObject

/**
 * The glasses' picture of a captured page (05 §10.1): the pixels as GeckoView painted them, the layout
 * the extension reported and the second capture without text go through web-raster. Pure Kotlin, so
 * the whole path from capture to picture is tested without a watch.
 */
object WebPicture {
    /**
     * Device pixels per CSS pixel: 576 pixels are 384 CSS pixels, so pages lay out as on a small phone
     * (the same as in the Gecko test).
     */
    const val DENSITY = 1.5f

    /** [argb] (and [textless]) are [width] × [height] pixels; the result has the same size. */
    fun raster(
        width: Int,
        height: Int,
        argb: IntArray,
        layout: JsonObject?,
        textless: IntArray?,
        contrast: WebContrast = WebContrast.OUTLINE,
    ): GrayRaster {
        val capture = LayoutParser.capture(width, height, argb, layout, textless)
        val glasses = GlassesRasterizer.rasterize(capture, options(width, contrast))
        val out = GrayRaster(width, height)
        glasses.pixels.copyInto(out.pixels, endIndex = minOf(glasses.pixels.size, out.pixels.size))
        return out
    }

    fun options(width: Int, contrast: WebContrast): RasterOptions = RasterOptions(
        width = width,
        contrast = when (contrast) {
            WebContrast.OUTLINE -> Contrast.OUTLINE
            WebContrast.HALO -> Contrast.HALO
            WebContrast.PLATE -> Contrast.PLATE
        },
    )

    /** [dy] pixels of the picture in CSS pixels of the page. */
    fun cssPixels(dy: Int): Double = dy / DENSITY.toDouble()
}

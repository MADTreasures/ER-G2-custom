package ch.madtreasures.g2watch.webraster

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * Turns the layout that `collectLayout()` of `src/main/js/page-layout.js` reports (CSS pixels, CSS
 * colours) and a captured picture of the page into a [PageCapture]. [textless] is the second capture
 * with all text transparent, when there is one. Used by the browser of the watch app and by the Gecko
 * test.
 */
object LayoutParser {

    fun capture(width: Int, height: Int, argb: IntArray, layout: JsonObject?, textless: IntArray? = null): PageCapture {
        if (layout == null) return PageCapture(width, height, argb)
        val vw = layout.number("vw") ?: return PageCapture(width, height, argb)
        // CSS pixels to captured pixels: the surface may be smaller or larger than the viewport.
        val scale = width / vw
        fun box(a: JsonArray): Box? {
            if (a.size < 4) return null
            val v = (0 until 4).map { (a[it] as? JsonPrimitive)?.doubleOrNull ?: return null }
            return Box((v[0] * scale).roundToInt(), (v[1] * scale).roundToInt(), (v[2] * scale).roundToInt(), (v[3] * scale).roundToInt())
        }
        fun colour(a: JsonArray): Int? = (a.getOrNull(4) as? JsonPrimitive)?.content?.let(::cssColour)
        val texts = layout.array("texts").mapNotNull { e -> (e as? JsonArray)?.let { a -> box(a)?.let { TextRun(it, colour(a)) } } }
        // [x, y, w, h] for pictures, [x, y, w, h, "g"] for vector drawings (graphics).
        val allPictures = layout.array("pictures").mapNotNull { e -> (e as? JsonArray)?.let { a -> box(a)?.let { it to ((a.getOrNull(4) as? JsonPrimitive)?.content == "g") } } }
        val pictures = allPictures.filter { !it.second }.map { it.first }
        val graphics = allPictures.filter { it.second }.map { it.first }
        val surfaces = ArrayList<Surface>()
        // The page's own background first (largest), then the elements with a background colour.
        val page = listOfNotNull(layout.string("page"), layout.string("body")).mapNotNull(::cssColour).lastOrNull()
        if (page != null) surfaces += Surface(Box(0, 0, width, height), page)
        layout.array("surfaces").forEach { e ->
            val a = e as? JsonArray ?: return@forEach
            val b = box(a) ?: return@forEach
            val c = colour(a) ?: return@forEach
            surfaces += Surface(b, c)
        }
        return PageCapture(width, height, argb, texts, pictures, surfaces, textless?.takeIf { it.size == argb.size }, graphics)
    }

    /**
     * `rgb(r, g, b)` or `rgba(r, g, b, a)` as `getComputedStyle` reports them, as ARGB; null for
     * anything else or a fully transparent colour.
     */
    fun cssColour(css: String): Int? {
        val m = Regex("""rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""").matchEntire(css.trim()) ?: return null
        fun channel(i: Int) = m.groupValues[i].toDouble().roundToInt().coerceIn(0, 255)
        val alphaText = m.groupValues[4]
        val alpha = when {
            alphaText.isEmpty() -> 255
            alphaText.endsWith("%") -> (alphaText.dropLast(1).toDouble() * 2.55).roundToInt()
            else -> (alphaText.toDouble() * 255).roundToInt()
        }.coerceIn(0, 255)
        if (alpha == 0) return null
        return (alpha shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}

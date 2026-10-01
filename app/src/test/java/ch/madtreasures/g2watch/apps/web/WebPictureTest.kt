package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.webraster.Contrast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebRequestError
import java.util.Base64

/** From a captured page to the picture of the image block, and what the browser says when it fails. */
class WebPictureTest {
    private val w = 576
    private val h = 260

    /** A light page with dark lines of "text" (stripes), as GeckoView would paint it, and its layout. */
    private fun article(): Pair<IntArray, String> {
        val argb = IntArray(w * h) { 0xFFF8F8F8.toInt() }
        for (line in 0 until 4) {
            val y0 = 30 + line * 40
            for (y in y0 until y0 + 16) for (x in 24 until 520) if ((x / 3 + y) % 2 == 0) argb[y * w + x] = 0xFF101010.toInt()
        }
        val texts = (0 until 4).joinToString(",") { "[16,${20 + it * 26.667},336,10.667,\"rgb(16, 16, 16)\"]" }
        return argb to """{"dpr":1.5,"vw":384,"vh":173.33,"texts":[$texts],"pictures":[],"surfaces":[],"page":"rgb(248, 248, 248)","body":"rgba(0, 0, 0, 0)"}"""
    }

    @Test
    fun `a light page becomes bright text on a see-through ground, at the block's size`() {
        val (argb, layout) = article()
        val raster = WebPicture.raster(w, h, argb, Json.parseToJsonElement(layout).jsonObject, null)
        assertEquals(w, raster.width)
        assertEquals(h, raster.height)
        // The white page is see-through, the text lights up.
        assertEquals(0, raster[560, 250])
        assertEquals(0, raster[300, 20])
        assertTrue((30 until 46).any { y -> (24 until 520).any { x -> raster[x, y] >= 240 } })
        // Only the 16 levels of the glasses.
        val allowed = (0 until 16).map { if (it == 15) 255 else it * 16 }.toSet()
        assertTrue(raster.pixels.all { (it.toInt() and 0xFF) in allowed })
    }

    @Test
    fun `without a layout the pixels alone make the picture`() {
        val (argb, _) = article()
        val raster = WebPicture.raster(w, h, argb, null, null)
        assertEquals(0, raster[560, 250])
        assertTrue((30 until 46).any { y -> (24 until 520).any { x -> raster[x, y] > 0 } })
    }

    @Test
    fun `the contrast styles of the app interface are those of web-raster`() {
        assertEquals(Contrast.OUTLINE, WebPicture.options(w, WebContrast.OUTLINE).contrast)
        assertEquals(Contrast.HALO, WebPicture.options(w, WebContrast.HALO).contrast)
        assertEquals(Contrast.PLATE, WebPicture.options(w, WebContrast.PLATE).contrast)
        assertEquals(w, WebPicture.options(w, WebContrast.OUTLINE).width)
        // 576 picture pixels are 384 CSS pixels.
        assertEquals(384.0, WebPicture.cssPixels(576), 1e-9)
    }

    @Test
    fun `errors are told in German, with a dark page in place of the missing one`() {
        assertEquals("Adresse nicht gefunden", WebErrors.text(WebRequestError.ERROR_CATEGORY_URI, WebRequestError.ERROR_UNKNOWN_HOST))
        assertEquals("Kein Internet", WebErrors.text(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_OFFLINE))
        assertEquals("Zertifikat der Seite ungültig", WebErrors.text(WebRequestError.ERROR_CATEGORY_SECURITY, WebRequestError.ERROR_SECURITY_BAD_CERT))
        assertEquals("Netzwerkfehler", WebErrors.text(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_UNKNOWN))

        val page = WebErrors.page("Adresse nicht gefunden", "https://nirgends.example/<script>")
        assertTrue(page.startsWith("data:text/html;charset=utf-8;base64,"))
        val html = String(Base64.getDecoder().decode(page.substringAfter("base64,")), Charsets.UTF_8)
        assertTrue(html.contains("Adresse nicht gefunden"))
        assertTrue(html.contains("background:#000"))
        // The address is shown, never run.
        assertTrue(html.contains("&lt;script&gt;") && !html.contains("<script>"))
    }

    @Test
    fun `links to other apps stay on the watch`() {
        assertEquals(null, WebErrors.foreignScheme("https://srf.ch/"))
        assertEquals(null, WebErrors.foreignScheme("about:blank"))
        assertEquals("tel", WebErrors.foreignScheme("tel:+41441234567"))
        assertEquals("intent", WebErrors.foreignScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("Telefonnummern lassen sich hier nicht anrufen", WebErrors.foreignLink("tel"))
        assertTrue(WebErrors.foreignLink("market").contains("market:"))
    }
}

package ch.madtreasures.g2watch.apps.web

import ch.madtreasures.g2watch.apps.WebField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The messages between the browser engine and its extension (assets/webbridge/content.js), and the
 * extension's files as GeckoView will load them.
 */
class WebBridgeTest {

    @Test
    fun `decodes what the content script sends`() {
        assertEquals(PageMessage.Hello("https://srf.ch/"), WebBridge.decode("""{"kind":"hello","url":"https://srf.ch/"}"""))
        val layout = WebBridge.decode("""{"kind":"layout","id":7,"layout":{"vw":384,"texts":[[1,2,3,4,"rgb(0, 0, 0)"]]}}""") as PageMessage.Layout
        assertEquals(7L, layout.id)
        assertEquals(384, layout.layout["vw"]!!.jsonPrimitive.content.toInt())
        assertEquals(PageMessage.Staged(8), WebBridge.decode("""{"kind":"staged","id":8}"""))
        assertEquals(
            PageMessage.Field(WebField("Suche", "Brille", password = false, multiline = false)),
            WebBridge.decode("""{"kind":"field","field":{"label":"Suche","value":"Brille","password":false,"multiline":false}}"""),
        )
        assertEquals(PageMessage.Field(null), WebBridge.decode("""{"kind":"field","field":null}"""))
        assertEquals(PageMessage.Reader(on = true, readable = true), WebBridge.decode("""{"kind":"reader","on":true,"readable":true}"""))
        assertEquals(PageMessage.Changed, WebBridge.decode("""{"kind":"changed"}"""))
    }

    @Test
    fun `ignores what is no message`() {
        assertNull(WebBridge.decode("not json"))
        assertNull(WebBridge.decode("[1]"))
        assertNull(WebBridge.decode("""{"kind":"teleport"}"""))
        assertNull(WebBridge.decode("""{"kind":"layout","layout":{}}"""))
        assertNull(WebBridge.decode("""{"kind":"staged"}"""))
        // A broken field is no field.
        assertEquals(PageMessage.Field(null), WebBridge.decode("""{"kind":"field","field":[1,2]}"""))
    }

    @Test
    fun `sends what the content script reads`() {
        fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
        assertEquals("""{"kind":"config","reader":true}""", WebBridge.config(true))
        assertEquals("""{"kind":"reader","on":false}""", WebBridge.reader(false))
        assertEquals("""{"kind":"layout","id":3}""", WebBridge.layoutRequest(3))
        assertEquals("""{"kind":"stage","id":4,"stage":"hide-text"}""", WebBridge.stage(4, "hide-text"))
        assertEquals(130.0, parse(WebBridge.scroll(WebPicture.cssPixels(195)))["dy"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        val type = parse(WebBridge.type("Katzen \"im\" Schnee", enter = true))
        assertEquals("Katzen \"im\" Schnee", type["text"]!!.jsonPrimitive.content)
        assertEquals("true", type["enter"]!!.jsonPrimitive.content)
    }

    // Unit tests run in the module directory.
    private val extension = File("src/main/assets/webbridge")
    private val pageLayout = File("../web-raster/src/main/js/page-layout.js")

    @Test
    fun `the extension has every file its manifest names, with the id and port of the engine`() {
        val manifest = Json.parseToJsonElement(File(extension, "manifest.json").readText()).jsonObject
        assertEquals(GeckoWebEngine.EXTENSION_ID, manifest["browser_specific_settings"]!!.jsonObject["gecko"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val scripts = Regex(""""js"\s*:\s*\[([^\]]*)]""").find(File(extension, "manifest.json").readText())!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }
        assertEquals("content.js", scripts.last())
        for (script in scripts) {
            // page-layout.js comes from web-raster at build time (root build.gradle.kts).
            val file = if (script == "page-layout.js") pageLayout else File(extension, script)
            assertTrue("$script missing", file.isFile)
        }
        val content = File(extension, "content.js").readText()
        assertTrue(content.contains("""connectNative("${WebBridge.NATIVE_APP}")"""))
        assertTrue(GeckoWebEngine.EXTENSION_URI.endsWith("/webbridge/"))
        // What the content script calls is there.
        val layout = pageLayout.readText()
        assertTrue(layout.contains("function collectLayout(") && layout.contains("function stage("))
        assertTrue(File(extension, "readability/Readability.js").readText().contains("function Readability("))
        assertTrue(File(extension, "readability/Readability-readerable.js").readText().contains("function isProbablyReaderable("))
        // Every message kind the engine decodes is one the script sends.
        for (kind in listOf("hello", "layout", "staged", "field", "reader", "changed")) assertTrue(kind, content.contains("kind: \"$kind\""))
    }
}

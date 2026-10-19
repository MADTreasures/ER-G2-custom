package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `g2app.json` and the build step that writes it (docs/app-entwicklung/09 §2–§3). */
class PackageManifestTest {

    class SampleApp : G2App {
        override val manifest = AppManifest(
            id = "ch.test.probe",
            name = "Probe",
            version = "2.1.0",
            input = InputMode.GESTURES,
            permissions = setOf(Permission.NETWORK, Permission.BUZZER),
            ui = "apps/ch.test.probe/ui.json",
            description = "Nur ein Test.",
        )

        override fun onEvent(event: AppEvent, ui: AppContext) = Unit
    }

    @Test
    fun `json round trip keeps everything`() {
        val m = PackageManifest(G2AppApi.VERSION, SampleApp::class.java.name, SampleApp().manifest)
        assertEquals(m, PackageManifest.parse(m.toJson()))
        assertTrue(m.toJson().contains("\"format\":\"g2app-paket@1\""))
    }

    @Test
    fun `defaults for optional fields`() {
        val m = PackageManifest.parse("""{"format":"g2app-paket@1","api":1,"main":"a.B","id":"ch.a.b","name":"B","version":"1.0.0"}""")
        assertEquals(InputMode.POINTER, m.app.input)
        assertEquals(emptySet(), m.app.permissions)
        assertEquals(null, m.app.ui)
        assertNull(m.icon)
    }

    @Test
    fun `the icon is written only when there is one and must be a png path inside the package`() {
        val plain = PackageManifest(G2AppApi.VERSION, "a.B", SampleApp().manifest)
        assertFalse(plain.toJson().contains("icon"))
        val withIcon = plain.copy(icon = "apps/ch.test.probe/icon.png")
        assertTrue(withIcon.toJson().contains("\"icon\":\"apps/ch.test.probe/icon.png\""))
        assertEquals(withIcon, PackageManifest.parse(withIcon.toJson()))
        for (bad in listOf("../icon.png", "/apps/icon.png", "apps//icon.png", "apps/./icon.png", "c:/icon.png", "apps\\icon.png", "apps/icon.jpg", "")) {
            val json = plain.toJson().removeSuffix("}") + ",\"icon\":${JsonPrimitive(bad)}}"
            assertFailsWith<PackageFormatException>(bad) { PackageManifest.parse(json) }
        }
    }

    @Test
    fun `icon bytes are checked without decoding`() {
        val path = "apps/a/icon.png"
        assertNull(PackageManifest.iconProblem(path, png(64, 64)))
        assertNull(PackageManifest.iconProblem(path, png(16, 16)))
        assertNull(PackageManifest.iconProblem(path, png(256, 256)))
        assertEquals("Symbol $path ist kein PNG", PackageManifest.iconProblem(path, "GIF89a".toByteArray() + ByteArray(40)))
        assertEquals("Symbol $path ist kein PNG", PackageManifest.iconProblem(path, png(64, 64).copyOf(20)))
        assertEquals("Symbol $path ist nicht quadratisch (64 × 48 Pixel)", PackageManifest.iconProblem(path, png(64, 48)))
        assertEquals("Symbol $path muss 16 bis 256 Pixel groß sein, nicht 8 × 8", PackageManifest.iconProblem(path, png(8, 8)))
        assertEquals("Symbol $path muss 16 bis 256 Pixel groß sein, nicht 300 × 300", PackageManifest.iconProblem(path, png(300, 300)))
        val big = png(64, 64) + ByteArray(PackageManifest.ICON_MAX_BYTES)
        assertEquals("Symbol $path ist zu groß (höchstens 64 KB)", PackageManifest.iconProblem(path, big))
    }

    @Test
    fun `the build step names the icon it finds and refuses a bad one`(@TempDir tmp: File) {
        val classes = File(SampleApp::class.java.protectionDomain.codeSource.location.toURI())
        val dir = File(tmp, "assets/apps/ch.test.probe").apply { mkdirs() }
        File(dir, "ui.json").writeText("{}")
        assertNull(describe(listOf(classes), File(tmp, "assets")).icon)

        File(dir, "icon.png").writeBytes(png(64, 64))
        assertEquals("apps/ch.test.probe/icon.png", describe(listOf(classes), File(tmp, "assets")).icon)

        File(dir, "icon.png").writeBytes(png(64, 32))
        val e = assertFailsWith<PackageFormatException> { describe(listOf(classes), File(tmp, "assets")) }
        assertEquals("Symbol apps/ch.test.probe/icon.png ist nicht quadratisch (64 × 32 Pixel)", e.message)
    }

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY), "png", it)
    }.toByteArray()

    @Test
    fun `the build step finds the one app and checks its pages`(@TempDir tmp: File) {
        val classes = File(SampleApp::class.java.protectionDomain.codeSource.location.toURI())
        val assets = File(tmp, "assets").apply { File(this, "apps/ch.test.probe").mkdirs() }
        // This test class and SampleApp are both in the test classes; only SampleApp is a G2App.
        val missing = assertFailsWith<PackageFormatException> { describe(listOf(classes), assets) }
        assertEquals("Seiten apps/ch.test.probe/ui.json fehlen in src/main/assets", missing.message)

        File(assets, "apps/ch.test.probe/ui.json").writeText("{}")
        val m = describe(listOf(classes), assets)
        assertEquals(SampleApp::class.java.name, m.main)
        assertEquals(G2AppApi.VERSION, m.api)
        assertEquals("ch.test.probe", m.app.id)
    }

    @Test
    fun `a package without an app is refused`(@TempDir tmp: File) {
        assertEquals("Keine Klasse implementiert G2App", assertFailsWith<PackageFormatException> { describe(listOf(tmp), null) }.message)
    }
}

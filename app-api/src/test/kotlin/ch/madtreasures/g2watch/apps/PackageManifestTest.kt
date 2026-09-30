package ch.madtreasures.g2watch.apps

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    }

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

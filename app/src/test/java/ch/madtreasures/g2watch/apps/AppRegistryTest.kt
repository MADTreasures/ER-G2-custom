package ch.madtreasures.g2watch.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppRegistryTest {
    @Test
    fun `every built-in app has a unique, valid manifest and shows a page on start`() {
        val apps = builtInApps.map { it() }
        assertEquals(apps.size, apps.map { it.manifest.id }.toSet().size)
        for (app in apps) {
            val ui = FakeAppContext.forApp(app)
            app.onEvent(AppEvent.Start, ui)
            app.onEvent(AppEvent.Visible, ui)
            assertNotNull("${app.manifest.id} shows no page", ui.current)
        }
    }

    @Test
    fun `the launcher order is stopwatch, shopping list, YouTube`() {
        assertEquals(listOf("Stoppuhr", "Einkauf", "YouTube"), builtInApps.map { it().manifest.name })
    }
}

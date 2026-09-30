package ch.madtreasures.g2watch.geckoprobe

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The probe's screen on a simulated round watch (Robolectric): it starts, lists the tests and
 * the watch, and saves a report. The tests themselves need GeckoView and a real watch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w228dp-h228dp-round-watch-xhdpi", application = Application::class)
class ProbeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    @Test
    fun `shows the tests in order, the watch and an open decision`() {
        val lab = GeckoLab(app)
        var screenOn: Boolean? = null
        compose.setContent { MaterialTheme { ProbeScreen(lab, keepScreenOn = { screenOn = it }) } }

        compose.onNodeWithText("Gecko-Test").assertExists()
        for (label in listOf("1 · Schnelltest", "2 · Timer-Test", "3 · Dauertest", "4 · Seite rendern", "5 · Wikipedia rendern")) {
            scrollTo(hasText(label))
            compose.onNodeWithText(label).assertExists()
        }
        scrollTo(hasText("Gerät:", substring = true))
        scrollTo(hasText("Empfehlung: noch offen – nicht alle Messungen gemacht"))
        compose.onNodeWithText("Bericht").assertExists()
        // Nothing runs, so the screen may go dark as usual.
        assertEquals(false, screenOn)
        assertEquals(null, lab.running.value)
    }

    @Test
    fun `the report button writes the report file`() {
        val lab = GeckoLab(app)
        compose.setContent { MaterialTheme { ProbeScreen(lab, keepScreenOn = {}) } }
        // The edge button is folded away at the top of a long list; the tap goes to it directly.
        compose.onNodeWithText("Bericht").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        val file = File(app.getExternalFilesDir(null) ?: app.filesDir, GeckoLab.REPORT)
        assertTrue(file.path, file.isFile)
        val text = file.readText()
        assertTrue(text, text.startsWith("G2 Gecko-Test ${BuildConfig.VERSION_NAME} · "))
        assertTrue(text, "Empfehlung: noch offen – nicht alle Messungen gemacht" in text)
        scrollTo(hasText("adb pull", substring = true))
    }
}

/** The activity with the real application class: it starts in the main process and shows its screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-rDE-w228dp-h228dp-round-watch-xhdpi")
class ProbeActivityTest {

    @Test
    fun `starts in the main process without GeckoView running`() {
        val controller = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        val probe = activity.application as ProbeApp
        assertTrue("main process", probe.isMainProcess)
        assertFalse("finishing", activity.isFinishing)
        // The engine starts only with the first test.
        assertEquals(null, probe.lab.running.value)
        controller.pause().stop().destroy()
    }
}

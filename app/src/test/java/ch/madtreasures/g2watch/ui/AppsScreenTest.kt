package ch.madtreasures.g2watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.material3.MaterialTheme
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.PackageManifest
import ch.madtreasures.g2watch.apps.packages.WaitingPackage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The page "Apps" on the watch: installing waiting files, removing with two taps (09 §4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w228dp-h228dp-round-watch-xhdpi", application = android.app.Application::class)
class AppsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pkg(id: String, name: String, version: String = "1.0.0") =
        PackageManifest(1, "$id.App", AppManifest(id, name, version))

    private val installs = mutableListOf<String>()
    private val removals = mutableListOf<String>()

    private fun show(
        waiting: List<WaitingPackage> = emptyList(),
        installed: List<PackageManifest> = emptyList(),
        message: String? = null,
    ) = compose.setContent {
        MaterialTheme {
            AppsScreen(
                waiting = waiting,
                installed = installed,
                builtIn = listOf("YouTube"),
                message = message,
                busy = false,
                inboxPath = "/storage/emulated/0/Android/data/ch.madtreasures.g2watch/files/apps",
                onInstall = { installs += it.file.name },
                onRemove = { removals += it },
                onBack = {},
            )
        }
    }

    private fun scrollTo(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    @Test
    fun `a waiting package is installed with one tap, a broken one says why`() {
        show(
            waiting = listOf(
                WaitingPackage(File("stoppuhr.g2app"), pkg("ch.madtreasures.stoppuhr", "Stoppuhr"), null),
                WaitingPackage(File("kaputt.g2app"), null, "kaputt.g2app ist keine ZIP-Datei"),
            ),
        )
        scrollTo("Stoppuhr")
        compose.onNodeWithText("Stoppuhr").performClick()
        assertEquals(listOf("stoppuhr.g2app"), installs)
        scrollTo("kaputt.g2app ist keine ZIP-Datei")
        compose.onNodeWithText("kaputt.g2app").performClick()
        assertEquals("a broken file cannot be installed", listOf("stoppuhr.g2app"), installs)
    }

    @Test
    fun `removing takes a second tap`() {
        show(installed = listOf(pkg("ch.madtreasures.einkauf", "Einkauf")))
        scrollTo("Einkauf")
        compose.onNodeWithText("Einkauf").performClick()
        assertEquals(emptyList<String>(), removals)
        compose.onNodeWithText("Nochmals tippen: entfernen").assertExists()
        compose.onNodeWithText("Einkauf").performClick()
        assertEquals(listOf("ch.madtreasures.einkauf"), removals)
    }

    @Test
    fun `without own apps it says so and where new files go`() {
        show(message = "Stoppuhr entfernt")
        compose.onNodeWithText("Stoppuhr entfernt").assertExists()
        scrollTo("Noch keine eigenen Apps.")
        scrollTo("YouTube")
        scrollTo(
            "Datei *.g2app auf die Uhr legen, in den Ordner Android/data/ch.madtreasures.g2watch/files/apps " +
                "(Android Studio: Device Explorer). Dann erscheint sie hier.",
        )
    }
}

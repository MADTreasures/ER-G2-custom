package ch.madtreasures.g2watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.wear.compose.material3.MaterialTheme
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.GlassesState
import ch.madtreasures.g2watch.glasses.Stage
import ch.madtreasures.g2watch.glasses.TransferStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings and the firmware pages on a rendered round watch face (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w227dp-h227dp-round-watch-xhdpi", application = android.app.Application::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val connected = GlassesState(
        stage = Stage.CONNECTED,
        title = "G2 A1B2",
        battery = 81,
        framesSent = 1234,
        transmitMs = 42,
        transfer = TransferStats.of(listOf(30, 21, 95, 42)),
    )

    private fun show(content: @Composable () -> Unit) = compose.setContent { MaterialTheme { content() } }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    private fun settings(
        state: GlassesState = connected,
        describe: (FirmwareTarget) -> String? = { null },
        onFirmware: (FirmwareTarget) -> Unit = {},
    ) = show { SettingsScreen(state, 1f, describe, {}, {}, {}, {}, {}, {}, onFirmware, {}) }

    @Test
    fun `shows how long frames take from the watch to the glasses`() {
        settings()
        scrollTo(hasText("1234 Bilder übertragen"))
        compose.onNodeWithText("42 ms").assertExists()
        compose.onNodeWithText("zuletzt · Ø 47 ms").assertExists()
        compose.onNodeWithText("Spanne 21 – 95 ms").assertExists()
        compose.onNodeWithTag(TRANSFER_CHART_TAG).assertExists()
    }

    @Test
    fun `without frames it says so`() {
        settings(GlassesState(stage = Stage.IDLE))
        scrollTo(hasText("Noch keine Bilder übertragen."))
        compose.onNodeWithText("Noch keine Bilder übertragen.").assertExists()
    }

    @Test
    fun `offers both firmwares, says what is set up, and passes the choice on`() {
        var chosen: FirmwareTarget? = null
        settings(
            describe = { if (it == FirmwareTarget.ORIGINAL) "Even Realities 2.3.0.24" else null },
            onFirmware = { chosen = it },
        )
        scrollTo(hasText("Custom-Firmware"))
        compose.onNodeWithText("Even Realities 2.3.0.24").assertExists()
        compose.onNodeWithText("nicht eingerichtet").assertExists()
        compose.onNodeWithText("Custom-Firmware").performClick()
        assertEquals(FirmwareTarget.CUSTOM, chosen)
    }

    @Test
    fun `offers no test run, only the two firmwares and the risks`() {
        settings()
        scrollTo(hasTestTag(RISKS_TAG))
        compose.onNodeWithText("Original-Firmware").assertExists()
        compose.onNodeWithText("Testlauf").assertDoesNotExist()
    }

    @Test
    fun `without a way to install there is nothing to hold`() {
        show { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, null, "Original 2.3.0.24", {}, { confirmed++ }) }
        compose.onNodeWithText("Neu: nicht eingerichtet").assertExists()
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun `the confirm page says what to expect before the hold`() {
        show { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", {}, {}) }
        scrollTo(hasText(firmwareWarning(FirmwareTarget.CUSTOM)))
        compose.onNodeWithText(firmwareWarning(FirmwareTarget.CUSTOM)).assertExists()
        scrollTo(hasText(FIRMWARE_STEPS))
        compose.onNodeWithText(FIRMWARE_STEPS).assertExists()
    }

    @Test
    fun `while something stands in the way the page explains instead of offering the hold`() {
        show { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", {}, { confirmed++ }, blocker = "Noch keine Brille gewählt.") }
        scrollTo(hasText("Noch keine Brille gewählt."))
        compose.onNodeWithText("Noch keine Brille gewählt.").assertExists()
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun `the warning is honest about the risks and the way back`() {
        val custom = firmwareWarning(FirmwareTarget.CUSTOM)
        assertTrue(custom.contains("Garantie"))
        assertTrue(custom.contains("meist harmlos"))
        assertTrue(!custom.contains(".md"))
        assertTrue(firmwareWarning(FirmwareTarget.ORIGINAL).contains("2.3.0.24"))
        assertTrue(RISKS.any { it.first == "Rückweg" })
    }

    @Test
    fun `the risks page lists every point`() {
        var back = 0
        show { RisksScreen { back++ } }
        compose.onNodeWithText("Risiken & Rückweg").assertExists()
        for ((title, _) in RISKS) {
            scrollTo(hasText(title))
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test
    fun `a finished transfer is titled by its target`() {
        show { FirmwareProgressScreen(FirmwareInstall.Done(FirmwareTarget.CUSTOM, "Beide Gläser melden Faceclaw/35.")) {} }
        compose.onNodeWithText("Custom-Firmware").assertExists()
        compose.onNodeWithText("Fertig").assertExists()
        compose.onNodeWithText("OK").assertExists()
    }

    private var confirmed = 0
    private var cancelled = 0

    private fun confirm() {
        show { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", { cancelled++ }, { confirmed++ }) }
        scrollTo(hasTestTag(HOLD_TO_CONFIRM_TAG))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `a press shorter than two seconds sends nothing`() {
        confirm()
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_CONFIRM_MS - 300L)
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(0, confirmed)
    }

    @Test
    fun `holding two seconds sends exactly once`() {
        confirm()
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_CONFIRM_MS - 100L)
        assertEquals(0, confirmed)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, confirmed)
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(1, confirmed)
    }

    @Test
    fun `the button at the edge cancels`() {
        confirm()
        compose.onNodeWithText("Abbrechen").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)
    }

    @Test
    fun `a running transfer shows its progress and offers no way out`() {
        show { FirmwareProgressScreen(FirmwareInstall.Running(FirmwareTarget.CUSTOM, "Übertrage Firmware …", 42)) {} }
        compose.onNodeWithText("42 %").assertExists()
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `without an installer it says so and can be closed`() {
        var closed = 0
        show { FirmwareProgressScreen(FirmwareInstall.Unavailable(FirmwareTarget.CUSTOM, "Kein Weg eingerichtet.")) { closed++ } }
        compose.onNodeWithText("Nicht eingerichtet").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(1, closed)
    }
}

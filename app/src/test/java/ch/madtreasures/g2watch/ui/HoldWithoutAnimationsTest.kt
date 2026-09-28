package ch.madtreasures.g2watch.ui

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.wear.compose.material3.MaterialTheme
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The confirm button with animations switched off, as in the developer options a sideloading
 * wearer has open: then every animation ends at once, and the hold must still take two seconds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w228dp-h228dp-round-watch-xhdpi", application = android.app.Application::class)
class HoldWithoutAnimationsTest {
    @get:Rule
    val compose = createComposeRule(object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    @Test
    fun `a short touch sends nothing even without animations`() {
        var confirmed = 0
        compose.setContent {
            MaterialTheme { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", {}, { confirmed++ }) }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(HOLD_TO_CONFIRM_TAG))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(0, confirmed)

        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_CONFIRM_MS - 100L)
        assertEquals(0, confirmed)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, confirmed)
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(1, confirmed)
    }
}

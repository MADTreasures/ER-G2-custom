package ch.madtreasures.g2watch.ui

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeSource
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.GlassesState
import ch.madtreasures.g2watch.glasses.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** The touchpad with real touch input on a rendered round watch face (Robolectric, native graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w228dp-h228dp-round-watch-xhdpi", application = android.app.Application::class)
class TouchpadScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var moved = Offset.Zero
    private var clicks = 0
    private var settings = 0

    private val connected = GlassesState(stage = Stage.CONNECTED, battery = 81)

    /** Low right on the face, clear of the time, the batteries and the gear: plain black. */
    private val finger = Offset(320f, 380f)

    private fun px(dp: Dp) = with(compose.density) { dp.toPx() }

    private val radius get() = px(TOUCH_RADIUS)

    /** The watch battery at 76 %, as the system announces it. */
    @Before
    fun watchBattery() {
        @Suppress("DEPRECATION")
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 76)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
    }

    private var glasses by mutableStateOf(connected)
    private var gestureMode by mutableStateOf(false)
    private val gestures = mutableListOf<GestureKind>()

    private fun show(state: GlassesState = connected) {
        glasses = state
        compose.setContent {
            MaterialTheme {
                TouchpadScreen(
                    glasses, 1f,
                    onMove = { dx, dy -> moved += Offset(dx, dy) },
                    onSpeed = {},
                    onClick = { clicks++ },
                    onOpenSettings = { settings++ },
                    timeSource = FixedTime,
                    gestureMode = gestureMode,
                    onGesture = { gestures += it },
                )
            }
        }
    }

    private fun showStill(state: GlassesState = connected) {
        compose.mainClock.autoAdvance = false
        show(state)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun gear() = compose.onNodeWithTag(GEAR_TAG).fetchSemanticsNode().boundsInRoot

    private fun screen() = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun channels(c: Int) = listOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))

    private fun pixel(p: Offset) = channels(screen().getPixel(p.x.roundToInt(), p.y.roundToInt()))

    /** Anything but the black face. */
    private fun shownAt(p: Offset) = pixel(p).max() > 40

    /** Blue like the glass and its rim: blue clearly ahead of red. */
    private fun blueAt(p: Offset) = pixel(p).let { (r, _, b) -> b > 60 && b > r + 25 }

    @Test
    fun `shows the time and the batteries of watch and glasses`() {
        show()
        compose.onNodeWithText("14:05").assertIsDisplayed()
        compose.onNodeWithText("76 %").assertIsDisplayed() // watch
        compose.onNodeWithText("81 %").assertIsDisplayed() // glasses
    }

    @Test
    fun `time and batteries sit in the upper half, the gear centred below them`() {
        show()
        val height = screen().height
        val time = compose.onNodeWithText("14:05").fetchSemanticsNode().boundsInRoot
        val battery = compose.onNodeWithText("81 %").fetchSemanticsNode().boundsInRoot
        assertTrue("time too low: ${time.center.y}", time.center.y < height * 0.35f)
        assertTrue("batteries too low: ${battery.center.y}", battery.center.y < height * 0.5f)
        val gear = gear()
        assertTrue("gear not below the batteries", gear.top >= battery.bottom)
        assertEquals(screen().width / 2f, gear.center.x, 2f)
    }

    @Test
    fun `glasses that are not connected right now show no level`() {
        show()
        for (stage in listOf(Stage.RECONNECTING, Stage.IDLE, Stage.FAILED)) {
            // What is left of a session that had a level and was charging.
            glasses = GlassesState(stage = stage, battery = 81, charging = true)
            compose.waitForIdle()
            compose.onNodeWithText("81 %", substring = true).assertDoesNotExist()
            compose.onNodeWithText("– %").assertIsDisplayed()
        }
    }

    @Test
    fun `connected glasses show their firmware and each temple's version`() {
        show(connected.copy(firmware = FirmwareRequirement.check("2.3.0.24", "2.3.0.24", "Faceclaw/${FirmwareRequirement.REQUIRED_REVISION}")))
        compose.onNodeWithText("Faceclaw/${FirmwareRequirement.REQUIRED_REVISION}").assertIsDisplayed()
        compose.onNodeWithText("Links 2.3.0.24 · Rechts 2.3.0.24").assertIsDisplayed()
        glasses = glasses.copy(stage = Stage.RECONNECTING)
        compose.waitForIdle()
        compose.onNodeWithTag(FIRMWARE_ROW_TAG).assertDoesNotExist()
    }

    @Test
    fun `charging glasses show their level with a bolt`() {
        show(GlassesState(stage = Stage.CHARGING, battery = 64, charging = true))
        compose.onNodeWithText("64 % ⚡").assertIsDisplayed()
    }

    @Test
    fun `shows no picture of the glasses`() {
        showStill()
        val shot = screen()
        // The glasses show 16 shades of green; nothing on the watch is green.
        for (y in 0 until shot.height step 2) for (x in 0 until shot.width step 2) {
            val (r, g, b) = channels(shot.getPixel(x, y))
            assertTrue("green pixel at ($x, $y): $r $g $b", g - maxOf(r, b) < 30)
        }
    }

    @Test
    fun `moving the finger moves the pointer`() {
        show()
        compose.onRoot().performTouchInput {
            down(finger)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue(moved.x > 0f)
        assertEquals(0f, moved.y, 0.01f)
        assertEquals(0, clicks)
    }

    @Test
    fun `a double tap clicks once and does not move`() {
        show()
        compose.onRoot().performTouchInput { doubleClick(finger) }
        compose.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(Offset.Zero, moved)
    }

    @Test
    fun `holding the gear opens the settings`() {
        showStill()
        compose.onRoot().performTouchInput { down(gear().center) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0, settings)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, settings)
    }

    @Test
    fun `holding anywhere else opens nothing, and the finger can still move the pointer`() {
        showStill()
        compose.onRoot().performTouchInput { down(finger) }
        compose.mainClock.advanceTimeBy(1_200)
        assertEquals(0, settings)
        compose.onRoot().performTouchInput {
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("resting stopped the pointer", moved.x > 0f)
    }

    @Test
    fun `taps on the gear neither click nor open anything`() {
        show()
        val at = gear().center
        compose.onRoot().performTouchInput { doubleClick(at) }
        compose.waitForIdle()
        assertEquals(0, clicks)
        assertEquals(0, settings)
    }

    @Test
    fun `holding the gear fills a ring round it clockwise`() {
        showStill()
        val gear = gear()
        compose.onRoot().performTouchInput { down(gear.center) }
        compose.mainClock.advanceTimeBy(450)
        // Half way: from the top over the right side; the left side is still empty.
        val ring = gear.width / 2 - px(1.5.dp)
        assertTrue("no ring on the right", pixel(gear.center + Offset(ring, 0f)).min() > 150)
        assertFalse("ring already on the left", pixel(gear.center - Offset(ring, 0f)).min() > 150)
        // No disc covers the gear meanwhile: its white body still shows.
        assertTrue("the gear is hidden", pixel(gear.center - Offset(0f, px(6.5.dp))).min() > 200)
    }

    @Test
    fun `a glass disc with a blue rim sits under the finger`() {
        showStill()
        assertFalse(shownAt(finger))
        compose.onRoot().performTouchInput { down(finger) }
        compose.mainClock.advanceTimeByFrame()
        // Opaque blue glass in the middle and half-way out, over what was black.
        assertTrue("no glass under the finger", blueAt(finger))
        assertTrue("the glass is not solid", blueAt(finger - Offset(radius / 2, 0f)))
        // A light blue rim at the edge.
        val (r, _, b) = pixel(finger - Offset(radius - px(1.dp), 0f))
        assertTrue("no light rim: $r $b", b > 200 && b > r)
    }

    @Test
    fun `the disc follows the finger and goes away when it lifts`() {
        showStill()
        compose.onRoot().performTouchInput { down(finger) }
        compose.onRoot().performTouchInput {
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
        }
        compose.mainClock.advanceTimeByFrame()
        val there = finger - Offset(120f, 0f)
        assertTrue("the disc did not follow", blueAt(there))
        assertFalse("the disc stayed behind", shownAt(finger))

        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertFalse("the disc stayed after lifting", shownAt(there))
    }

    @Test
    fun `a lone tap leaves nothing on the face`() {
        showStill()
        compose.onRoot().performTouchInput { down(finger) }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(blueAt(finger))
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertFalse("the disc stayed after the finger lifted", shownAt(finger))
        assertEquals(0, clicks)
    }

    @Test
    fun `the second touch of a double tap lights up the rim, and lifting it clicks`() {
        showStill()
        val rim = finger - Offset(radius - px(1.dp), 0f)
        val halo = finger - Offset(radius + px(3.dp), 0f)
        // First touch: the disc as it always looks.
        compose.onRoot().performTouchInput { down(finger) }
        compose.mainClock.advanceTimeByFrame()
        val restingRim = pixel(rim)
        val restingHalo = pixel(halo)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()

        // Second touch soon after: lit while the finger is still down, before anything clicks.
        compose.onRoot().performTouchInput {
            advanceEventTime(150)
            down(finger)
        }
        compose.mainClock.advanceTimeBy(100)
        val litRim = pixel(rim)
        val litHalo = pixel(halo)
        assertTrue("rim not lighter: $restingRim → $litRim", litRim[0] > restingRim[0] + 20)
        assertTrue("halo not brighter: $restingHalo → $litHalo", litHalo[2] > restingHalo[2] + 20)
        assertEquals(0, clicks)

        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
        assertFalse("the disc stayed after the click", shownAt(finger))
    }

    @Test
    fun `a second touch that moves the pointer does not click`() {
        showStill()
        compose.onRoot().performTouchInput {
            down(finger)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput {
            advanceEventTime(150)
            down(finger)
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(moved.x < 0f)
        assertEquals(0, clicks)
    }

    private object FixedTime : TimeSource {
        @Composable
        override fun currentTime(): String = "14:05"
    }

    // --- Gesture mode (apps with input "gestures") -----------------------------------------------

    private fun showGestures() {
        gestureMode = true
        show()
    }

    @Test
    fun `in gesture mode swipes are gestures, not pointer moves`() {
        showGestures()
        compose.onNodeWithTag(GESTURES_TAG).assertIsDisplayed()
        fun swipe(dx: Float, dy: Float) = compose.onRoot().performTouchInput {
            down(finger)
            repeat(4) { moveBy(Offset(dx / 4, dy / 4)) }
            up()
        }
        swipe(120f, 0f)
        swipe(-120f, 0f)
        swipe(0f, -120f)
        swipe(0f, 120f)
        compose.waitForIdle()
        assertEquals(
            listOf(GestureKind.SWIPE_RIGHT, GestureKind.SWIPE_LEFT, GestureKind.SCROLL_DOWN, GestureKind.SCROLL_UP),
            gestures,
        )
        assertEquals(Offset.Zero, moved)
    }

    @Test
    fun `in gesture mode a tap waits for a second one, two taps are a double click`() {
        showStill()
        gestureMode = true
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { click(finger) }
        compose.mainClock.advanceTimeBy(200)
        assertTrue(gestures.isEmpty())
        compose.mainClock.advanceTimeBy(400)
        assertEquals(listOf(GestureKind.CLICK), gestures)
        gestures.clear()
        compose.onRoot().performTouchInput { doubleClick(finger) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(listOf(GestureKind.DOUBLE_CLICK), gestures)
        assertEquals(0, clicks)
    }

    @Test
    fun `in gesture mode holding is a long press with its release, and the gear still opens the settings`() {
        showStill()
        gestureMode = true
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { down(finger) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(GestureKind.LONG_PRESS), gestures)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(GestureKind.LONG_PRESS, GestureKind.LONG_PRESS_RELEASE), gestures)
        compose.onRoot().performTouchInput { down(gear().center) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, settings)
    }
}

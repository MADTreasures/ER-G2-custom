package ch.madtreasures.g2watch.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import ch.madtreasures.g2watch.G2WatchApp
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.GlassesState
import ch.madtreasures.g2watch.glasses.Stage
import ch.madtreasures.g2watch.glasses.TransferStats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the watch screens on a round 454 px face (Pixel Watch size class), for checking
 * the layout without a watch:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*WatchSnapshotTest*' -PsnapshotDir=$PWD/docs/bilder
 *
 * Skipped without -PsnapshotDir. Everything outside the round face is greyed out. The clock
 * shows 14:05 and the watch battery 76 %, so the pictures stay the same from run to run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w227dp-h227dp-round-watch-xhdpi", application = android.app.Application::class)
class WatchSnapshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("snapshotDir")

    @Before
    fun setUp() {
        assumeTrue("no -PsnapshotDir", !dir.isNullOrBlank())
        @Suppress("DEPRECATION")
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 76)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
    }

    private fun snapshot(name: String, content: @Composable () -> Unit) {
        show(content)
        compose.waitForIdle()
        save(name)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText(timeSource = FixedTime) }) { content() }
            }
        }
    }

    private fun save(name: String) {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { roundFace(shot).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The touchpad after [gesture] ran through the real gesture handler and [afterMs] passed. */
    private fun touchSnapshot(name: String, afterMs: Long, gesture: TouchInjectionScope.(gear: Offset) -> Unit) {
        compose.mainClock.autoAdvance = false
        show { Touchpad() }
        compose.mainClock.advanceTimeByFrame()
        val gear = compose.onNodeWithTag(GEAR_TAG).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { gesture(gear) }
        compose.mainClock.advanceTimeBy(afterMs)
        save(name)
    }

    /** A settings page scrolled so that [text] is in view. */
    private fun settingsSnapshot(name: String, text: String?) {
        show { Settings() }
        compose.waitForIdle()
        if (text != null) compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
        save(name)
    }

    private fun roundFace(shot: Bitmap): Bitmap {
        val out = shot.copy(Bitmap.Config.ARGB_8888, true)
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Path.Direction.CW)
            addCircle(out.width / 2f, out.height / 2f, minOf(out.width, out.height) / 2f, Path.Direction.CW)
        }
        Canvas(out).drawPath(outside, Paint().apply { color = Color.rgb(60, 60, 60) })
        return out
    }

    private val connected = GlassesState(
        stage = Stage.CONNECTED,
        title = "G2 A1B2",
        battery = 81,
        firmware = FirmwareRequirement.check("2.3.0.24", "2.3.0.24", "Faceclaw/${FirmwareRequirement.REQUIRED_REVISION}"),
        framesSent = 1234,
        transmitMs = 42,
        transfer = TransferStats.of(
            listOf(
                31, 28, 35, 42, 30, 26, 29, 33, 61, 38, 27, 25, 30, 34, 29,
                95, 40, 31, 28, 24, 21, 30, 36, 33, 29, 27, 31, 45, 38, 42,
            ),
        ),
        lastInput = "Tipp (rechter Bügel)",
    )

    /** How the firmware buttons read once the firmware project has connected its installer. */
    private fun example(target: FirmwareTarget) = when (target) {
        FirmwareTarget.ORIGINAL -> "Even Realities 2.3.0.24"
        FirmwareTarget.CUSTOM -> "Faceclaw/35 · Basis 2.3.0.24"
    }

    @Composable
    private fun Settings() = SettingsScreen(connected, 1.2f, ::example, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Composable
    private fun Touchpad(glasses: GlassesState = connected) =
        TouchpadScreen(glasses, 1f, { _, _ -> }, {}, {}, {}, timeSource = FixedTime)

    @Test
    fun touchpadConnected() = snapshot("uhr-touchpad") { Touchpad() }

    @Test
    fun touchpadReconnecting() = snapshot("uhr-touchpad-brille-weg") {
        Touchpad(connected.copy(stage = Stage.RECONNECTING))
    }

    @Test
    fun touchMoving() = touchSnapshot("uhr-finger-bewegen", 16) {
        // Beside the gear, so the whole disc shows.
        down(Offset(395f, 341f))
        repeat(3) { moveBy(Offset(-20f, -5f)) }
    }

    @Test
    fun touchClick() = touchSnapshot("uhr-finger-klick", 90) {
        // The second touch of a double tap, still down: lifting it will click.
        val at = Offset(119f, 326f)
        down(at)
        up()
        advanceEventTime(150)
        down(at)
    }

    @Test
    fun gearHolding() = touchSnapshot("uhr-zahnrad-halten", 560) { gear ->
        down(gear)
    }

    @Test
    fun settingsTop() = settingsSnapshot("uhr-einstellungen", null)

    @Test
    fun settingsTransfer() = settingsSnapshot("uhr-einstellungen-log", FRAMES_TEXT)

    @Test
    fun settingsFirmware() = settingsSnapshot("uhr-einstellungen-firmware", "Custom-Firmware")

    @Test
    fun settingsPointer() = settingsSnapshot("uhr-einstellungen-zeiger", "Protokoll")

    @Test
    fun firmwareConfirm() = snapshot("uhr-firmware-bestaetigen") {
        FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", {}, {})
    }

    @Test
    fun firmwareHolding() {
        show { FirmwareConfirmScreen(FirmwareTarget.CUSTOM, "Faceclaw/35 · Basis 2.3.0.24", "Original 2.3.0.24", {}, {}) }
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(HOLD_TO_CONFIRM_TAG))
        compose.waitForIdle()
        // Stop the clock only now: half-way through holding, the button is half full.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_CONFIRM_MS / 2L)
        save("uhr-firmware-halten")
    }

    @Test
    fun firmwareRunning() = snapshot("uhr-firmware-laeuft") {
        FirmwareProgressScreen(FirmwareInstall.Running(FirmwareTarget.CUSTOM, "Linkes Glas: Teil 6 von 6 (Hauptprogramm) …", 42)) {}
    }

    @Test
    fun firmwareDone() = snapshot("uhr-firmware-fertig") {
        FirmwareProgressScreen(FirmwareInstall.Done(FirmwareTarget.CUSTOM, "Die Brille meldet Faceclaw/35 (L 2.3.0.24, R 2.3.0.24).")) {}
    }

    @Test
    fun firmwareFailed() = snapshot("uhr-firmware-fehlgeschlagen") {
        FirmwareProgressScreen(
            FirmwareInstall.Failed(
                FirmwareTarget.CUSTOM,
                "Akku der Brille zu schwach oder nicht lesbar (R 80 %, L 22 %). Beide Gläser auf mindestens 50 % laden " +
                    "und erneut versuchen. Nichts wurde an der Brille verändert.",
            ),
        ) {}
    }

    @Test
    fun firmwareNeedsTestRun() {
        show {
            FirmwareConfirmScreen(
                FirmwareTarget.CUSTOM,
                "Faceclaw/35 · Basis 2.3.0.24",
                "Original 2.3.0.24",
                {},
                {},
                blocker = "Zuerst einmal den Testlauf machen (Einstellungen → Testlauf). Er prüft alles und schreibt nichts auf die Brille.",
            )
        }
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Zuerst einmal den Testlauf machen", substring = true))
        compose.waitForIdle()
        save("uhr-firmware-testlauf-noetig")
    }

    @Test
    fun settingsTestRun() = settingsSnapshot("uhr-einstellungen-testlauf", "Testlauf")

    @Test
    fun testRunRunning() = snapshot("uhr-testlauf-laeuft") {
        FirmwareProgressScreen(FirmwareInstall.Running(FirmwareTarget.CUSTOM, "Linkes Glas: prüfe den Update-Kanal …", null, testRun = true)) {}
    }

    @Test
    fun testRunPassed() = snapshot("uhr-testlauf-bestanden") {
        FirmwareProgressScreen(
            FirmwareInstall.Done(
                FirmwareTarget.CUSTOM,
                "Image Faceclaw/35 · Basis 2.3.0.24 geprüft (SHA-256 d7971b68…). Brille: Original 2.3.0.24. " +
                    "Akku R 80 %, L 76 %. MTU L 247, R 247. Es wurde nichts auf die Brille geschrieben.",
                testRun = true,
            ),
        ) {}
    }

    @Test
    fun risks() = snapshot("uhr-risiken") { RisksScreen {} }

    @Test
    fun incompatible() = snapshot("uhr-firmware-passt-nicht") {
        val verdict = FirmwareRequirement.check("2.3.0.24", "2.3.0.24", "")
        StatusScreen(
            GlassesState(stage = Stage.INCOMPATIBLE, title = "G2 A1B2", detail = verdict.message.orEmpty(), firmware = verdict),
            {}, {}, {}, {}, {},
        )
    }

    @Test
    fun checking() = snapshot("uhr-pruefung") {
        StatusScreen(
            GlassesState(stage = Stage.CHECKING, title = "G2 A1B2", detail = "Lese die Firmware-Version (rechter Bügel) …"),
            {}, {}, {}, {}, {},
        )
    }

    @Test
    fun devices() = snapshot("uhr-geraete") {
        DevicesScreen(
            pairs = emptyList(),
            scanning = false,
            bluetoothOn = true,
            scanError = null,
            lastPair = G2WatchApp.LastPair("G2 A1B2", "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            onScan = {}, onEnableBluetooth = {}, onConnectPair = {}, onConnectLast = {}, onForgetLast = {}, onLog = {},
        )
    }

    @Test
    fun log() = snapshot("uhr-protokoll") {
        LogScreen(
            listOf(
                "14:05:01 Verbinde mit G2 A1B2 (R AA:BB:CC:DD:EE:01, L AA:BB:CC:DD:EE:02)",
                "14:05:03 security auth: right lens, bond state BONDED",
                "14:05:04 device-info: L=2.3.0.24 R=2.3.0.24 ext=[]",
                "14:05:04 Firmware: Original 2.3.0.24 – Die Brille hat die Original-Firmware " +
                    "(L=2.3.0.24 R=2.3.0.24). Diese App braucht Faceclaw-Firmware Revision 35.",
                "14:06:10 Firmware: Aufspielen Custom-Firmware (Faceclaw/35 · Basis 2.3.0.24) gestartet",
                "14:06:14 Firmware: image ready: Faceclaw/35 · Basis 2.3.0.24, 4609823 bytes, sha256 d7971b68…",
                "14:06:41 Firmware: glasses battery: R 80 %, L 76 %",
            ),
            {},
        )
    }

    @Test
    fun permission() = snapshot("uhr-berechtigung") {
        PermissionScreen({}, {})
    }

    private companion object {
        const val FRAMES_TEXT = "1234 Bilder übertragen"
    }

    private object FixedTime : TimeSource {
        @Composable
        override fun currentTime(): String = "14:05"
    }
}

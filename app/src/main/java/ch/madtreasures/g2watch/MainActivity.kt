package ch.madtreasures.g2watch

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.Stage
import ch.madtreasures.g2watch.ui.DevicesScreen
import ch.madtreasures.g2watch.ui.FirmwareConfirmScreen
import ch.madtreasures.g2watch.ui.FirmwareProgressScreen
import ch.madtreasures.g2watch.ui.LogScreen
import ch.madtreasures.g2watch.ui.PermissionScreen
import ch.madtreasures.g2watch.ui.RisksScreen
import ch.madtreasures.g2watch.ui.SettingsScreen
import ch.madtreasures.g2watch.ui.StatusScreen
import ch.madtreasures.g2watch.ui.TouchpadScreen

class MainActivity : ComponentActivity() {

    private val app get() = application as G2WatchApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) {
                    Root()
                }
            }
        }
    }

    private enum class Screen { DEVICES, STATUS, TOUCHPAD, SETTINGS, LOG, FIRMWARE_CONFIRM, FIRMWARE_PROGRESS, RISKS }

    @Composable
    private fun Root() {
        val glasses = app.glasses
        val desktop = app.desktop
        val scanner = app.scanner
        val state by glasses.state.collectAsStateWithLifecycle()
        val speed by desktop.speed.collectAsStateWithLifecycle()
        var permissionTick by remember { mutableIntStateOf(0) }
        val missing = remember(permissionTick) { missingPermissions() }
        var screen by rememberSaveable { mutableStateOf(Screen.DEVICES) }
        var logReturn by rememberSaveable { mutableStateOf(Screen.DEVICES) }
        var settingsReturn by rememberSaveable { mutableStateOf(Screen.TOUCHPAD) }
        var risksReturn by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
        var firmwareTarget by rememberSaveable { mutableStateOf(FirmwareTarget.CUSTOM) }
        val firmware = app.firmware
        val install by firmware.progress.collectAsStateWithLifecycle()

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionTick++ }
        val enableBtLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { permissionTick++ }

        // Keep the watch awake while it talks to the glasses: the touchpad stops working once
        // Wear OS dims into ambient mode. Not while the glasses charge or are out of reach
        // (that can last hours), and not without glasses: then the screen times out as usual.
        // A firmware transfer must not be cut short by the watch dozing off either.
        val keepAwake = install is FirmwareInstall.Running || when (state.stage) {
            Stage.CHECKING, Stage.CONNECTING, Stage.CONNECTED, Stage.DISCONNECTING -> true
            else -> false
        }
        LaunchedEffect(keepAwake) {
            if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Follow the connection: to the touchpad once the glasses show the desktop, to the status
        // page while checking and when something needs explaining.
        LaunchedEffect(state.stage) {
            when (state.stage) {
                Stage.CONNECTED -> if (screen == Screen.STATUS || screen == Screen.DEVICES) screen = Screen.TOUCHPAD
                Stage.CHECKING, Stage.CONNECTING -> if (screen == Screen.DEVICES) screen = Screen.STATUS
                Stage.INCOMPATIBLE, Stage.FAILED ->
                    if (screen == Screen.TOUCHPAD || screen == Screen.SETTINGS || screen == Screen.DEVICES) screen = Screen.STATUS
                else -> Unit
            }
        }

        // A transfer, or its result, always has the screen: also after the activity was recreated
        // (e.g. closed from the recents list while the foreground service kept the transfer going).
        LaunchedEffect(install) {
            if (install !is FirmwareInstall.Idle && screen != Screen.FIRMWARE_PROGRESS) screen = Screen.FIRMWARE_PROGRESS
        }

        fun connect(title: String, right: String, left: String?) {
            // The transfer owns the glasses until it is over.
            if (firmware.progress.value is FirmwareInstall.Running) {
                screen = Screen.FIRMWARE_PROGRESS
                return
            }
            if (missingPermissions().isNotEmpty()) {
                // Revoked since the list was shown: ask again first.
                permissionTick++
                screen = Screen.DEVICES
                return
            }
            scanner.stop()
            app.saveLastPair(title, right, left)
            glasses.connect(title, right, left)
            screen = Screen.STATUS
        }

        when (screen) {
            Screen.DEVICES -> {
                if (missing.isNotEmpty()) {
                    PermissionScreen(
                        onRequest = { permissionLauncher.launch((missing + optionalPermissions()).toTypedArray()) },
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                            )
                        },
                    )
                    return
                }
                val pairs by scanner.pairs.collectAsStateWithLifecycle()
                val scanning by scanner.scanning.collectAsStateWithLifecycle()
                val scanError by scanner.error.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    // No scan next to a transfer: it would share the radio with the firmware link.
                    if (firmware.progress.value is FirmwareInstall.Running) return@LaunchedEffect
                    if (scanner.bluetoothEnabled()) scanner.start() else scanner.refreshKnownDevices()
                }
                DevicesScreen(
                    pairs = pairs,
                    scanning = scanning,
                    bluetoothOn = scanner.bluetoothEnabled(),
                    scanError = scanError,
                    lastPair = app.lastPair(),
                    onScan = { scanner.start() },
                    onEnableBluetooth = {
                        try {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    },
                    onConnectPair = { pair ->
                        val right = pair.right ?: return@DevicesScreen
                        connect(pair.title, right.address, pair.left?.address)
                    },
                    onConnectLast = { last -> connect(last.title, last.right, last.left) },
                    onForgetLast = { app.forgetLastPair(); permissionTick++ },
                    onLog = { logReturn = Screen.DEVICES; screen = Screen.LOG },
                )
            }

            Screen.STATUS -> StatusScreen(
                state = state,
                onCancel = { glasses.disconnect(); screen = Screen.DEVICES },
                onRetry = { app.lastPair()?.let { connect(it.title, it.right, it.left) } },
                onOpenTouchpad = { screen = Screen.TOUCHPAD },
                onSettings = { settingsReturn = Screen.STATUS; screen = Screen.SETTINGS },
                onLog = { logReturn = Screen.STATUS; screen = Screen.LOG },
            )

            Screen.TOUCHPAD -> TouchpadScreen(
                glasses = state,
                speed = speed,
                onMove = { dx, dy -> desktop.moveBy(dx, dy) },
                onSpeed = { desktop.setSpeed(it) },
                onClick = { desktop.click() },
                onOpenSettings = { settingsReturn = Screen.TOUCHPAD; screen = Screen.SETTINGS },
            )

            Screen.SETTINGS -> {
                BackHandler { screen = settingsReturn }
                SettingsScreen(
                    state = state,
                    speed = speed,
                    describeFirmware = firmware::describe,
                    onBack = { screen = settingsReturn },
                    onCloseWindow = { desktop.back(); screen = Screen.TOUCHPAD },
                    onCenter = { desktop.centerPointer(); screen = Screen.TOUCHPAD },
                    onSpeed = { desktop.setSpeed(it) },
                    onConnect = { screen = Screen.DEVICES },
                    onDisconnect = { glasses.disconnect() },
                    onFirmware = { firmwareTarget = it; screen = Screen.FIRMWARE_CONFIRM },
                    onTestRun = {
                        // Writes nothing, so a tap is enough; it still needs the glasses for a while.
                        firmwareTarget = FirmwareTarget.CUSTOM
                        glasses.note("Firmware: Testlauf gestartet")
                        firmware.testRun(FirmwareTarget.CUSTOM)
                        screen = Screen.FIRMWARE_PROGRESS
                    },
                    onLog = { logReturn = Screen.SETTINGS; screen = Screen.LOG },
                    backLabel = if (settingsReturn == Screen.TOUCHPAD) "Touchpad" else "Zurück",
                    onRisks = { risksReturn = Screen.SETTINGS; screen = Screen.RISKS },
                )
            }

            Screen.FIRMWARE_CONFIRM -> {
                BackHandler { screen = Screen.SETTINGS }
                FirmwareConfirmScreen(
                    target = firmwareTarget,
                    description = firmware.describe(firmwareTarget),
                    onGlasses = state.firmware?.summary,
                    onCancel = { screen = Screen.SETTINGS },
                    blocker = state.firmware
                        ?.takeIf { FirmwareRequirement.isNewerThanBase(it.leftVersion) || FirmwareRequirement.isNewerThanBase(it.rightVersion) }
                        ?.let { "Die Brille meldet eine neuere Firmware als ${FirmwareRequirement.BASE_STOCK_VERSION}. Darauf spielt die App nichts auf." }
                        ?: firmware.blocker(firmwareTarget),
                    onRisks = { risksReturn = Screen.FIRMWARE_CONFIRM; screen = Screen.RISKS },
                    onConfirm = {
                        glasses.note("Firmware: ${firmwareTarget.label} bestätigt")
                        firmware.install(firmwareTarget)
                        screen = Screen.FIRMWARE_PROGRESS
                    },
                )
            }

            Screen.FIRMWARE_PROGRESS -> {
                fun close() {
                    val wasTestRun = when (val result = install) {
                        is FirmwareInstall.Done -> result.testRun
                        is FirmwareInstall.Failed -> result.testRun
                        else -> false
                    }
                    firmware.dismiss()
                    // After a transfer the glasses were let go: check them again, which also shows
                    // the new firmware (and starts the desktop if it is the custom one). After a
                    // test run the next step is in the settings, and the job connects on its own.
                    val last = app.lastPair()
                    if (!wasTestRun && last != null && state.stage == Stage.IDLE) connect(last.title, last.right, last.left)
                    else screen = Screen.SETTINGS
                }
                // No way back while firmware is on its way.
                BackHandler { if (install !is FirmwareInstall.Running) close() }
                FirmwareProgressScreen(install = install, onClose = { close() })
            }

            Screen.RISKS -> {
                BackHandler { screen = risksReturn }
                RisksScreen(onBack = { screen = risksReturn })
            }

            Screen.LOG -> {
                val lines by glasses.log.collectAsStateWithLifecycle()
                BackHandler { screen = logReturn }
                LogScreen(lines = lines, onBack = { screen = logReturn })
            }
        }
    }

    private fun missingPermissions(): List<String> =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    /** Asked for together with Bluetooth, but the app works without it (no ongoing notification). */
    private fun optionalPermissions(): List<String> =
        listOf(Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
}

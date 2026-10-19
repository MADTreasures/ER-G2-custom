package ch.madtreasures.g2watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.RemoteInput
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.host.TextPrompt
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.Stage
import ch.madtreasures.g2watch.input.KeyLayout
import ch.madtreasures.g2watch.input.MouseBackGuard
import ch.madtreasures.g2watch.input.MouseCapture
import ch.madtreasures.g2watch.ui.AppsScreen
import ch.madtreasures.g2watch.ui.DevicesScreen
import ch.madtreasures.g2watch.ui.FirmwareConfirmScreen
import ch.madtreasures.g2watch.ui.FirmwareProgressScreen
import ch.madtreasures.g2watch.ui.InputDevicesScreen
import ch.madtreasures.g2watch.ui.LogScreen
import ch.madtreasures.g2watch.ui.PermissionScreen
import ch.madtreasures.g2watch.ui.RisksScreen
import ch.madtreasures.g2watch.ui.SettingsScreen
import ch.madtreasures.g2watch.ui.StatusScreen
import ch.madtreasures.g2watch.ui.TouchpadScreen
import ch.madtreasures.g2watch.ui.inputHint
import ch.madtreasures.g2watch.ui.inputSummary

class MainActivity : ComponentActivity() {

    /** Keyboard and voice input for [prompt], with its suggestions as ready answers. */
    private fun textInputIntent(prompt: TextPrompt): Intent {
        val input = RemoteInput.Builder(TEXT_KEY)
            .setLabel(prompt.prompt)
            .setChoices(prompt.suggestions.takeIf { it.isNotEmpty() }?.toTypedArray<CharSequence>())
            .setAllowFreeFormInput(true)
            .build()
        return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
            RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
            RemoteInputIntentHelper.putTitleExtra(it, prompt.prompt)
        }
    }

    private val app get() = application as G2WatchApp

    /**
     * A Bluetooth keyboard and mouse work the glasses only while the touchpad shows connected glasses;
     * on every other screen they work the watch as usual (03 §5.3). Main thread.
     */
    private var hardwareActive = false

    /** Pointer capture for the mouse on the touchpad. */
    private val capture by lazy { MouseCapture { mouse(it, captured = true) } }

    /** Swallows the one system back Android makes of the mouse's back button ([MouseBackGuard]). */
    private val mouseBack: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = backGuard.used()
    }

    private val backGuard: MouseBackGuard by lazy { MouseBackGuard(MainScheduler()) { mouseBack.isEnabled = it } }

    /** A mouse event for the glasses; true if it was taken. */
    private fun mouse(ev: MotionEvent, captured: Boolean): Boolean {
        val taken = if (captured) app.hardware.captured(ev) else app.hardware.motion(ev)
        if (taken) backGuard.buttons(ev.buttonState)
        return taken
    }

    // androidx.core's ComponentActivity marks its dispatchKeyEvent as library-internal, so lint flags every
    // override; overriding the Activity's key dispatch is still the way to see keys before Compose does.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (hardwareActive && app.hardware.key(event)) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        (hardwareActive && mouse(ev, captured = false)) || super.dispatchGenericMotionEvent(ev)

    /** A mouse's button presses and drags arrive as touches; on the touchpad they are the mouse's, not a finger's. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        (hardwareActive && mouse(ev, captured = false)) || super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Pointer capture ends with the focus (the watch's text input, a system dialog) and comes back with it.
        capture.windowFocusChanged(hasFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Added first, so every screen's own back handler comes before it; it is off unless armed.
        onBackPressedDispatcher.addCallback(this, mouseBack)
        setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) {
                    Root()
                }
            }
        }
    }

    private enum class Screen { DEVICES, STATUS, TOUCHPAD, SETTINGS, LOG, FIRMWARE_CONFIRM, FIRMWARE_PROGRESS, RISKS, APPS, INPUT }

    @Composable
    private fun Root() {
        val glasses = app.glasses
        val desktop = app.desktop
        val scanner = app.scanner
        val state by glasses.state.collectAsStateWithLifecycle()
        val speed by desktop.speed.collectAsStateWithLifecycle()
        val inputMode by app.apps.inputMode.collectAsStateWithLifecycle()
        var permissionTick by remember { mutableIntStateOf(0) }
        val missing = remember(permissionTick) { missingPermissions() }
        var screen by rememberSaveable { mutableStateOf(Screen.DEVICES) }
        var logReturn by rememberSaveable { mutableStateOf(Screen.DEVICES) }
        var settingsReturn by rememberSaveable { mutableStateOf(Screen.TOUCHPAD) }
        var risksReturn by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
        var firmwareTarget by rememberSaveable { mutableStateOf(FirmwareTarget.CUSTOM) }
        val firmware = app.firmware
        val packages = app.packages
        val installedApps by packages.installed.collectAsStateWithLifecycle()
        val waitingApps by packages.waiting.collectAsStateWithLifecycle()
        var protocolFileOn by remember { mutableStateOf(app.protocolFile.enabled) }
        val install by firmware.progress.collectAsStateWithLifecycle()
        val inputDevices by app.inputDevices.devices.collectAsStateWithLifecycle()
        val keyLayout by app.keyLayout.collectAsStateWithLifecycle()
        val mouse = inputDevices.any { it.mouse }
        val hardwareOn = screen == Screen.TOUCHPAD && state.stage == Stage.CONNECTED
        SideEffect { hardwareActive = hardwareOn }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionTick++ }
        val enableBtLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { permissionTick++ }

        // An app on the glasses asks for text: keyboard and voice input of Wear OS, over any screen.
        val textPrompt by app.textPrompts.open.collectAsStateWithLifecycle()
        var askedPrompt by rememberSaveable { mutableIntStateOf(0) }
        val textLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(TEXT_KEY)?.toString() }
            app.textPrompts.answer(askedPrompt, text)
        }
        LaunchedEffect(textPrompt?.id) {
            val prompt = textPrompt ?: return@LaunchedEffect
            if (askedPrompt == prompt.id) return@LaunchedEffect
            askedPrompt = prompt.id
            try {
                textLauncher.launch(textInputIntent(prompt))
            } catch (e: ActivityNotFoundException) {
                app.glasses.note("Texteingabe fehlt auf dieser Uhr: ${e.message}")
                app.textPrompts.answer(prompt.id, null)
            }
        }

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

            Screen.TOUCHPAD -> {
                // The mouse is captured by the view that has the focus: the touchpad's.
                val view = LocalView.current
                DisposableEffect(view) { onDispose { capture.clear(view) } }
                LaunchedEffect(view, hardwareOn && mouse) { capture.set(view, hardwareOn && mouse) }
                TouchpadScreen(
                    glasses = state,
                    speed = speed,
                    onMove = { dx, dy -> desktop.moveBy(dx, dy) },
                    onSpeed = { desktop.setSpeed(it) },
                    onClick = { desktop.click() },
                    onOpenSettings = { settingsReturn = Screen.TOUCHPAD; screen = Screen.SETTINGS },
                    gestureMode = inputMode == InputMode.GESTURES,
                    onGesture = { app.input.onWatchGesture(it) },
                    inputHint = inputHint(inputDevices.any { it.keyboard }, mouse),
                )
            }

            Screen.SETTINGS -> {
                BackHandler { screen = settingsReturn }
                // Files laid on the watch since the last look show up as "neu" under "Apps installieren".
                LaunchedEffect(Unit) { packages.rescan() }
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
                    onLog = { logReturn = Screen.SETTINGS; screen = Screen.LOG },
                    backLabel = if (settingsReturn == Screen.TOUCHPAD) "Touchpad" else "Zurück",
                    onRisks = { risksReturn = Screen.SETTINGS; screen = Screen.RISKS },
                    onApps = { screen = Screen.APPS },
                    appsSummary = appsSummary(installedApps.size, waitingApps.size),
                    protocolFile = protocolFileOn,
                    onProtocolFile = { on -> app.setProtocolFile(on); protocolFileOn = on },
                    inputSummary = inputSummary(inputDevices),
                    onInput = { screen = Screen.INPUT },
                )
            }

            Screen.INPUT -> {
                val pairing = app.pairing
                val pairingState by pairing.state.collectAsStateWithLifecycle()
                DisposableEffect(Unit) { onDispose { pairing.stop() } }
                BackHandler { screen = Screen.SETTINGS }
                InputDevicesScreen(
                    devices = inputDevices,
                    layout = keyLayout,
                    autoLayout = KeyLayout.forLocale(resources.configuration.locales[0]),
                    pairing = pairingState,
                    onLayout = { app.setKeyLayout(it) },
                    // No search next to a firmware transfer: it would share the radio with it.
                    onScan = { if (firmware.progress.value !is FirmwareInstall.Running) pairing.start() },
                    onPair = { pairing.pair(it.address) },
                    onBluetoothSettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                            app.glasses.note("Bluetooth-Einstellungen fehlen auf dieser Uhr")
                        }
                    },
                    onBack = { screen = Screen.SETTINGS },
                )
            }

            Screen.APPS -> {
                val message by packages.message.collectAsStateWithLifecycle()
                val busy by packages.busy.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { packages.rescan() }
                BackHandler { packages.clearMessage(); screen = Screen.SETTINGS }
                AppsScreen(
                    waiting = waitingApps,
                    installed = installedApps,
                    builtIn = app.builtInAppManifests.map { it.name },
                    message = message,
                    busy = busy,
                    inboxPath = packages.inboxPath,
                    onInstall = { packages.install(it.file) },
                    onRemove = { packages.remove(it) },
                    onBack = { packages.clearMessage(); screen = Screen.SETTINGS },
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
                    firmware.dismiss()
                    // After a transfer the glasses were let go: check them again, which also shows
                    // the new firmware (and starts the desktop if it is the custom one).
                    val last = app.lastPair()
                    if (last != null && state.stage == Stage.IDLE) connect(last.title, last.right, last.left)
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
                LogScreen(lines = lines, onBack = { screen = logReturn }, fileEnabled = protocolFileOn)
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

    private companion object {
        /** Result key of the text input for apps. */
        const val TEXT_KEY = "g2watch.text"

        /** "2 installiert · 1 neu" under the settings button "Apps installieren". */
        fun appsSummary(installed: Int, waiting: Int): String =
            listOfNotNull("$installed installiert", "$waiting neu".takeIf { waiting > 0 }).joinToString(" · ")
    }
}

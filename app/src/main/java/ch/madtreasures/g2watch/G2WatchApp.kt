package ch.madtreasures.g2watch

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.builtInApps
import ch.madtreasures.g2watch.apps.host.AndroidHostPorts
import ch.madtreasures.g2watch.apps.host.AppHost
import ch.madtreasures.g2watch.apps.host.AppThread
import ch.madtreasures.g2watch.apps.host.GlassesStatus
import ch.madtreasures.g2watch.apps.host.HardwareSink
import ch.madtreasures.g2watch.apps.host.InputRouter
import ch.madtreasures.g2watch.apps.host.TextPrompts
import ch.madtreasures.g2watch.apps.launcher.Launcher
import ch.madtreasures.g2watch.apps.packages.AppPackages
import ch.madtreasures.g2watch.apps.packages.DexPackageLoader
import ch.madtreasures.g2watch.apps.packages.PackageStore
import ch.madtreasures.g2watch.apps.phone.MicPrompt
import ch.madtreasures.g2watch.apps.phone.PhoneLinks
import ch.madtreasures.g2watch.apps.phone.WatchMicrophone
import ch.madtreasures.g2watch.apps.phone.WearTransport
import ch.madtreasures.g2watch.ble.G2Scanner
import ch.madtreasures.g2watch.desktop.AndroidTextPainter
import ch.madtreasures.g2watch.desktop.Depth
import ch.madtreasures.g2watch.desktop.DesktopController
import ch.madtreasures.g2watch.firmware.AndroidFirmwareEnvironment
import ch.madtreasures.g2watch.firmware.LensPair
import ch.madtreasures.g2watch.firmware.WatchFirmwareInstaller
import ch.madtreasures.g2watch.glasses.FirmwareInstaller
import ch.madtreasures.g2watch.glasses.GlassesConnection
import ch.madtreasures.g2watch.glasses.GlassesListener
import ch.madtreasures.g2watch.glasses.GlassesService
import ch.madtreasures.g2watch.glasses.ProtocolFile
import ch.madtreasures.g2watch.input.HardwareEvents
import ch.madtreasures.g2watch.input.HidPairing
import ch.madtreasures.g2watch.input.InputDevices
import ch.madtreasures.g2watch.input.KeyLayout
import ch.madtreasures.g2watch.input.KeyPress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds the desktop and the glasses connection for the whole process, so both survive activity
 * recreation and keep running behind the foreground service while the watch display is off.
 */
class G2WatchApp : Application() {
    /** The black-and-white emoji font of both text painters; loaded once, it is 2 MB. */
    private val emojiFont: Typeface? by lazy { AndroidTextPainter.emojiFont(assets) }

    val desktop: DesktopController by lazy {
        DesktopController(AndroidTextPainter(emojiFont), depth = Depth.of(prefs.getString(DEPTH, null))).also { it.startClock() }
    }

    /** Settings → "Bildtiefe": kept for the next start, and the glasses show it at once. */
    fun setDepth(depth: Depth) {
        prefs.edit { putString(DEPTH, depth.name) }
        desktop.setDepth(depth)
    }

    /** Text questions of the apps, answered on the watch screen (keyboard or voice). */
    val textPrompts = TextPrompts()

    /** The question for the watch's microphone (apps that stream it to the phone), asked on the watch screen. */
    val micPrompt = MicPrompt()

    private val appsLazy = lazy {
        val main = MainScheduler()
        val log: (String) -> Unit = { line -> main.post { glasses.note(line) } }
        AppHost(
            scheduler = AppThread.scheduler(),
            screen = desktop,
            text = AndroidTextPainter(emojiFont),
            ports = AndroidHostPorts(this, textPrompts, keyboard = { inputDevices.keyboard }, phoneEngine = { phoneLinks(log) }, logSink = log),
            installed = packages,
        ).also { desktop.connectApps(it) }
    }

    /**
     * The connections of the apps to apps on the phone, through G2 Handy (interface 4). When the wearer
     * allows the microphone, the glasses connection's foreground service gets its type, and connections
     * that wanted it start it.
     */
    private fun phoneLinks(log: (String) -> Unit): PhoneLinks {
        val links = PhoneLinks(WearTransport(this, log), WatchMicrophone(this, micPrompt), ThreadScheduler("G2Watch-phone"), log)
        micPrompt.onAllowed = {
            GlassesService.refresh(this)
            links.microphoneAllowed()
        }
        return links
    }

    /** Runs the watch apps and shows them on the glasses (docs/app-entwicklung/03). */
    val apps: AppHost by appsLazy

    /** Manifests of the apps built into the APK; app packages cannot take their ids. */
    val builtInAppManifests by lazy { builtInApps.map { it().manifest } }

    /**
     * The app packages installed on the watch (docs/app-entwicklung/09): new ones arrive as `.g2app` files
     * in `Android/data/ch.madtreasures.g2watch/files/apps/` and are installed on the page "Apps".
     */
    val packages: AppPackages by lazy {
        val main = MainScheduler()
        val reserved = builtInAppManifests.map { it.id }.toSet() + Launcher.ID
        AppPackages(
            store = PackageStore(File(filesDir, "app-packages"), reserved),
            inbox = getExternalFilesDir("apps"),
            loader = DexPackageLoader(G2WatchApp::class.java.classLoader ?: ClassLoader.getSystemClassLoader()),
            io = Executors.newSingleThreadExecutor { Thread(it, "G2Watch-packages") },
            reserved = reserved,
            // Only a host that exists needs to hear about it; a new one reads the list itself.
            onChange = { if (appsLazy.isInitialized()) apps.packagesChanged() },
            log = { line -> main.post { glasses.note(line) } },
        ).also { it.rescan() }
    }

    /** Turns the glasses' and the watch's gestures, and a Bluetooth keyboard and mouse, into input for the apps. */
    val input: InputRouter by lazy { InputRouter(hardwareSink) { apps.gesture(it) } }

    /** Keyboards and mice connected to the watch (03 §5.4). */
    val inputDevices: InputDevices by lazy { InputDevices(this).also { it.start() } }

    private val _keyLayout by lazy { MutableStateFlow(KeyLayout.of(prefs.getString(KEY_LAYOUT, null))) }

    /** The layout of a hardware keyboard (Einstellungen → Tastatur & Maus); "Automatisch" by default. */
    val keyLayout: StateFlow<KeyLayout> by lazy { _keyLayout.asStateFlow() }

    fun setKeyLayout(layout: KeyLayout) {
        prefs.edit { putString(KEY_LAYOUT, layout.id) }
        _keyLayout.value = layout
    }

    /** Key and mouse events of the activity, while the touchpad shows, for the glasses. */
    val hardware: HardwareEvents by lazy {
        HardwareEvents(input, layout = { _keyLayout.value }, locale = { resources.configuration.locales[0] }, isGeneric = inputDevices::isGeneric)
    }

    /** "Gerät koppeln" in the settings: finds keyboards and mice nearby and pairs them. */
    val pairing: HidPairing by lazy { HidPairing(this) { line -> glasses.note(line) } }

    /** Where keyboard and mouse go: the pointer and its clicks to the desktop, keys, wheel and menu to the app host. */
    private val hardwareSink = object : HardwareSink {
        override val gestureMode: Boolean get() = apps.inputMode.value == InputMode.GESTURES
        override val speed: Float get() = desktop.speed.value

        override fun movePointer(dx: Float, dy: Float) = desktop.moveBy(dx, dy)

        override fun click() = desktop.click()

        override fun menu() = apps.openMenu()

        override fun back() = desktop.back()

        override fun wheel(notches: Int) = apps.wheel(notches)

        override fun key(press: KeyPress) = apps.key(press)
    }

    val glasses: GlassesConnection by lazy {
        GlassesConnection(
            this,
            desktop,
            object : GlassesListener {
                override fun onInput(kind: String?, eventType: Int, eventSource: Int, ringTick: Long, ringType: Int) =
                    input.onGlassesEvent(kind, eventType, eventSource, ringTick, ringType)

                override fun onStatus(status: GlassesStatus) = apps.updateGlasses(status)
            },
            protocol = { line ->
                // Android Studio → Logcat, filter "tag:G2Watch"; the file only when switched on.
                Log.i(LOG_TAG, line)
                protocolFile.append(line)
            },
        )
    }
    val scanner: G2Scanner by lazy { G2Scanner(this) }

    /** The protocol as a file for Android Studio's Device Explorer; optional, off until switched on. */
    val protocolFile: ProtocolFile by lazy {
        ProtocolFile(getExternalFilesDir("protokoll")).also { it.enabled = prefs.getBoolean(PROTOCOL_FILE, false) }
    }

    /** Settings → "Protokoll als Datei": on writes what the protocol already holds, then every new line. */
    fun setProtocolFile(on: Boolean) {
        prefs.edit { putBoolean(PROTOCOL_FILE, on) }
        if (on) protocolFile.start(glasses.log.value) else protocolFile.enabled = false
    }

    /**
     * Puts the original or the custom firmware on the last chosen glasses. Only the confirm page
     * (two-second hold) starts it; see [WatchFirmwareInstaller].
     */
    val firmware: FirmwareInstaller by lazy {
        val main = MainScheduler()
        val note: (String) -> Unit = { line -> main.post { glasses.note(line) } }
        WatchFirmwareInstaller(
            env = AndroidFirmwareEnvironment(this, { glasses }, main, note),
            pair = { lastPair()?.let { LensPair(it.right, it.left.orEmpty()) } },
            worker = ThreadScheduler("G2Watch-firmware"),
            log = note,
        )
    }

    private val prefs by lazy { getSharedPreferences("g2watch", Context.MODE_PRIVATE) }

    data class LastPair(val title: String, val right: String, val left: String?)

    override fun onCreate() {
        super.onCreate()
        // GeckoView (the browser, 05 §5) starts its page, GPU and network processes with this class
        // too; desktop, glasses connection and apps belong to the main process alone.
        if (!isMainProcess) return
        followWatchBattery()
        // Connects itself to the desktop, so the "Apps" tile works from the first frame.
        apps
        // Listens for keyboards and mice from the start, so a question already knows about one.
        inputDevices
    }

    /** False in GeckoView's own processes. */
    val isMainProcess: Boolean get() = getProcessName() == packageName

    fun lastPair(): LastPair? {
        val right = prefs.getString("right", null) ?: return null
        return LastPair(prefs.getString("title", null) ?: "G2", right, prefs.getString("left", null))
    }

    fun saveLastPair(title: String, right: String, left: String?) {
        prefs.edit {
            putString("title", title)
            putString("right", right)
            putString("left", left)
        }
    }

    fun forgetLastPair() {
        prefs.edit {
            remove("title")
            remove("right")
            remove("left")
        }
    }

    /** The watch battery in the desktop's top bar, from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private fun followWatchBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
                desktop.updateStatus { it.copy(watchBattery = percent) }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        /** Tag of the protocol lines in Logcat. */
        const val LOG_TAG = "G2Watch"

        /** Preference: protocol also as a file (Settings → "Protokoll als Datei"). */
        const val PROTOCOL_FILE = "protocolFile"

        /** Preference: the name of the [Depth] level (Settings → "Bildtiefe"). */
        const val DEPTH = "depth"
        /** Preference: layout of a hardware keyboard ([KeyLayout.id]). */
        const val KEY_LAYOUT = "keyboardLayout"
    }
}

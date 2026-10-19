package ch.madtreasures.g2watch.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** What a Bluetooth input device is, as far as it says so before pairing; [label] is German. */
enum class HidKind(val label: String) {
    KEYBOARD("Tastatur"),
    MOUSE("Maus"),
    COMBO("Tastatur und Maus"),
    OTHER("Eingabegerät"),
    ;

    companion object {
        /**
         * From the class of device of a classic device (BluetoothClass.getDeviceClass): a peripheral
         * (major 0x0500) with the keyboard (0x40) and pointing (0x80) bits; null for anything else.
         */
        fun ofClass(deviceClass: Int): HidKind? {
            if (deviceClass and 0x1F00 != 0x0500) return null
            return when (deviceClass and 0xC0) {
                0x40 -> KEYBOARD
                0x80 -> MOUSE
                0xC0 -> COMBO
                else -> OTHER
            }
        }

        /** From the GAP appearance an LE device advertises: the HID category 0x03C0 … 0x03FF. */
        fun ofAppearance(appearance: Int): HidKind? = when {
            appearance == 0x03C1 -> KEYBOARD
            appearance == 0x03C2 -> MOUSE
            appearance shr 6 == 0x0F -> OTHER
            else -> null
        }

        /** The appearance in a raw LE advertisement (AD type 0x19, little endian), or -1. */
        fun appearance(advertisement: ByteArray?): Int {
            val a = advertisement ?: return -1
            var i = 0
            while (i < a.size) {
                val length = a[i].toInt() and 0xFF
                if (length == 0 || i + length >= a.size) break
                if (a[i + 1].toInt() and 0xFF == 0x19 && length >= 3) return (a[i + 2].toInt() and 0xFF) or ((a[i + 3].toInt() and 0xFF) shl 8)
                i += length + 1
            }
            return -1
        }
    }
}

/** A keyboard or mouse nearby that the watch could pair with. */
data class FoundDevice(val address: String, val name: String, val kind: HidKind, val bonded: Boolean = false)

/**
 * "Gerät koppeln" on the watch: Wear OS's own Bluetooth list shows headphones but hides keyboards and
 * mice, so the app looks for them itself (classic discovery and an LE scan for the HID service, about
 * 15 s) and asks Android to pair the one the wearer picks (`createBond`). Android then connects it as
 * an input device, if Wear OS has the HID host profile at all – that is not known yet for the Pixel
 * Watch. Main thread; needs the Bluetooth permissions the app asks for at its start.
 */
@SuppressLint("MissingPermission")
class HidPairing(context: Context, private val log: (String) -> Unit) {
    data class State(val scanning: Boolean = false, val found: List<FoundDevice> = emptyList(), val message: String? = null)

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = this.context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var receiving = false
    private var scanning = false

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = State(message = "Bluetooth ist aus")
            return
        }
        if (_state.value.scanning) return
        _state.value = State(scanning = true, found = _state.value.found.filter { it.bonded })
        register()
        try {
            a.startDiscovery()
            a.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HID_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                leScan,
            )
            scanning = true
        } catch (e: SecurityException) {
            finish("Bluetooth-Berechtigung fehlt")
            return
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, SCAN_MS)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        if (_state.value.scanning) finish(null)
        unregister()
    }

    /** Asks Android to pair with [address]; a keyboard may then ask for a code typed on it. */
    fun pair(address: String) {
        val a = adapter ?: return
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return
        }
        stopScanning()
        register()
        val started = try {
            device.createBond()
        } catch (e: SecurityException) {
            false
        }
        val name = nameOf(address)
        log("Tastatur & Maus: koppeln mit $name – ${if (started) "gestartet" else "nicht möglich"}")
        _state.value = _state.value.copy(
            scanning = false,
            message = if (started) "Kopple mit $name … Bei einer Tastatur den angezeigten Code dort eintippen und Enter drücken." else "Koppeln mit $name ging nicht",
        )
    }

    private val timeout = Runnable { finish(null) }

    private fun finish(message: String?) {
        stopScanning()
        val found = _state.value.found
        _state.value = _state.value.copy(
            scanning = false,
            message = message ?: if (found.isEmpty()) "Nichts gefunden. Gerät in den Kopplungsmodus bringen und nochmals suchen." else null,
        )
    }

    private fun stopScanning() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.cancelDiscovery()
            adapter?.bluetoothLeScanner?.stopScan(leScan)
        } catch (e: SecurityException) {
            // Permission taken back meanwhile: nothing left to stop.
        }
    }

    private fun nameOf(address: String) = _state.value.found.firstOrNull { it.address == address }?.name ?: address

    private fun found(device: BluetoothDevice, name: String?, kind: HidKind) {
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            false
        }
        val entry = FoundDevice(device.address, name?.takeIf { it.isNotBlank() } ?: device.address, kind, bonded)
        val list = _state.value.found.filter { it.address != entry.address } + entry
        _state.value = _state.value.copy(found = list)
    }

    private val leScan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val kind = HidKind.ofAppearance(HidKind.appearance(record?.bytes)) ?: HidKind.OTHER
            val name = record?.deviceName ?: try {
                result.device.name
            } catch (e: SecurityException) {
                null
            }
            main.post { found(result.device, name, kind) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val cls = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                    val kind = cls?.let { HidKind.ofClass(it.deviceClass) } ?: return
                    found(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), kind)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val name = nameOf(device.address)
                    val message = when (now) {
                        BluetoothDevice.BOND_BONDED -> "Gekoppelt: $name. Es erscheint oben, sobald Wear OS es als Eingabegerät verbindet."
                        BluetoothDevice.BOND_NONE -> "Koppeln mit $name ging nicht"
                        else -> return
                    }
                    log("Tastatur & Maus: $message")
                    _state.value = _state.value.copy(
                        found = _state.value.found.map { if (it.address == device.address) it.copy(bonded = now == BluetoothDevice.BOND_BONDED) else it },
                        message = message,
                    )
                }
            }
        }
    }

    private fun register() {
        if (receiving) return
        receiving = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Sent by the Bluetooth stack, not by the system itself: an exported receiver; both are protected broadcasts.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unregister() {
        if (!receiving) return
        receiving = false
        context.unregisterReceiver(receiver)
    }

    private companion object {
        /** The HID over GATT service of LE keyboards and mice. */
        val HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

        const val SCAN_MS = 15_000L
    }
}

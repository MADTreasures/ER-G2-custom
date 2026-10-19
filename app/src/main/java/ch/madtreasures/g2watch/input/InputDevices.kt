package ch.madtreasures.g2watch.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A keyboard or mouse connected to the watch (Bluetooth, or USB on a dock). */
data class ExternalDevice(val id: Int, val name: String, val keyboard: Boolean, val mouse: Boolean)

/**
 * The keyboards and mice Android has connected to the watch, from its InputManager: a device shows up
 * here only once Wear OS took it as an input device (paired and connected through its HID host). The
 * watch's own buttons, crown and screen are no external devices and never show.
 */
class InputDevices(context: Context) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private val _devices = MutableStateFlow<List<ExternalDevice>>(emptyList())

    /** Connected keyboards and mice, newest last. */
    val devices: StateFlow<List<ExternalDevice>> = _devices.asStateFlow()

    /** Whether a keyboard with letters is connected; any thread. */
    val keyboard: Boolean get() = _devices.value.any { it.keyboard }

    /** Device id → whether its key map is Android's US Generic one. Main thread. */
    private val generic = HashMap<Int, Boolean>()

    fun start() {
        manager?.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refresh()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()

    override fun onInputDeviceRemoved(deviceId: Int) {
        generic.remove(deviceId)
        refresh()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // A new key map, e.g. after a layout change.
        generic.remove(deviceId)
        refresh()
    }

    private fun refresh() {
        _devices.value = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id)?.let(::describe) }
    }

    /** Whether [device] types with Android's US Generic map, so our own layout may replace it. */
    fun isGeneric(device: InputDevice?): Boolean = device != null && generic.getOrPut(device.id) {
        val map = device.keyCharacterMap
        KeyLayouts.isGeneric { code, meta -> map.get(code, meta) }
    }

    companion object {
        /** [device] as a keyboard or mouse, or null for anything else (the watch's own input among it). */
        fun describe(device: InputDevice): ExternalDevice? {
            if (!device.isExternal || device.isVirtual) return null
            val keyboard = device.supportsSource(InputDevice.SOURCE_KEYBOARD) && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            val mouse = isMouse(device)
            if (!keyboard && !mouse) return null
            return ExternalDevice(device.id, device.name.orEmpty().ifBlank { if (keyboard) "Tastatur" else "Maus" }, keyboard, mouse)
        }

        /** Keys from [device] belong to the glasses: a keyboard, or the media keys of one, connected from outside. */
        fun hasKeys(device: InputDevice?): Boolean =
            device != null && device.isExternal && !device.isVirtual && device.supportsSource(InputDevice.SOURCE_KEYBOARD)

        /**
         * Whether [device] is an external mouse. While a window holds its pointer capture, Android reports
         * the mouse as SOURCE_MOUSE_RELATIVE instead of SOURCE_MOUSE (and tells of a changed device): it
         * still counts, or the capture would end itself and come back in a loop.
         */
        fun isMouse(device: InputDevice?): Boolean =
            device != null && device.isExternal && !device.isVirtual &&
                (device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE))
    }
}

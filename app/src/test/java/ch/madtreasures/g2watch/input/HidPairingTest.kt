package ch.madtreasures.g2watch.input

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** "Gerät koppeln" with Robolectric's Bluetooth: what the wearer reads while a device pairs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HidPairingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
    }

    /** The class of device a Bluetooth keyboard sends: peripheral with the keyboard bit. */
    private fun keyboardClass(): BluetoothClass {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0x0540)
            parcel.setDataPosition(0)
            return BluetoothClass.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `picking a device ends the scan's timeout, so the pairing message stays`() {
        val pairing = HidPairing(context) {}
        pairing.start()
        assertTrue(pairing.state.value.scanning)
        // A keyboard turns up 4 s into the scan, and the wearer picks it.
        main.idleFor(Duration.ofSeconds(4))
        val keyboard = adapter.getRemoteDevice(ADDRESS)
        shadowOf(keyboard).setCreatedBond(true)
        context.sendBroadcast(
            Intent(BluetoothDevice.ACTION_FOUND)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, keyboard)
                .putExtra(BluetoothDevice.EXTRA_CLASS, keyboardClass())
                .putExtra(BluetoothDevice.EXTRA_NAME, NAME),
        )
        main.idle()
        assertEquals(listOf(NAME), pairing.state.value.found.map { it.name })
        pairing.pair(ADDRESS)
        val message = pairing.state.value.message
        assertNotNull(message)
        assertTrue(message!!, message.startsWith("Kopple mit $NAME"))
        // Well past the scan's 15 s, the message is still there.
        main.idleFor(Duration.ofSeconds(20))
        assertEquals(message, pairing.state.value.message)
        pairing.stop()
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val NAME = "Tastatur K380"
    }
}

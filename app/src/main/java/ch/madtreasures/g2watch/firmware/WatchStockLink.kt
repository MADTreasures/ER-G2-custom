package ch.madtreasures.g2watch.firmware

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.faceclaw.app.AndroidProtocolPlatform
import com.faceclaw.app.BondState
import com.faceclaw.app.FaceclawBleListener
import com.faceclaw.app.FaceclawBleManager
import com.faceclaw.app.GattWriteMode
import com.faceclaw.app.StockLink
import com.faceclaw.app.StockLinkListener

/**
 * Faceclaw's [StockLink] over its GATT manager, the same 1:1 wrapper as Faceclaw's
 * `AndroidStockLink`, which keeps its manager private. This one also tells the ATT MTU the
 * manager negotiated, which [GuardedStockLink] needs before it lets a firmware frame through.
 * Each flow gets its own link, like in Faceclaw.
 */
class WatchStockLink(context: Context) : StockLink, FaceclawBleListener {
    private val bleManager = FaceclawBleManager(context.applicationContext)

    @Volatile
    private var listener: StockLinkListener? = null

    init {
        bleManager.setListener(this)
    }

    /** 23 (the ATT default) until an MTU exchange succeeded on this connection. */
    fun negotiatedMtu(address: String): Int = bleManager.getNegotiatedMtu(address)

    override fun setListener(listener: StockLinkListener?) {
        this.listener = listener
    }

    override fun connect(address: String, timeoutMs: Int): Boolean = bleManager.connect(address, timeoutMs)

    override fun prepareLink(address: String, desiredMtu: Int, timeoutMs: Int) {
        bleManager.requestConnectionPriority(address, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        bleManager.requestMtu(address, desiredMtu, timeoutMs)
    }

    override fun discoverServices(address: String, timeoutMs: Int): Boolean = bleManager.discoverServices(address, timeoutMs)

    override fun enableNotifications(address: String, characteristicUuid: String, timeoutMs: Int): Boolean =
        bleManager.enableNotifications(address, characteristicUuid, true, timeoutMs)

    override fun writeFrames(address: String, characteristicUuid: String, frames: List<ByteArray>, mode: GattWriteMode, timeoutMs: Int): Boolean =
        bleManager.writeFrames(address, characteristicUuid, frames, AndroidProtocolPlatform.writeType(mode), timeoutMs)

    override fun isConnected(address: String): Boolean = bleManager.isConnected(address)

    override fun bondState(address: String): BondState =
        when (bleManager.getBondState(address)) {
            BluetoothDevice.BOND_NONE -> BondState.NONE
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            else -> BondState.UNKNOWN
        }

    override fun disconnect(address: String) = bleManager.disconnect(address)

    override fun close() = bleManager.close()

    override fun onNotification(address: String?, characteristicUuid: String?, data: ByteArray?) {
        if (address == null || characteristicUuid == null || data == null) return
        listener?.onNotification(address, characteristicUuid, data)
    }

    override fun onConnectionStateChange(address: String?, connected: Boolean) {
        if (address == null) return
        listener?.onConnectionStateChange(address, connected)
    }

    companion object {
        /** A fresh link, guarded for firmware writes. */
        fun guarded(context: Context, log: (String) -> Unit): GuardedStockLink {
            val link = WatchStockLink(context)
            return GuardedStockLink(link, link::negotiatedMtu, log)
        }
    }
}

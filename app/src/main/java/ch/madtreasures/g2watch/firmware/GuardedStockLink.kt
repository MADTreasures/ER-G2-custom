package ch.madtreasures.g2watch.firmware

import com.faceclaw.app.BleProtocol
import com.faceclaw.app.BondState
import com.faceclaw.app.GattWriteMode
import com.faceclaw.app.StockLink
import com.faceclaw.app.StockLinkListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [StockLink] every firmware flow of the watch runs on: Faceclaw's GATT link ([delegate])
 * with two guards in front of the firmware-update characteristic.
 *
 * - **Armed:** nothing reaches the update characteristic unless the installer [arm]ed the link.
 *   It does so only after the wearer confirmed on the watch and on the glasses and the image was
 *   checked against the allow-list a moment before. The probe, the on-glasses prompt and the test
 *   run all run disarmed, so they cannot write firmware even by mistake.
 * - **MTU:** Faceclaw's flasher writes 240-byte frames and requests a large MTU, but never checks
 *   what it got. A write is refused unless the link negotiated an ATT MTU of at least [MIN_MTU]
 *   and every frame fits. On such a watch the transfer therefore stops at its very first write
 *   (BEGIN), before the glasses were told anything, instead of halfway through a component.
 *
 * A refused write returns false, exactly like a failed GATT write, so Faceclaw's flows handle it
 * with their normal failure paths (the flasher reports an ack timeout and gives up the lens).
 */
class GuardedStockLink(
    private val delegate: StockLink,
    /** The ATT MTU negotiated with [address] so far (23 before any exchange). */
    private val negotiatedMtu: (address: String) -> Int,
    private val log: (String) -> Unit = {},
) : StockLink {

    @Volatile
    private var armed = false

    private val refused = AtomicInteger()
    private val otaWrites = AtomicInteger()

    /** Allows writes to the update characteristic from now on. */
    fun arm() {
        armed = true
    }

    /** Refuses writes to the update characteristic from now on. */
    fun disarm() {
        armed = false
    }

    val isArmed: Boolean get() = armed

    /** Messages written to the update characteristic (each may span several frames). */
    val otaWriteCount: Int get() = otaWrites.get()

    /** Writes to the update characteristic that the guards refused. */
    val refusedWriteCount: Int get() = refused.get()

    fun mtu(address: String): Int = negotiatedMtu(address)

    override fun writeFrames(
        address: String,
        characteristicUuid: String,
        frames: List<ByteArray>,
        mode: GattWriteMode,
        timeoutMs: Int,
    ): Boolean {
        if (characteristicUuid.equals(BleProtocol.OTA_DATA_WRITE_UUID, ignoreCase = true)) {
            if (!armed) {
                refused.incrementAndGet()
                log("blocked: firmware write while the link is not armed")
                return false
            }
            val mtu = negotiatedMtu(address)
            val largest = frames.maxOfOrNull { it.size } ?: 0
            if (mtu < MIN_MTU || largest > mtu - ATT_HEADER) {
                refused.incrementAndGet()
                log("blocked: firmware write with MTU $mtu (needs $MIN_MTU, frame $largest bytes)")
                return false
            }
            otaWrites.incrementAndGet()
        }
        return delegate.writeFrames(address, characteristicUuid, frames, mode, timeoutMs)
    }

    override fun setListener(listener: StockLinkListener?) = delegate.setListener(listener)

    override fun connect(address: String, timeoutMs: Int): Boolean = delegate.connect(address, timeoutMs)

    override fun prepareLink(address: String, desiredMtu: Int, timeoutMs: Int) = delegate.prepareLink(address, desiredMtu, timeoutMs)

    override fun discoverServices(address: String, timeoutMs: Int): Boolean = delegate.discoverServices(address, timeoutMs)

    override fun enableNotifications(address: String, characteristicUuid: String, timeoutMs: Int): Boolean =
        delegate.enableNotifications(address, characteristicUuid, timeoutMs)

    override fun isConnected(address: String): Boolean = delegate.isConnected(address)

    override fun bondState(address: String): BondState = delegate.bondState(address)

    override fun disconnect(address: String) = delegate.disconnect(address)

    override fun close() {
        armed = false
        delegate.close()
    }

    companion object {
        /** A 240-byte frame plus the 3-byte ATT write header. */
        const val MIN_MTU = 243
        private const val ATT_HEADER = 3
    }
}

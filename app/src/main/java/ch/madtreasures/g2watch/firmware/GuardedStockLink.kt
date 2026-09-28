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
 *   checked against the allow-list a moment before. The probe, the on-glasses prompt and the
 *   check after the transfer run disarmed, so they cannot write firmware even by mistake.
 * - **MTU:** Faceclaw's flasher writes 240-byte frames and requests a large MTU, but never checks
 *   what it got. While armed, a connection whose MTU exchange ended below [MIN_MTU] is dropped
 *   right in [prepareLink], so the flasher's own reconnect loop (built for lenses that are still
 *   rebooting) tries again, and BEGIN only ever goes out on a wide enough link. As a backstop a
 *   write is refused unless the link has at least [MIN_MTU] and every frame fits.
 *
 * A refused write returns false, exactly like a failed GATT write, so Faceclaw's flows handle it
 * with their normal failure paths. A write on a link that is gone is passed on unchanged, so a
 * lost connection is reported as such and not as a narrow MTU.
 */
class GuardedStockLink(
    private val delegate: StockLink,
    /** The ATT MTU negotiated with [address] so far (23 before any exchange). */
    private val negotiatedMtu: (address: String) -> Int,
    private val log: (String) -> Unit = {},
) : StockLink {

    @Volatile
    private var armed = false

    private val arms = AtomicInteger()

    private val refused = AtomicInteger()
    private val mtuRefused = AtomicInteger()
    private val otaWrites = AtomicInteger()

    /**
     * Lenses whose latest MTU exchange left a live link too narrow, with that MTU; a new connection
     * clears the entry.
     */
    private val narrow = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Allows writes to the update characteristic from now on. */
    fun arm() {
        arms.incrementAndGet()
        armed = true
    }

    /** How often [arm] was called on this link. */
    val armCount: Int get() = arms.get()

    /** Refuses writes to the update characteristic from now on. */
    fun disarm() {
        armed = false
    }

    val isArmed: Boolean get() = armed

    /** Messages written to the update characteristic (each may span several frames). */
    val otaWriteCount: Int get() = otaWrites.get()

    /** Writes to the update characteristic that the guards refused. */
    val refusedWriteCount: Int get() = refused.get()

    /** Connections dropped and writes refused because the MTU was too small. */
    val mtuRefusalCount: Int get() = mtuRefused.get()

    /** True while some lens's latest MTU exchange was too narrow, i.e. the MTU is why it stopped. */
    val mtuTooNarrow: Boolean get() = narrow.isNotEmpty()

    /** The narrowest MTU behind [mtuTooNarrow], or null. */
    val narrowMtu: Int? get() = narrow.values.minOrNull()

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
            // A link that is gone fails in the delegate as usual; its MTU entry is gone with it.
            if (!delegate.isConnected(address)) return delegate.writeFrames(address, characteristicUuid, frames, mode, timeoutMs)
            val mtu = negotiatedMtu(address)
            val largest = frames.maxOfOrNull { it.size } ?: 0
            if (mtu < MIN_MTU || largest > mtu - ATT_HEADER) {
                // The link can vanish between the two reads above: only a live link has a narrow MTU.
                if (!delegate.isConnected(address)) return delegate.writeFrames(address, characteristicUuid, frames, mode, timeoutMs)
                refused.incrementAndGet()
                mtuRefused.incrementAndGet()
                narrow[address] = mtu
                log("blocked: firmware write with MTU $mtu (needs $MIN_MTU, frame $largest bytes)")
                return false
            }
            otaWrites.incrementAndGet()
        }
        return delegate.writeFrames(address, characteristicUuid, frames, mode, timeoutMs)
    }

    override fun setListener(listener: StockLinkListener?) = delegate.setListener(listener)

    override fun connect(address: String, timeoutMs: Int): Boolean {
        // The MTU hint follows the latest attempt per lens.
        narrow.remove(address)
        return delegate.connect(address, timeoutMs)
    }

    override fun prepareLink(address: String, desiredMtu: Int, timeoutMs: Int) {
        delegate.prepareLink(address, desiredMtu, timeoutMs)
        if (!armed) return
        val mtu = negotiatedMtu(address)
        val connected = delegate.isConnected(address)
        if (mtu >= MIN_MTU) {
            narrow.remove(address)
        } else {
            // Drop it: bring-up fails at the next step and the flasher reconnects within its window.
            // Only a link that is still up is too narrow, the ATT default 23 included (the watch got
            // no more); a link lost during the exchange says nothing about the MTU.
            if (connected) narrow[address] = mtu
            mtuRefused.incrementAndGet()
            log("MTU $mtu after the exchange (needs $MIN_MTU): dropping the connection before any firmware write")
            delegate.disconnect(address)
        }
    }

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

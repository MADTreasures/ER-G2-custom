package ch.madtreasures.g2watch.firmware

import com.faceclaw.app.AndroidProtocolPlatform
import com.faceclaw.app.BleProtocol
import com.faceclaw.app.BondState
import com.faceclaw.app.GattWriteMode
import com.faceclaw.app.ProtocolPlatform
import com.faceclaw.app.StockFlowTimings
import com.faceclaw.app.StockLink
import com.faceclaw.app.StockLinkListener
import java.util.Collections

/** Faceclaw's Android platform with a JVM clock, for unit tests. */
val testPlatform: ProtocolPlatform = object : ProtocolPlatform by AndroidProtocolPlatform {
    override fun elapsedRealtimeMs() = System.nanoTime() / 1_000_000
}

/** Faceclaw's stock-flow timings shrunk to milliseconds, like Faceclaw's own StockFlowsTest. */
val fastTimings = StockFlowTimings(
    securityAuthTimeoutMs = 300, securityAuthSoftTimeoutMs = 100, pairingWaitCapMs = 2_000, postBondGraceMs = 20, pollMs = 5,
    armRetryDelayMs = 5, queryTimeoutMs = 60, preludeTimeoutMs = 60, heartbeatIntervalMs = 15, selectionTimeoutMs = 300,
    createAckTimeoutMs = 60, batteryAckTimeoutMs = 60, blockAckTimeoutMs = 80, ctrlAckTimeoutMs = 80,
    firstLensConnectWindowMs = 300, secondLensConnectWindowMs = 300, rebootSettleMs = 5, notifySettleMs = 5,
    otaRetryDelayMs = 5, componentRetryDelayMs = 5,
)

const val RIGHT = "AA:00:00:00:00:01"
const val LEFT = "AA:00:00:00:00:02"

/**
 * A pair of G2 lenses behind Faceclaw's [StockLink] port, answering like the stock firmware:
 * security auth, prelude, settings reads (versions, battery, silent mode, field 100), the EvenHub
 * prompt page with a scripted answer, and the OTA update channel. What the lenses received over
 * OTA is kept per lens; once a lens accepted every component and the link drops, it "reboots"
 * into [firmwareAfterFlash].
 */
class SimulatedGlasses(
    var leftVersion: String = "2.3.0.24",
    var rightVersion: String = "2.3.0.24",
    /** Settings field 100: "" on stock, "Faceclaw/35" on the custom firmware. */
    var extension: String = "",
    val battery: MutableMap<String, Int> = mutableMapOf(RIGHT to 80, LEFT to 75),
    /**
     * Whether a lens answers the session prelude on its own link. Faceclaw only ever sends it to
     * the right arm; the left arm alone is not known to answer, so tests can switch that off.
     */
    var leftAnswersPrelude: Boolean = true,
    /** Index the wearer taps on the prompt: 1 = "Yes, flash", 0 = "No, cancel", null = nothing. */
    var promptAnswer: Int? = 1,
    var silentMode: Boolean = false,
    /** ATT MTU each connection negotiates. */
    var mtu: Int = 247,
    /** The extension the lenses report once both accepted a complete image. */
    var firmwareAfterFlash: String = "Faceclaw/35",
    /** Per-lens field 100 that wins over [extension], e.g. for a lens that did not switch. */
    val armExtension: MutableMap<String, String> = mutableMapOf(),
    /** How many components make a complete image (the synthetic images have 5). */
    var componentCount: Int = 5,
) : StockLink {
    class Written(val address: String, val uuid: String, val sid: Int, val seq: Int, val pb: ByteArray)

    private var listener: StockLinkListener? = null

    /** The flow currently listening, for tests that answer a write themselves. */
    val listenerForTests: StockLinkListener? get() = listener
    private val connected: MutableSet<String> = Collections.synchronizedSet(HashSet())
    val writes: MutableList<Written> = Collections.synchronizedList(ArrayList())
    var connectResult: (String) -> Boolean = { true }

    /** Called for every write before the lens answers; tests use it to inject faults. */
    var beforeAnswer: (Written) -> Boolean = { true }

    /** Component payloads each lens accepted (END ok), in order. */
    val received = HashMap<String, MutableList<ByteArray>>()
    private val current = HashMap<String, ByteArray>()
    val otaWrites: List<Written> get() = synchronized(writes) { writes.filter { it.uuid.equals(BleProtocol.OTA_DATA_WRITE_UUID, true) } }
    var closed = 0
        private set

    fun guardedLink(log: (String) -> Unit = {}) = GuardedStockLink(this, { mtu }, log)

    // --- StockLink ------------------------------------------------------------------------------

    override fun setListener(listener: StockLinkListener?) {
        this.listener = listener
    }

    override fun connect(address: String, timeoutMs: Int): Boolean = connectResult(address).also { if (it) connected.add(address) }

    override fun prepareLink(address: String, desiredMtu: Int, timeoutMs: Int) = Unit

    override fun discoverServices(address: String, timeoutMs: Int): Boolean = address in connected

    override fun enableNotifications(address: String, characteristicUuid: String, timeoutMs: Int): Boolean = address in connected

    override fun writeFrames(address: String, characteristicUuid: String, frames: List<ByteArray>, mode: GattWriteMode, timeoutMs: Int): Boolean {
        if (address !in connected) throw IllegalStateException("Not connected: $address")
        val head = frames.first()
        val body = ByteArray(frames.sumOf { it.size - 8 })
        var at = 0
        for (f in frames) {
            f.copyInto(body, at, 8)
            at += f.size - 8
        }
        val w = Written(address, characteristicUuid, head[6].toInt() and 0xff, head[2].toInt() and 0xff, BleProtocol.stripTrailingCrc(body))
        writes.add(w)
        if (beforeAnswer(w)) answer(w)
        return true
    }

    override fun isConnected(address: String): Boolean = address in connected

    override fun bondState(address: String): BondState = BondState.BONDED

    override fun disconnect(address: String) {
        if (connected.remove(address)) rebootIfFlashed(address)
    }

    override fun close() {
        closed++
        synchronized(connected) { connected.toList() }.forEach { disconnect(it) }
    }

    // --- the lenses ------------------------------------------------------------------------------

    private fun answer(w: Written) {
        if (w.uuid.equals(BleProtocol.OTA_DATA_WRITE_UUID, true)) {
            answerOta(w)
            return
        }
        val magic = BleProtocol.readVarintFieldValue(w.pb, 2, -1)
        when (w.sid) {
            BleProtocol.SID_SECURITY_AUTH -> notify(w.address, varintField(1, 4) + varintField(2, magic) + field(3, ByteArray(0)), w.sid)
            BleProtocol.PRELUDE_ACK_SID -> if (w.address != LEFT || leftAnswersPrelude) notify(w.address, ack(BleProtocol.PRELUDE_ACK_MAGIC), w.sid)
            BleProtocol.SID_UI_SETTING -> notify(w.address, settingsAck(w.address, magic), w.sid)
            BleProtocol.SID_EVENHUB -> {
                val cmd = BleProtocol.readVarintFieldValue(w.pb, 1, -1)
                notify(w.address, ack(magic), w.sid)
                val choice = promptAnswer
                if (cmd == 0 && choice != null) {
                    val items = listOf("No, cancel", "Yes, flash")
                    notify(w.address, listSelection("flashmenu", items[choice], choice, BleProtocol.EVENT_CLICK), BleProtocol.SID_EVENHUB, BleProtocol.FLAG_NOTIFY)
                }
            }
        }
    }

    private fun answerOta(w: Written) {
        val op = w.pb.firstOrNull()?.toInt() ?: return
        when (w.sid) {
            SID_CTRL -> when (op) {
                OP_BEGIN -> otaAck(w.address, OP_BEGIN, 0)
                OP_FILE_CHECK -> {
                    current[w.address] = ByteArray(0)
                    otaAck(w.address, OP_FILE_CHECK, 0)
                }
                OP_END -> {
                    received.getOrPut(w.address) { ArrayList() } += current[w.address] ?: ByteArray(0)
                    otaAck(w.address, OP_END, 8)
                }
            }
            SID_DATA -> {
                current[w.address] = (current[w.address] ?: ByteArray(0)) + w.pb
                otaAck(w.address, OP_BLOCK, 0)
            }
        }
    }

    private fun rebootIfFlashed(address: String) {
        val flashed = received.keys.count { (received[it]?.size ?: 0) >= componentCount }
        if (address in received && flashed == 2) extension = firmwareAfterFlash
    }

    private fun settingsAck(address: String, magic: Int): ByteArray {
        var request = field(5, leftVersion) + field(6, rightVersion)
        battery[address]?.let { request += varintField(12, it) }
        request += varintField(14, if (silentMode) 1 else 0)
        var pb = varintField(1, 2) + varintField(2, magic) + field(4, request)
        val ext = armExtension[address] ?: extension
        if (ext.isNotEmpty()) pb += field(100, ext)
        return pb
    }

    private fun notify(address: String, pb: ByteArray, sid: Int, flag: Int = 0) {
        for (frame in BleProtocol.framePb(pb, sid, flag, 7)) listener?.onNotification(address, BleProtocol.NOTIFY_CHAR_UUID, frame)
    }

    private fun otaAck(address: String, op: Int, status: Int) {
        val frame = BleProtocol.framePb(byteArrayOf(op.toByte(), status.toByte()), SID_DATA, 0, 1).single()
        listener?.onNotification(address, BleProtocol.OTA_DATA_NOTIFY_UUID, frame)
    }

    fun drop(address: String) {
        connected.remove(address)
        listener?.onConnectionStateChange(address, false)
    }

    companion object {
        const val SID_CTRL = 0xc0
        const val SID_DATA = 0xc1
        const val OP_BEGIN = 0
        const val OP_FILE_CHECK = 1
        const val OP_BLOCK = 2
        const val OP_END = 3

        private fun varint(value: Int): ByteArray {
            var v = value
            val out = ArrayList<Byte>()
            do {
                var b = v and 0x7f
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out.add(b.toByte())
            } while (v != 0)
            return out.toByteArray()
        }

        fun varintField(number: Int, value: Int): ByteArray = varint(number shl 3) + varint(value)

        fun field(number: Int, bytes: ByteArray): ByteArray = varint((number shl 3) or 2) + varint(bytes.size) + bytes

        fun field(number: Int, text: String): ByteArray = field(number, text.encodeToByteArray())

        private fun ack(magic: Int) = varintField(1, 1) + varintField(2, magic)

        private fun listSelection(container: String, item: String, index: Int, eventType: Int): ByteArray =
            field(13, field(1, field(2, container) + field(3, item) + varintField(4, index) + varintField(5, eventType)))
    }
}

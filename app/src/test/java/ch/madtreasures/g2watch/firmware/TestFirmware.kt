package ch.madtreasures.g2watch.firmware

import java.util.Random
import java.util.zip.CRC32

/**
 * Small, fully valid EVENOTA images (never Even's firmware): five components, the last one the
 * main application with a correct preamble, so both our validator and Faceclaw's accept them.
 */
object TestFirmware {
    private val SIZES = listOf(300, 64, 128, 32, 9_000)
    private const val MAIN_APP = "ota/s200_firmware_ota.bin"

    val stock: ByteArray = build(seed = 1)
    val custom: ByteArray = build(seed = 2)
    val stockSha: String = Digests.sha256(stock)
    val customSha: String = Digests.sha256(custom)

    /** Component payloads of [image] in order: what a lens must receive. */
    fun payloads(image: ByteArray): List<ByteArray> = EvenOtaImage.parse(image).let { img -> img.components.map { img.payload(it) } }

    fun build(seed: Long): ByteArray {
        val rnd = Random(seed)
        val n = SIZES.size
        val tocEnd = 0x40 + n * 16
        val offsets = ArrayList<Int>()
        var pos = tocEnd
        for (s in SIZES) {
            offsets += pos
            pos += 128 + s
        }
        val img = ByteArray(pos)
        "EVENOTA".toByteArray().copyInto(img, 0)
        putU32(img, 8, n.toLong())
        for (i in 0 until n) {
            val off = offsets[i]
            val ps = SIZES[i]
            putU32(img, off + 8, ps.toLong())
            val name = if (i == n - 1) MAIN_APP else "ota/part$i.bin"
            name.toByteArray().copyInto(img, off + 48)
            val p = off + 128
            for (k in 0 until ps) img[p + k] = rnd.nextInt(256).toByte()
            if (name == MAIN_APP) {
                putU32(img, p, ps.toLong() or 0x04000000L)
                putU32(img, p + 0x14, 0x00438000L)
                putU32(img, p + 4, CRC32().apply { update(img, p + 8, ps - 8) }.value)
            }
            val crc = Crc32cMsb.compute(img, p, ps)
            val toc = 0x40 + i * 16
            putU32(img, toc, i.toLong())
            putU32(img, toc + 4, off.toLong())
            putU32(img, toc + 8, (ps + 128).toLong())
            putU32(img, toc + 12, crc)
            putU32(img, off + 12, crc)
        }
        return img
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        for (k in 0 until 4) b[off + k] = (v ushr (8 * k)).toByte()
    }
}

/** The allow-list of the tests: the two synthetic images, described like the real ones. */
object TestImages : FirmwareImages {
    var allowList: (String) -> FirmwareKind? = { sha ->
        when (sha) {
            TestFirmware.stockSha -> FirmwareKind.Stock
            TestFirmware.customSha -> FirmwareKind.Custom
            else -> null
        }
    }

    override fun prepare(kind: FirmwareKind, stock: ByteArray, onPatch: (applied: Int, total: Int) -> Unit): EvenOtaImage {
        if (Digests.sha256(stock) != TestFirmware.stockSha) throw FirmwareBuildException("wrong stock image")
        onPatch(1, 1)
        return EvenOtaImage.parse(if (kind == FirmwareKind.Stock) stock else TestFirmware.custom)
    }

    override fun kindOf(sha256: String): FirmwareKind? = allowList(sha256)

    override fun describe(kind: FirmwareKind): String = FirmwareCatalog.describe(kind)
}

/** A watch around [glasses] that records what the job asked of it. */
class FakeFirmwareEnvironment(val glasses: SimulatedGlasses = SimulatedGlasses()) : FirmwareEnvironment {
    var stockBytes: ByteArray = TestFirmware.stock
    var stockError: Exception? = null
    var power = WatchPower(80, charging = false)
    var released = 0
    val awake = ArrayList<String>()
    var asleep = 0
    val links = ArrayList<GuardedStockLink>()
    val log = ArrayList<String>()

    override val stock = StockImageSource { progress ->
        stockError?.let { throw it }
        progress(stockBytes.size.toLong(), stockBytes.size.toLong())
        stockBytes
    }
    override val images: FirmwareImages = TestImages
    override val timings = fastTimings
    override val platform = testPlatform

    override fun openLink(log: (String) -> Unit): GuardedStockLink = glasses.guardedLink(log).also { links += it }

    override fun watchPower() = power

    override fun releaseGlasses() {
        released++
    }

    override fun keepAwake(text: String) {
        awake += text
    }

    override fun allowSleep() {
        asleep++
    }

    override fun sleep(ms: Long) = Unit

    /** The saved notice; tests read and preset it. */
    var notice: Pair<ch.madtreasures.g2watch.glasses.FirmwareTarget, String>? = null

    /** Every saved message, null for each clear. */
    val noticeHistory = ArrayList<String?>()

    override fun saveNotice(target: ch.madtreasures.g2watch.glasses.FirmwareTarget, message: String) {
        notice = target to message
        noticeHistory += message
    }

    override fun clearNotice() {
        notice = null
        noticeHistory += null
    }

    override fun savedNotice() = notice
}

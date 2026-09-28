package ch.madtreasures.g2watch.firmware

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatchSetTest {
    private val base = ByteArray(64) { it.toByte() }
    private val expected = base.copyOf(68).also {
        it[4] = 0x55
        it[5] = 0x66
        it[64] = 1; it[65] = 2; it[66] = 3; it[67] = 4
    }

    private fun spec(
        baseHash: String = Digests.sha256(base),
        outHash: String = Digests.sha256(expected),
        ops: String = """{"offset": 4, "old": "0405", "new": "5566", "desc": "edit"},
                         {"offset": 64, "old": "", "new": "01020304", "desc": "append"}""",
    ) = """{"base": "test.bin", "base_sha256": "$baseHash", "output_sha256": "$outHash", "patches": [$ops]}"""

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `applies edits and appends and checks both hashes`() {
        val set = PatchSet.parse(spec())
        var steps = 0
        val out = set.apply(base) { applied, total -> steps = applied; assertEquals(2, total) }
        assertContentEquals(expected, out)
        assertEquals(2, steps)
        assertEquals(4, base[4].toInt(), "the base must not be modified")
    }

    @Test
    fun `refuses a different base image`() {
        val e = assertThrows<FirmwareBuildException> { PatchSet.parse(spec()).apply(base.copyOf().also { it[0] = 9 }) }
        assertContains(e.message!!, "not the version")
    }

    @Test
    fun `refuses unexpected bytes, a bad append offset and a wrong result`() {
        val badOld = spec(ops = """{"offset": 4, "old": "ffff", "new": "5566", "desc": "x"}""", outHash = "00")
        assertContains(assertThrows<FirmwareBuildException> { PatchSet.parse(badOld).apply(base) }.message!!, "unexpected bytes")
        val badAppend = spec(ops = """{"offset": 60, "old": "", "new": "0102", "desc": "x"}""", outHash = "00")
        assertContains(assertThrows<FirmwareBuildException> { PatchSet.parse(badAppend).apply(base) }.message!!, "append offset")
        assertContains(assertThrows<FirmwareBuildException> { PatchSet.parse(spec(outHash = "ab")).apply(base) }.message!!, "failed verification")
        assertThrows<FirmwareBuildException> { PatchSet.parse(spec(ops = """{"offset": 4, "old": "0g", "new": "55", "desc": "x"}""")) }
    }

    @Test
    fun `the bundled patch set matches the catalog`() {
        val set = FirmwareCatalog.patchSet
        assertEquals("g2_2.3.0.24.bin", set.baseName)
        assertEquals(FirmwareCatalog.STOCK_SHA256, set.baseSha256)
        assertEquals(FirmwareCatalog.CUSTOM_SHA256, set.outputSha256)
        assertEquals(38, set.ops.size)
        val appended = set.ops.filter { it.old.isEmpty() }
        assertEquals(1, appended.size)
        assertEquals(FirmwareCatalog.STOCK_SIZE, appended[0].offset)
        assertEquals(FirmwareCatalog.CUSTOM_SIZE, FirmwareCatalog.STOCK_SIZE + appended[0].new.size)
        // The main-app checksum fix-up in the table of contents is part of the set.
        val crcOp = set.ops.single { it.offset == 0x9c }
        assertEquals("c0c6322a", crcOp.old.hex())
        assertEquals("817b5baa", crcOp.new.hex())
    }

    @Test
    fun `the allow-list knows exactly two images`() {
        assertEquals(FirmwareKind.Stock, FirmwareCatalog.kindOf(FirmwareCatalog.STOCK_SHA256))
        assertEquals(FirmwareKind.Custom, FirmwareCatalog.kindOf(FirmwareCatalog.CUSTOM_SHA256.uppercase()))
        assertNull(FirmwareCatalog.kindOf(Digests.sha256(base)))
        assertThrows<FirmwareBuildException> { FirmwareCatalog.prepare(FirmwareKind.Custom, base) }
        assertThrows<FirmwareBuildException> { FirmwareCatalog.prepare(FirmwareKind.Stock, base) }
    }

    @Test
    fun `the catalog describes both targets`() {
        assertEquals("Even Realities 2.3.0.24", FirmwareCatalog.describe(FirmwareKind.Stock))
        assertEquals("Faceclaw/35 · Basis 2.3.0.24", FirmwareCatalog.describe(FirmwareKind.Custom))
        assertEquals("Faceclaw/35", FirmwareCatalog.CUSTOM_EXTENSION)
    }
}

package ch.madtreasures.g2watch.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors Faceclaw's tests/firmware-compat.test.cjs (exact revision since 0.8.0), plus the watch app's stricter gate. */
class FirmwareRequirementTest {
    private val required = FirmwareRequirement.REQUIRED_REVISION

    private fun check(extension: String, left: String = "2.2.9.22", right: String = "2.2.9.22") =
        FirmwareRequirement.check(left, right, extension)

    @Test
    fun `parses each slot format`() {
        assertEquals(FirmwareExtension.Stock, FirmwareRequirement.parseExtension(""))
        assertEquals(FirmwareExtension.Stock, FirmwareRequirement.parseExtension("  "))
        assertEquals(FirmwareExtension.Stock, FirmwareRequirement.parseExtension(null))
        assertEquals(FirmwareExtension.Faceclaw(1), FirmwareRequirement.parseExtension("Faceclaw/1"))
        assertEquals(FirmwareExtension.Faceclaw(12), FirmwareRequirement.parseExtension(" Faceclaw/12 "))
        assertEquals(
            FirmwareExtension.LegacyFaceclaw("EVENCFW/22 img640 imgz rle"),
            FirmwareRequirement.parseExtension("EVENCFW/22 img640 imgz rle"),
        )
        assertEquals(FirmwareExtension.LegacyFaceclaw("EVENCFW"), FirmwareRequirement.parseExtension("EVENCFW"))
        assertEquals(FirmwareExtension.Other("OtherCFW/3"), FirmwareRequirement.parseExtension("OtherCFW/3"))
        // A Faceclaw prefix without a usable number is not Faceclaw's.
        assertEquals(FirmwareExtension.Other("Faceclaw/x"), FirmwareRequirement.parseExtension("Faceclaw/x"))
        assertEquals(FirmwareExtension.Other("Faceclaw/-3"), FirmwareRequirement.parseExtension("Faceclaw/-3"))
        // Like parseInt: trailing text after the digits is ignored.
        assertEquals(FirmwareExtension.Faceclaw(34), FirmwareRequirement.parseExtension("Faceclaw/34 debug"))
    }

    @Test
    fun `only the required revision is compatible`() {
        val exact = check("Faceclaw/$required")
        assertEquals(FirmwareKind.COMPATIBLE, exact.kind)
        assertTrue(exact.compatible)
        assertNull(exact.message)
        // Judged by the revision, not by the stock base it was built from.
        assertEquals(FirmwareKind.COMPATIBLE, check("Faceclaw/$required", "2.3.0.1", "2.3.0.1").kind)
    }

    @Test
    fun `a newer revision is refused because the draw protocol changes between revisions`() {
        val newer = check("Faceclaw/${required + 1}")
        assertEquals(FirmwareKind.NEWER_FACECLAW, newer.kind)
        assertTrue(!newer.compatible)
        assertTrue(newer.message.orEmpty(), newer.message.orEmpty().contains("genau Faceclaw-Firmware Revision $required"))
    }

    @Test
    fun `the required revision is the one the watch installs`() {
        assertEquals(ch.madtreasures.g2watch.firmware.FirmwareCatalog.CUSTOM_REVISION, required)
        assertEquals(35, required)
    }

    @Test
    fun `stock newer than the base of the images is recognised`() {
        assertTrue(FirmwareRequirement.isNewerThanBase("2.3.1.0"))
        assertTrue(FirmwareRequirement.isNewerThanBase("2.10.0.0"))
        assertTrue(!FirmwareRequirement.isNewerThanBase("2.3.0.24"))
        assertTrue(!FirmwareRequirement.isNewerThanBase("2.2.9.22"))
        assertTrue(!FirmwareRequirement.isNewerThanBase(""))
    }

    @Test
    fun `older Faceclaw revisions and legacy builds are refused`() {
        val older = check("Faceclaw/${required - 1}")
        assertEquals(FirmwareKind.OLDER_FACECLAW, older.kind)
        val message = older.message.orEmpty()
        assertTrue(message.contains("Revision ${required - 1}"))
        assertTrue(message.contains("Revision $required"))

        val legacy = check("EVENCFW/22 img640 fbguard wearnotify")
        assertEquals(FirmwareKind.OLDER_FACECLAW, legacy.kind)
        assertTrue(legacy.message.orEmpty().contains("ältere Faceclaw-Firmware"))
    }

    @Test
    fun `custom firmware from another source is refused`() {
        val other = check("OtherCFW/3")
        assertEquals(FirmwareKind.OTHER_CUSTOM, other.kind)
        val message = other.message.orEmpty()
        assertTrue(message.contains("anderen Quelle"))
        assertTrue(message.contains("OtherCFW/3"))
    }

    @Test
    fun `stock firmware is refused and nothing reported is unknown`() {
        val stock = check("")
        assertEquals(FirmwareKind.STOCK, stock.kind)
        assertTrue(stock.message.orEmpty().contains("Original-Firmware"))
        assertEquals("Original 2.2.9.22", stock.summary)

        // Faceclaw shows no warning for this case, but the watch app needs positive proof.
        val nothing = check("", "", "")
        assertEquals(FirmwareKind.UNKNOWN, nothing.kind)
        assertTrue(!nothing.compatible)
    }

    @Test
    fun `reported version is the higher arm`() {
        assertEquals("2.2.10.10", FirmwareRequirement.reportedVersion("2.2.9.22", "2.2.10.10"))
        assertEquals("2.2.9.22", FirmwareRequirement.reportedVersion("", "2.2.9.22"))
        assertEquals("2.2.9.22", FirmwareRequirement.reportedVersion("2.2.9.22", null))
        assertEquals("", FirmwareRequirement.reportedVersion("", ""))
    }

    @Test
    fun `name and the version of each temple, for the watch screen`() {
        val custom = check("Faceclaw/$required", "2.3.0.24", "2.3.0.24")
        assertEquals("Faceclaw/$required", custom.name)
        assertEquals("Links 2.3.0.24 · Rechts 2.3.0.24", custom.temples)
        // A mixed state after an interrupted transfer shows as such.
        assertEquals("Links 2.3.0.24 · Rechts ?", check("Faceclaw/$required", "2.3.0.24", "").temples)
        assertEquals("Original", check("").name)
        assertEquals("unbekannt", check("", "", "").name)
    }

    @Test
    fun `summary names revision and base`() {
        assertEquals("Faceclaw/$required · Basis 2.3.0.24", check("Faceclaw/$required", "2.3.0.24", "2.3.0.24").summary)
    }
}

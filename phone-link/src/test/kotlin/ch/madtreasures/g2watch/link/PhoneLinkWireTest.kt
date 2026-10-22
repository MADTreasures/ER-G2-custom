package ch.madtreasures.g2watch.link

import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneLinkWireTest {

    @Test
    fun `paths name the partner and the type`() {
        assertEquals("/g2/link/ch.madtreasures.fluency/open", PhoneLinkWire.path("ch.madtreasures.fluency", Type.OPEN))
        for (type in Type.entries) {
            assertEquals("ch.madtreasures.fluency" to type, PhoneLinkWire.parse(PhoneLinkWire.path("ch.madtreasures.fluency", type)))
        }
    }

    @Test
    fun `other paths are no link paths`() {
        assertNull(PhoneLinkWire.parse("/g2/evenhub"))
        assertNull(PhoneLinkWire.parse("/g2/link/"))
        assertNull(PhoneLinkWire.parse("/g2/link/ch.madtreasures.fluency"))
        assertNull(PhoneLinkWire.parse("/g2/link/ch.madtreasures.fluency/later"))
        assertNull(PhoneLinkWire.parse("/g2/link/fluency/open")) // a package name has a dot
        assertNull(PhoneLinkWire.parse("/g2/link/../open"))
        assertNull(PhoneLinkWire.parse("/g2/link/ch.madtreasures.fluency/open/more"))
    }

    @Test
    fun `only package names are partners`() {
        assertTrue(PhoneLinkWire.isPackageName("ch.madtreasures.fluency"))
        assertTrue(PhoneLinkWire.isPackageName("com.example.app_2"))
        assertFalse(PhoneLinkWire.isPackageName("fluency"))
        assertFalse(PhoneLinkWire.isPackageName("ch..fluency"))
        assertFalse(PhoneLinkWire.isPackageName("ch.madtreasures/fluency"))
        assertFalse(PhoneLinkWire.isPackageName("1ch.fluency"))
        assertFailsWith<IllegalArgumentException> { PhoneLinkWire.path("../etc", Type.OPEN) }
    }

    @Test
    fun `open carries the app and its version`() {
        val opener = Opener("ch.madtreasures.fluency", "1.1.0")
        assertEquals(opener, PhoneLinkWire.readOpen(PhoneLinkWire.open(opener)))
        assertEquals(Opener("x.y", ""), PhoneLinkWire.readOpen("""{"app":"x.y","later":1}""".encodeToByteArray()))
        assertNull(PhoneLinkWire.readOpen(ByteArray(0)))
        assertNull(PhoneLinkWire.readOpen("""{"version":"1.0.0"}""".encodeToByteArray()))
        assertNull(PhoneLinkWire.readOpen("kein JSON".encodeToByteArray()))
    }

    @Test
    fun `a reason may be missing`() {
        assertEquals("Fluency ist auf dem Handy nicht installiert", PhoneLinkWire.readReason(PhoneLinkWire.reason("Fluency ist auf dem Handy nicht installiert")))
        assertContentEquals(ByteArray(0), PhoneLinkWire.reason(null))
        assertContentEquals(ByteArray(0), PhoneLinkWire.reason(" "))
        assertNull(PhoneLinkWire.readReason(ByteArray(0)))
        assertNull(PhoneLinkWire.readReason("""{"message":""}""".encodeToByteArray()))
    }

    @Test
    fun `text goes through unchanged`() {
        val text = """{"t":"utt","text":"Grüezi 你好 😊 \" \\"}"""
        assertEquals(text, PhoneLinkWire.readText(PhoneLinkWire.text(text)))
    }
}

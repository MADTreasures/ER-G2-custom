package ch.madtreasures.g2watch.firmware

import com.faceclaw.app.BleProtocol
import com.faceclaw.app.GattWriteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedStockLinkTest {
    private val glasses = SimulatedGlasses()

    private fun frames(size: Int) = listOf(ByteArray(size))

    private fun GuardedStockLink.ota(size: Int = 240) =
        writeFrames(RIGHT, BleProtocol.OTA_DATA_WRITE_UUID, frames(size), GattWriteMode.WITHOUT_RESPONSE, 100)

    private fun GuardedStockLink.control(size: Int = 240) =
        writeFrames(RIGHT, BleProtocol.WRITE_CHAR_UUID, frames(size), GattWriteMode.WITHOUT_RESPONSE, 100)

    private fun connected(mtu: Int): GuardedStockLink {
        glasses.mtu = mtu
        return glasses.guardedLink().also { it.connect(RIGHT, 100) }
    }

    @Test
    fun `an unarmed link refuses firmware writes but passes everything else`() {
        val link = connected(247)
        assertFalse(link.isArmed)
        assertFalse(link.ota())
        assertEquals(1, link.refusedWriteCount)
        assertEquals(0, glasses.otaWrites.size)
        assertTrue(link.control())
        assertEquals(1, glasses.writes.size)
    }

    @Test
    fun `an armed link passes firmware frames that fit the MTU`() {
        val link = connected(247)
        link.arm()
        assertTrue(link.ota(240))
        assertEquals(1, link.otaWriteCount)
        assertEquals(1, glasses.otaWrites.size)
    }

    @Test
    fun `a narrow MTU or an oversized frame is refused even when armed`() {
        val narrow = connected(185)
        narrow.arm()
        assertFalse(narrow.ota(100))
        assertEquals(0, glasses.otaWrites.size)

        val wide = connected(243)
        wide.arm()
        assertTrue(wide.ota(240))
        assertFalse(wide.ota(241))
        assertEquals(1, wide.refusedWriteCount)
    }

    @Test
    fun `closing or disarming ends the permission`() {
        val link = connected(247)
        link.arm()
        link.disarm()
        assertFalse(link.ota())
        link.arm()
        link.close()
        assertFalse(link.isArmed)
    }

    @Test
    fun `an armed link drops a narrow connection right after the MTU exchange`() {
        val link = connected(185)
        link.arm()
        link.prepareLink(RIGHT, 512, 100)
        assertFalse(link.isConnected(RIGHT))
        assertEquals(1, link.mtuRefusalCount)

        val unarmed = connected(185)
        unarmed.prepareLink(RIGHT, 512, 100)
        assertTrue(unarmed.isConnected(RIGHT))
    }

    @Test
    fun `a write on a lost link is not blamed on the MTU`() {
        val link = connected(247)
        link.arm()
        link.disconnect(RIGHT)
        try {
            link.ota()
        } catch (e: IllegalStateException) {
            // the link says "not connected", as without the guard
        }
        assertEquals(0, link.mtuRefusalCount)
    }

    @Test
    fun `the MTU at bring-up is kept, and a lost link or a default 23 is not called narrow`() {
        val link = connected(247)
        link.prepareLink(RIGHT, 512, 100)
        assertEquals(247, link.preparedMtu(RIGHT))

        val default = connected(23)
        default.arm()
        default.prepareLink(RIGHT, 512, 100)
        assertFalse(default.isConnected(RIGHT))
        assertFalse(default.mtuTooNarrow)

        val narrow = connected(185)
        narrow.arm()
        narrow.prepareLink(RIGHT, 512, 100)
        assertTrue(narrow.mtuTooNarrow)
        // A new connection starts the verdict afresh.
        narrow.connect(RIGHT, 100)
        assertFalse(narrow.mtuTooNarrow)
    }

    @Test
    fun `the UUID check ignores case`() {
        val link = connected(247)
        assertFalse(link.writeFrames(RIGHT, BleProtocol.OTA_DATA_WRITE_UUID.uppercase(), frames(20), GattWriteMode.WITHOUT_RESPONSE, 100))
        assertEquals(0, glasses.writes.size)
    }
}

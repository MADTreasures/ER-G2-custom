package ch.madtreasures.g2watch.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class StockImageStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val image = TestFirmware.stock
    private val expected = StockImageStore.Expected(
        url = "https://example.invalid/stock.bin",
        size = image.size,
        sha256 = TestFirmware.stockSha,
        fileName = "stock.bin",
    )

    private var downloads = 0
    private var served: () -> ByteArray = { image }

    private fun store(cache: File = tmp.newFolder("cache"), import: File? = tmp.newFolder("import")) =
        StockImageStore(cache, import, { url, max, progress ->
            downloads++
            assertEquals(expected.url, url)
            assertEquals(image.size, max)
            served().also { progress(it.size.toLong(), it.size.toLong()) }
        }, expected = expected)

    @Test
    fun `downloads once, verifies, and serves the cache afterwards`() {
        val cache = tmp.newFolder("c")
        val s = store(cache)
        assertArrayEquals(image, s.load { _, _ -> })
        assertEquals(1, downloads)
        assertTrue(File(cache, "stock.bin").isFile)
        assertFalse(File(cache, "stock.bin.part").exists())
        assertArrayEquals(image, s.load { _, _ -> })
        assertEquals(1, downloads)
    }

    @Test
    fun `a damaged cache is replaced by a fresh download`() {
        val cache = tmp.newFolder("c")
        File(cache, "stock.bin").writeBytes(image.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertArrayEquals(image, store(cache).load { _, _ -> })
        assertEquals(1, downloads)
        assertArrayEquals(image, File(cache, "stock.bin").readBytes())
    }

    @Test
    fun `a pushed file is used when it is the right image, anything else is ignored`() {
        val import = tmp.newFolder("i")
        File(import, "a-wrong.bin").writeBytes(ByteArray(image.size))
        File(import, "b-right.bin").writeBytes(image)
        File(import, "c-notes.txt").writeText("x")
        val cache = tmp.newFolder("c")
        assertArrayEquals(image, store(cache, import).load { _, _ -> })
        assertEquals(0, downloads)
        assertTrue(File(cache, "stock.bin").isFile)
    }

    @Test
    fun `a download that is not the image is rejected and not cached`() {
        val cache = tmp.newFolder("c")
        served = { image.copyOf().also { it[0] = 0 } }
        try {
            store(cache).load { _, _ -> }
            fail("accepted a wrong image")
        } catch (e: FirmwareBuildException) {
            assertTrue(e.message!!.contains("nicht Evens Firmware"))
            assertTrue(e.message!!.contains("Nichts wurde an der Brille verändert"))
        }
        assertFalse(File(cache, "stock.bin").exists())
    }

    @Test
    fun `no network is explained`() {
        served = { throw IOException("Unable to resolve host") }
        try {
            store().load { _, _ -> }
            fail("no exception")
        } catch (e: FirmwareBuildException) {
            assertTrue(e.message!!.contains("WLAN"))
        }
    }

    @Test
    fun `a download is capped at the expected size`() {
        val big = ByteArray(1000)
        try {
            StockImageStore.readCapped(ByteArrayInputStream(big), 999, 999) { _, _ -> }
            fail("read past the cap")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("larger"))
        }
        assertEquals(1000, StockImageStore.readCapped(ByteArrayInputStream(big), 1000, 1000) { _, _ -> }.size)
        assertEquals(10, StockImageStore.readCapped(ByteArrayInputStream(ByteArray(10)), 1000, 1000) { _, _ -> }.size)
    }
}

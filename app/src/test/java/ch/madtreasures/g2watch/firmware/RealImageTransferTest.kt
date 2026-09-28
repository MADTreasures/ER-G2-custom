package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The real thing, minus the glasses: Even's stock image is patched into Faceclaw/35 exactly as on
 * the watch, and Faceclaw's flasher streams all of it (about 1,130 blocks per lens) to simulated
 * lenses, which must end up with exactly the custom image's components. Opt-in, because Even's
 * image is never part of this repository:
 * `G2_STOCK_IMAGE=/path/to/g2_2.3.0.24.bin ./gradlew :app:testDebugUnitTest --tests '*RealImageTransferTest*'`.
 */
class RealImageTransferTest {
    private val path = System.getenv("G2_STOCK_IMAGE")

    @Test
    fun `the real custom image reaches both lenses bit for bit`() {
        assumeTrue("G2_STOCK_IMAGE not set", path != null && File(path).isFile)
        val stock = File(path!!).readBytes()
        val glasses = SimulatedGlasses(componentCount = 6)
        val env = object : FirmwareEnvironment by FakeFirmwareEnvironment(glasses) {
            override val stock = StockImageSource { _ -> stock }
            override val images = FirmwareImages.Catalog
        }
        val result = FirmwareJob(
            FirmwareTarget.CUSTOM,
            pair = LensPair(RIGHT, LEFT),
            env = env,
            report = {},
            log = {},
            policy = JobPolicy(settleMs = 0, verifyDelayMs = 0, verifyAttempts = 1, verifyIntervalMs = 0),
        ).run()
        assertTrue(result.toString(), result is FirmwareInstall.Done)
        val custom = FirmwareCatalog.prepare(FirmwareKind.Custom, stock)
        val expected = custom.components.map { custom.payload(it) }
        for (lens in listOf(LEFT, RIGHT)) {
            val got = glasses.received[lens]!!
            assertEquals(6, got.size)
            expected.zip(got).forEach { (e, g) -> assertTrue(e.contentEquals(g)) }
        }
        assertEquals(FirmwareCatalog.CUSTOM_SHA256, custom.sha256)
    }
}

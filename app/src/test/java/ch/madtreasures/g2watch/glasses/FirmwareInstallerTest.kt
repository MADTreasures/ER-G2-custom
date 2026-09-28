package ch.madtreasures.g2watch.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareInstallerTest {
    @Test
    fun `transfer stats keep the last thirty frames`() {
        assertNull(TransferStats.of(emptyList()))
        val stats = TransferStats.of((1..40).toList())!!
        assertEquals((11..40).toList(), stats.recent)
        assertEquals(40, stats.lastMs)
        assertEquals(11, stats.minMs)
        assertEquals(40, stats.maxMs)
        assertEquals(26, stats.avgMs) // 25.5, rounded
    }

    @Test
    fun `the app's own installer offers nothing and sends nothing`() {
        val installer = NotSetUpInstaller()
        for (target in FirmwareTarget.entries) assertNull(installer.describe(target))
        assertEquals(FirmwareInstall.Idle, installer.progress.value)
        installer.install(FirmwareTarget.CUSTOM)
        val result = installer.progress.value
        assertTrue(result is FirmwareInstall.Unavailable)
        assertEquals(FirmwareTarget.CUSTOM, (result as FirmwareInstall.Unavailable).target)
        installer.dismiss()
        assertEquals(FirmwareInstall.Idle, installer.progress.value)
        installer.testRun(FirmwareTarget.ORIGINAL)
        assertTrue(installer.progress.value is FirmwareInstall.Unavailable)
    }

    @Test
    fun `a test run has its own title`() {
        assertEquals("Testlauf", FirmwareInstall.Running(FirmwareTarget.CUSTOM, "x", null, testRun = true).title)
        assertEquals("Custom-Firmware", FirmwareInstall.Running(FirmwareTarget.CUSTOM, "x", null).title)
        assertEquals("Original-Firmware", FirmwareInstall.Failed(FirmwareTarget.ORIGINAL, "x").title)
    }
}

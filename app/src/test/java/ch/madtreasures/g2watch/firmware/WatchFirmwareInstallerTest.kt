package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFirmwareInstallerTest {
    private val worker = FakeScheduler()
    private val env = FakeFirmwareEnvironment()
    private val log = ArrayList<String>()
    private var pair: LensPair? = LensPair(RIGHT, LEFT)
    private val installer = WatchFirmwareInstaller(
        env,
        { pair },
        worker,
        { log += it },
        JobPolicy(settleMs = 0, verifyDelayMs = 0, verifyAttempts = 1, verifyIntervalMs = 0),
    )

    @Test
    fun `describes both targets from the catalog`() {
        assertEquals("Even Realities 2.3.0.24", installer.describe(FirmwareTarget.ORIGINAL))
        assertEquals("Faceclaw/35 · Basis 2.3.0.24", installer.describe(FirmwareTarget.CUSTOM))
    }

    @Test
    fun `nothing happens on its own`() {
        worker.advanceBy(60_000)
        assertEquals(FirmwareInstall.Idle, installer.progress.value)
        assertEquals(0, env.glasses.writes.size)
    }

    @Test
    fun `with chosen glasses nothing stands in the way of the confirm page`() {
        assertEquals(null, installer.blocker(FirmwareTarget.CUSTOM))
        assertEquals(null, installer.blocker(FirmwareTarget.ORIGINAL))
    }

    @Test
    fun `runs on the worker and ends with the job's result`() {
        val before = env.glasses.writes.size
        installer.install(FirmwareTarget.CUSTOM)
        assertTrue(installer.progress.value is FirmwareInstall.Running)
        assertEquals(before, env.glasses.writes.size) // not yet: the worker has not run
        worker.runPending()
        val result = installer.progress.value
        assertTrue(result.toString(), result is FirmwareInstall.Done)
        assertTrue(log.any { it.contains("gestartet") })
        installer.dismiss()
        assertEquals(FirmwareInstall.Idle, installer.progress.value)
    }

    @Test
    fun `a second start while one runs is ignored, and dismiss does not stop it`() {
        installer.install(FirmwareTarget.CUSTOM)
        installer.install(FirmwareTarget.ORIGINAL)
        installer.dismiss()
        assertTrue(installer.progress.value is FirmwareInstall.Running)
        worker.runPending()
        val result = installer.progress.value as FirmwareInstall.Done
        assertEquals(FirmwareTarget.CUSTOM, result.target)
        assertTrue(log.any { it.contains("zweiter Start ignoriert") })
    }

    @Test
    fun `a transfer cut short by the death of the app is reported once`() {
        env.notice = FirmwareTarget.CUSTOM to FirmwareJob.INTERRUPTED
        val restarted = WatchFirmwareInstaller(env, { pair }, worker, { log += it })
        val shown = restarted.progress.value
        assertTrue(shown is FirmwareInstall.Failed && shown.message == FirmwareJob.INTERRUPTED)
        assertTrue(FirmwareJob.INTERRUPTED.contains("unklar"))
        restarted.dismiss()
        assertEquals(FirmwareInstall.Idle, restarted.progress.value)
        assertEquals(null, env.notice)
    }

    @Test
    fun `without chosen glasses it says so`() {
        pair = null
        installer.install(FirmwareTarget.CUSTOM)
        val result = installer.progress.value
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue((result as FirmwareInstall.Failed).message.contains("Noch keine Brille"))
        assertEquals(WatchFirmwareInstaller.NO_GLASSES, installer.blocker(FirmwareTarget.CUSTOM))
        worker.runPending()
        assertEquals(0, env.glasses.writes.size)
    }
}

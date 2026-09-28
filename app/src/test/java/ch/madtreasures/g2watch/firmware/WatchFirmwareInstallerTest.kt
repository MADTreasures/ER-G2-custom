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
    private val passed = HashSet<LensPair>()
    private val record = object : TestRunRecord {
        override fun passed(pair: LensPair) = pair in passed

        override fun record(pair: LensPair) {
            passed += pair
        }
    }
    private val installer = WatchFirmwareInstaller(
        env,
        { pair },
        worker,
        { log += it },
        record,
        JobPolicy(settleMs = 0, verifyDelayMs = 0, verifyAttempts = 1, verifyIntervalMs = 0),
    )

    /** What a first successful test run leaves behind. */
    private fun testRunPassed() {
        installer.testRun(FirmwareTarget.CUSTOM)
        worker.runPending()
        installer.dismiss()
    }

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
    fun `the first real transfer needs a passed test run for these glasses`() {
        assertEquals(WatchFirmwareInstaller.NEEDS_TEST_RUN, installer.blocker(FirmwareTarget.CUSTOM))
        installer.install(FirmwareTarget.CUSTOM)
        val refused = installer.progress.value
        assertTrue(refused is FirmwareInstall.Failed && refused.message == WatchFirmwareInstaller.NEEDS_TEST_RUN)
        worker.runPending()
        assertEquals(0, env.glasses.writes.size)

        testRunPassed()
        assertTrue(LensPair(RIGHT, LEFT) in passed)
        assertEquals(null, installer.blocker(FirmwareTarget.CUSTOM))
        assertEquals(null, installer.blocker(FirmwareTarget.ORIGINAL))
        // Other glasses need their own.
        pair = LensPair("BB:00:00:00:00:01", "BB:00:00:00:00:02")
        assertEquals(WatchFirmwareInstaller.NEEDS_TEST_RUN, installer.blocker(FirmwareTarget.CUSTOM))
    }

    @Test
    fun `a failed test run does not count`() {
        env.glasses.mtu = 185
        installer.testRun(FirmwareTarget.CUSTOM)
        worker.runPending()
        assertTrue(installer.progress.value is FirmwareInstall.Failed)
        assertTrue(passed.isEmpty())
    }

    @Test
    fun `runs on the worker and ends with the job's result`() {
        testRunPassed()
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
        testRunPassed()
        installer.install(FirmwareTarget.CUSTOM)
        installer.install(FirmwareTarget.ORIGINAL)
        installer.testRun(FirmwareTarget.CUSTOM)
        installer.dismiss()
        assertTrue(installer.progress.value is FirmwareInstall.Running)
        worker.runPending()
        val result = installer.progress.value as FirmwareInstall.Done
        assertEquals(FirmwareTarget.CUSTOM, result.target)
        assertTrue(!result.testRun)
        assertTrue(log.any { it.contains("zweiter Start ignoriert") })
    }

    @Test
    fun `a transfer cut short by the death of the app is reported once`() {
        env.notice = FirmwareTarget.CUSTOM to FirmwareJob.INTERRUPTED
        val restarted = WatchFirmwareInstaller(env, { pair }, worker, { log += it }, record)
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

    @Test
    fun `a test run is marked as one`() {
        installer.testRun(FirmwareTarget.CUSTOM)
        assertTrue((installer.progress.value as FirmwareInstall.Running).testRun)
        worker.runPending()
        val result = installer.progress.value
        assertTrue(result.toString(), result is FirmwareInstall.Done && result.testRun)
        assertEquals(0, env.glasses.otaWrites.size)
    }
}

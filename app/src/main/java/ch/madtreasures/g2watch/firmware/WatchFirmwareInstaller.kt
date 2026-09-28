package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareInstaller
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Remembers for which glasses a test run passed. */
interface TestRunRecord {
    fun passed(pair: LensPair): Boolean

    fun record(pair: LensPair)
}

/**
 * The watch's real [FirmwareInstaller]: runs one [FirmwareJob] at a time on [worker].
 *
 * It never starts on its own. [install] and [testRun] are called only from the confirm page
 * (two-second hold) and the test-run button; a second call while a job runs is ignored, and there
 * is deliberately no cancel: stopping in the middle of a component is the one thing worse than
 * finishing it. [dismiss] clears a finished result.
 *
 * Before the first real transfer to a pair of glasses a test run must have passed for them
 * ([testRuns]): it proves on this very watch that both lenses pair, report their batteries and
 * negotiate a link wide enough, without writing anything (the handover's "Testlauf zuerst").
 */
class WatchFirmwareInstaller(
    private val env: FirmwareEnvironment,
    /** The glasses to work on, or null when none were chosen yet. */
    private val pair: () -> LensPair?,
    private val worker: Scheduler,
    private val log: (String) -> Unit,
    private val testRuns: TestRunRecord,
    private val policy: JobPolicy = JobPolicy(),
) : FirmwareInstaller {

    private val _progress = MutableStateFlow<FirmwareInstall>(FirmwareInstall.Idle)
    override val progress: StateFlow<FirmwareInstall> = _progress.asStateFlow()

    private val lock = Any()

    override fun describe(target: FirmwareTarget): String = env.images.describe(kindOf(target))

    override fun blocker(target: FirmwareTarget): String? {
        val lenses = pair() ?: return NO_GLASSES
        if (!testRuns.passed(lenses)) return NEEDS_TEST_RUN
        return null
    }

    override fun install(target: FirmwareTarget) = start(target, testRun = false)

    override fun testRun(target: FirmwareTarget) = start(target, testRun = true)

    override fun dismiss() {
        _progress.update { if (it is FirmwareInstall.Running) it else FirmwareInstall.Idle }
    }

    private fun start(target: FirmwareTarget, testRun: Boolean) {
        synchronized(lock) {
            if (_progress.value is FirmwareInstall.Running) {
                log("Firmware: läuft schon, zweiter Start ignoriert")
                return
            }
            val lenses = pair()
            if (lenses == null) {
                _progress.value = FirmwareInstall.Failed(target, NO_GLASSES, testRun)
                return
            }
            if (!testRun && !testRuns.passed(lenses)) {
                _progress.value = FirmwareInstall.Failed(target, NEEDS_TEST_RUN, testRun = false)
                return
            }
            _progress.value = FirmwareInstall.Running(target, "Starte …", null, testRun)
            log("Firmware: ${if (testRun) "Testlauf" else "Aufspielen"} ${target.label} (${env.images.describe(kindOf(target))}) gestartet")
            worker.post {
                val result = try {
                    FirmwareJob(
                        target = target,
                        testRun = testRun,
                        pair = lenses,
                        env = env,
                        report = { running -> _progress.value = running },
                        log = { line -> log("Firmware: $line") },
                        policy = policy,
                    ).run()
                } catch (t: Throwable) {
                    FirmwareInstall.Failed(target, "Unerwarteter Fehler: ${t.message ?: t.javaClass.simpleName}", testRun)
                }
                if (testRun && result is FirmwareInstall.Done) testRuns.record(lenses)
                _progress.value = result
                log("Firmware: ${summary(result)}")
            }
        }
    }

    private fun summary(result: FirmwareInstall): String = when (result) {
        is FirmwareInstall.Done -> "fertig – ${result.message}"
        is FirmwareInstall.Failed -> "FEHLER – ${result.message}"
        else -> result.toString()
    }

    companion object {
        const val NO_GLASSES = "Noch keine Brille gewählt. Zuerst die Brille suchen und verbinden."
        const val NEEDS_TEST_RUN =
            "Zuerst einmal den Testlauf machen (Einstellungen → Testlauf). Er prüft alles und schreibt nichts auf die Brille."
    }

    private fun kindOf(target: FirmwareTarget) = when (target) {
        FirmwareTarget.ORIGINAL -> FirmwareKind.Stock
        FirmwareTarget.CUSTOM -> FirmwareKind.Custom
    }
}

package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareInstaller
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's real [FirmwareInstaller]: runs one [FirmwareJob] at a time on [worker].
 *
 * It never starts on its own. [install] is called only from the confirm page (two-second hold); a
 * second call while a job runs is ignored, and there is deliberately no cancel: stopping in the
 * middle of a component is the one thing worse than finishing it. [dismiss] clears a finished
 * result.
 */
class WatchFirmwareInstaller(
    private val env: FirmwareEnvironment,
    /** The glasses to work on, or null when none were chosen yet. */
    private val pair: () -> LensPair?,
    private val worker: Scheduler,
    private val log: (String) -> Unit,
    private val policy: JobPolicy = JobPolicy(),
) : FirmwareInstaller {

    private val _progress = MutableStateFlow<FirmwareInstall>(FirmwareInstall.Idle)
    override val progress: StateFlow<FirmwareInstall> = _progress.asStateFlow()

    private val lock = Any()

    init {
        // The app was killed while firmware was on its way: say so instead of "nothing happened".
        env.savedNotice()?.let { (target, message) ->
            _progress.value = FirmwareInstall.Failed(target, message)
        }
    }

    override fun describe(target: FirmwareTarget): String = env.images.describe(kindOf(target))

    override fun blocker(target: FirmwareTarget): String? = if (pair() == null) NO_GLASSES else null

    override fun dismiss() {
        synchronized(lock) {
            if (_progress.value is FirmwareInstall.Running) return
            _progress.value = FirmwareInstall.Idle
            // Seen: an interrupted transfer from before is reported once, not on every start.
            env.clearNotice()
        }
    }

    override fun install(target: FirmwareTarget) {
        synchronized(lock) {
            if (_progress.value is FirmwareInstall.Running) {
                log("Firmware: läuft schon, zweiter Start ignoriert")
                return
            }
            val lenses = pair()
            if (lenses == null) {
                _progress.value = FirmwareInstall.Failed(target, NO_GLASSES)
                return
            }
            _progress.value = FirmwareInstall.Running(target, "Starte …", null)
            log("Firmware: Aufspielen ${target.label} (${env.images.describe(kindOf(target))}) gestartet")
            worker.post {
                val result = try {
                    FirmwareJob(
                        target = target,
                        pair = lenses,
                        env = env,
                        report = { running -> _progress.value = running },
                        log = { line -> log("Firmware: $line") },
                        policy = policy,
                    ).run()
                } catch (t: Throwable) {
                    FirmwareInstall.Failed(target, "Unerwarteter Fehler: ${t.message ?: t.javaClass.simpleName}")
                }
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
    }

    private fun kindOf(target: FirmwareTarget) = when (target) {
        FirmwareTarget.ORIGINAL -> FirmwareKind.Stock
        FirmwareTarget.CUSTOM -> FirmwareKind.Custom
    }
}

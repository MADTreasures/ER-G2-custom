package ch.madtreasures.g2watch.glasses

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which firmware the settings offer to put on the glasses. */
enum class FirmwareTarget(val label: String) {
    ORIGINAL("Original-Firmware"),
    CUSTOM("Custom-Firmware"),
}

/**
 * Where a firmware transfer stands, for the watch UI. [testRun] marks a test run: it prepares the
 * image and connects to both lenses the way a transfer does, but writes no firmware.
 */
sealed interface FirmwareInstall {
    data object Idle : FirmwareInstall

    data class Running(
        val target: FirmwareTarget,
        val step: String,
        val percent: Int?,
        val testRun: Boolean = false,
    ) : FirmwareInstall

    data class Done(val target: FirmwareTarget, val message: String, val testRun: Boolean = false) : FirmwareInstall

    data class Failed(val target: FirmwareTarget, val message: String, val testRun: Boolean = false) : FirmwareInstall

    /** Nothing on this watch is set up to install the target. */
    data class Unavailable(val target: FirmwareTarget, val message: String) : FirmwareInstall
}

/** The heading of the progress page: the target, or the test run of it. */
val FirmwareInstall.title: String
    get() = when (this) {
        is FirmwareInstall.Running -> if (testRun) "Testlauf" else target.label
        is FirmwareInstall.Done -> if (testRun) "Testlauf" else target.label
        is FirmwareInstall.Failed -> if (testRun) "Testlauf" else target.label
        is FirmwareInstall.Unavailable -> target.label
        FirmwareInstall.Idle -> "Firmware"
    }

/**
 * The boundary between the watch UI and whatever actually transfers firmware to the glasses.
 * The settings call [install] only after the wearer held the confirm button; nothing in the app
 * calls it on its own. The real transfer is [ch.madtreasures.g2watch.firmware.WatchFirmwareInstaller].
 */
interface FirmwareInstaller {
    /** What [target] would put on the glasses, e.g. a version; null if nothing is set up. */
    fun describe(target: FirmwareTarget): String?

    val progress: StateFlow<FirmwareInstall>

    /**
     * Why [install] would refuse [target] right now (e.g. no test run yet), in German; null when
     * nothing stands in the way. The confirm page offers the hold button only for null.
     */
    fun blocker(target: FirmwareTarget): String?

    fun install(target: FirmwareTarget)

    /**
     * Everything [install] does up to the first firmware byte, then stops: prepares and checks the
     * image for [target], connects and pairs both lenses over the update channel and checks that
     * the watch's Bluetooth link can carry the transfer. Writes nothing to the glasses.
     */
    fun testRun(target: FirmwareTarget)

    /** Back to [FirmwareInstall.Idle] once a transfer is over; a running one goes on. */
    fun dismiss()
}

/** Offers nothing and transfers nothing; says so when asked. */
class NotSetUpInstaller : FirmwareInstaller {
    private val _progress = MutableStateFlow<FirmwareInstall>(FirmwareInstall.Idle)
    override val progress: StateFlow<FirmwareInstall> = _progress.asStateFlow()

    override fun describe(target: FirmwareTarget): String? = null

    override fun blocker(target: FirmwareTarget): String = "In dieser App ist kein Weg zum Aufspielen eingerichtet."

    override fun install(target: FirmwareTarget) {
        _progress.value = FirmwareInstall.Unavailable(
            target,
            "In dieser App ist kein Weg zum Aufspielen eingerichtet.",
        )
    }

    override fun testRun(target: FirmwareTarget) = install(target)

    override fun dismiss() {
        if (_progress.value !is FirmwareInstall.Running) _progress.value = FirmwareInstall.Idle
    }
}

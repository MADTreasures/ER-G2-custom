package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.glasses.FaceclawMessages
import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareKind as GlassesFirmwareKind
import ch.madtreasures.g2watch.glasses.FirmwareRequirement
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import ch.madtreasures.g2watch.glasses.FirmwareVerdict
import com.faceclaw.app.BleProtocol
import com.faceclaw.app.DeviceInfoProbeFlow
import com.faceclaw.app.FaceclawDeviceInfoProbeListener
import com.faceclaw.app.FaceclawFirmwareFlasherListener
import com.faceclaw.app.FaceclawFlashPromptListener
import com.faceclaw.app.FlashPromptFlow
import com.faceclaw.app.OtaFlashFlow
import com.faceclaw.app.ProtocolPlatform
import com.faceclaw.app.StockFlowTimings
import com.faceclaw.app.StockLinkSession

/** How a job turns the stock image into the image to flash, and which images may be flashed at all. */
interface FirmwareImages {
    fun prepare(kind: FirmwareKind, stock: ByteArray, onPatch: (applied: Int, total: Int) -> Unit): EvenOtaImage

    /** The allow-list: which firmware an image with [sha256] is, or null for anything else. */
    fun kindOf(sha256: String): FirmwareKind?

    fun describe(kind: FirmwareKind): String

    /** The real one: Even's 2.3.0.24 and Faceclaw/35 built from it, both pinned by SHA-256. */
    object Catalog : FirmwareImages {
        override fun prepare(kind: FirmwareKind, stock: ByteArray, onPatch: (applied: Int, total: Int) -> Unit) =
            FirmwareCatalog.prepare(kind, stock, onPatch)

        override fun kindOf(sha256: String) = FirmwareCatalog.kindOf(sha256)

        override fun describe(kind: FirmwareKind) = FirmwareCatalog.describe(kind)
    }
}

/** What a [FirmwareJob] needs from the watch: Android in the app, fakes in tests. */
interface FirmwareEnvironment {
    val stock: StockImageSource
    val images: FirmwareImages
    val timings: StockFlowTimings
    val platform: ProtocolPlatform

    /** A fresh link for one flow; every flow closes its own. */
    fun openLink(log: (String) -> Unit): GuardedStockLink

    fun watchPower(): WatchPower

    /** Ends the app's own probe and session and returns once their links are closed. Blocks. */
    fun releaseGlasses()

    /** Keeps the watch awake (foreground service, wake lock) and shows [text] in its notification. */
    fun keepAwake(text: String)

    /** Undoes [keepAwake]. */
    fun allowSleep()

    /**
     * A message about the glasses that must survive the death of the app's process, e.g. "the
     * transfer was interrupted", until the wearer has seen it. Written to disk at once.
     */
    fun saveNotice(target: FirmwareTarget, message: String)

    fun clearNotice()

    /** The saved message, if the wearer has not confirmed it yet. */
    fun savedNotice(): Pair<FirmwareTarget, String>?

    fun sleep(ms: Long)
}

data class WatchPower(val percent: Int?, val charging: Boolean)

/** The glasses pair a job works on. Both arms are needed: each lens is flashed on its own. */
data class LensPair(val right: String, val left: String)

/** Limits and waits of a job; tests shrink the waits. */
data class JobPolicy(
    /**
     * Per lens; an unreadable level counts as too low. Faceclaw asks 30 %, Even's own updates more
     * than 50 %: the watch path is untested, so the stricter one.
     */
    val minGlassesBattery: Int = 50,
    /** Below this the watch must be on its charger. */
    val minWatchBattery: Int = 50,
    /** Between the flows, so the previous links are really closed. */
    val settleMs: Long = 2_000,
    /** After the transfer, before the first check: both lenses reboot. */
    val verifyDelayMs: Long = 15_000,
    val verifyAttempts: Int = 6,
    val verifyIntervalMs: Long = 15_000,
)

/**
 * One firmware transfer from start to end. [run] blocks and must run on a worker thread; it
 * reports every step through [report] and returns the final state.
 *
 * The order is chosen so that anything that can fail without the glasses fails first, and the
 * glasses see firmware only after two confirmations:
 *
 * 1. Checks on the watch: both arms known, watch battery.
 * 2. The image: stock from cache, import or Even's CDN (SHA-256), for Custom patched with g2flash's
 *    set (SHA-256 of the result), fully validated ([FirmwareCatalog.prepare]).
 * 3. The app's own connection lets go of the glasses.
 * 4. Faceclaw's read-only probe reads what is on the glasses. A stock version newer than the base
 *    of our images stops here (that would be a downgrade nobody has tested).
 * 5. Faceclaw's [FlashPromptFlow] pairs both arms, asks on the glasses themselves ("Yes, flash"),
 *    refuses in silent mode and reads both batteries; below [JobPolicy.minGlassesBattery] or
 *    unreadable stops here.
 * 6. The image is checked against the allow-list once more, the link is armed, and Faceclaw's
 *    [OtaFlashFlow] (a port of g2flash.py) writes left, then right. No retry of the whole
 *    transfer: a failure stops and says what state the lenses are in.
 * 7. After the reboot each lens is asked on its own link, and the result says what each one reports.
 */
class FirmwareJob(
    private val target: FirmwareTarget,
    private val pair: LensPair,
    private val env: FirmwareEnvironment,
    private val report: (FirmwareInstall.Running) -> Unit,
    private val log: (String) -> Unit,
    private val policy: JobPolicy = JobPolicy(),
) {
    private val kind = when (target) {
        FirmwareTarget.ORIGINAL -> FirmwareKind.Stock
        FirmwareTarget.CUSTOM -> FirmwareKind.Custom
    }

    /** Ends the job with a message for the wearer (it already says what state the glasses are in). */
    private class Stop(message: String) : Exception(message)

    /** Set once firmware bytes may be on their way: from then on the outcome is saved for the wearer. */
    private var noticeSaved = false

    fun run(): FirmwareInstall {
        var result: FirmwareInstall = FirmwareInstall.Idle
        try {
            result = runSteps()
            return result
        } finally {
            if (noticeSaved) {
                // A failure after the first byte stays saved until the wearer taps OK, even if Android
                // ends the app first; a confirmed transfer needs no notice. Anything thrown past the
                // job keeps the notice saved so far ("interrupted").
                when (val r = result) {
                    is FirmwareInstall.Done -> env.clearNotice()
                    is FirmwareInstall.Failed -> env.saveNotice(target, r.message)
                    else -> Unit
                }
            }
            env.allowSleep()
        }
    }

    private fun runSteps(): FirmwareInstall {
        try {
            step("Prüfe Voraussetzungen …")
            env.keepAwake("Firmware wird vorbereitet")
            checkWatch()
            val image = prepareImage()
            step("Trenne die Brillenverbindung …")
            env.releaseGlasses()
            env.sleep(policy.settleMs)
            val before = readFirmware("Lese die Firmware der Brille …")
                ?: throw Stop("Die Brille hat ihre Firmware nicht gemeldet. Brille aus dem Etui nehmen, am Handy Bluetooth ausschalten und erneut versuchen. $UNTOUCHED")
            checkCurrentFirmware(before)
            env.sleep(policy.settleMs)
            confirmOnGlasses()
            env.sleep(policy.settleMs)
            transfer(image)
            return verify()
        } catch (s: Stop) {
            log("stopped: ${s.message}")
            return FirmwareInstall.Failed(target, s.message.orEmpty())
        } catch (e: FirmwareBuildException) {
            log("image: ${e.message}")
            return FirmwareInstall.Failed(target, imageMessage(e))
        } catch (e: InvalidFirmwareException) {
            log("image: ${e.message}")
            return FirmwareInstall.Failed(target, "Das Firmware-Image ist fehlerhaft (${e.message}). $UNTOUCHED")
        } catch (e: Exception) {
            log("unexpected: $e")
            val what = if (touched) FLASH_UNKNOWN else UNTOUCHED
            return FirmwareInstall.Failed(target, "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}. $what")
        }
    }

    // --- 1. watch ------------------------------------------------------------------------------

    private fun checkWatch() {
        if (pair.right.isBlank() || pair.left.isBlank() || pair.right.equals(pair.left, ignoreCase = true)) {
            throw Stop("Für die Firmware braucht die Uhr beide Bügel der Brille. Brille neu suchen, bis beide gefunden sind. $UNTOUCHED")
        }
        val power = env.watchPower()
        val percent = power.percent
        if (!power.charging && (percent == null || percent < policy.minWatchBattery)) {
            val level = percent?.let { "$it %" } ?: "unbekannt"
            throw Stop("Der Akku der Uhr ist zu schwach ($level). Uhr auf mindestens ${policy.minWatchBattery} % laden oder aufs Ladegerät legen. $UNTOUCHED")
        }
    }

    // --- 2. image ------------------------------------------------------------------------------

    private fun prepareImage(): EvenOtaImage {
        step("Lade die Original-Firmware …", 0)
        var lastPercent = -1
        val stock = env.stock.load { done, total ->
            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
            if (percent != null && percent != lastPercent) {
                lastPercent = percent
                step("Lade die Original-Firmware …", percent)
            }
        }
        val image = if (kind == FirmwareKind.Custom) {
            step("Baue die Custom-Firmware …", 0)
            env.images.prepare(kind, stock) { applied, total -> step("Baue die Custom-Firmware …", applied * 100 / total) }
        } else {
            step("Prüfe das Image …")
            env.images.prepare(kind, stock) { _, _ -> }
        }
        // Whatever prepared it, only an allow-listed image of the chosen kind goes further.
        if (env.images.kindOf(image.sha256) != kind) throw Stop("Das Image ist nicht auf der Liste erlaubter Firmware. $UNTOUCHED")
        log("image ready: ${env.images.describe(kind)}, ${image.size} bytes, sha256 ${image.sha256}")
        return image
    }

    // --- 4. what is on the glasses -------------------------------------------------------------

    /** Faceclaw's read-only probe; null when the glasses reported nothing usable. */
    private fun readFirmware(text: String): FirmwareVerdict? {
        step(text)
        val link = env.openLink(log)
        var result: FirmwareVerdict? = null
        var error: String? = null
        val listener = object : FaceclawDeviceInfoProbeListener {
            override fun onLog(line: String?) {
                line?.let { log("probe: $it") }
            }

            override fun onState(state: String?, detail: String?) {
                if (state == "authenticating") step("Kopple mit der Brille (${armName(detail)}) – falls die Uhr fragt: bestätigen")
            }

            override fun onResult(leftVersion: String?, rightVersion: String?, extension: String?) {
                result = FirmwareRequirement.check(leftVersion, rightVersion, extension)
            }

            override fun onError(message: String?) {
                error = message
            }
        }
        try {
            DeviceInfoProbeFlow(link, pair.right, pair.left, listener, env.timings, env.platform).run()
        } finally {
            link.close()
        }
        error?.let { log("probe failed: $it") }
        return result?.takeIf { it.kind != GlassesFirmwareKind.UNKNOWN }
    }

    private fun checkCurrentFirmware(verdict: FirmwareVerdict) {
        log("on the glasses: ${verdict.summary}")
        val newer = listOf(verdict.leftVersion, verdict.rightVersion).filter { FirmwareRequirement.isNewerThanBase(it) }
        if (newer.isNotEmpty()) {
            throw Stop(
                "Die Brille hat eine neuere Firmware (${newer.first()}) als die Basis dieser App " +
                    "(${FirmwareCatalog.STOCK_VERSION}). Eine ältere Version darüber zu spielen ist nicht erprobt; " +
                    "die App tut es nicht. $UNTOUCHED",
            )
        }
    }

    // --- 5. confirmation and batteries ---------------------------------------------------------

    private class Battery(val right: Int, val left: Int) {
        override fun toString() = "R ${pct(right)}, L ${pct(left)}"

        private fun pct(v: Int) = if (v in 0..100) "$v %" else "?"
    }

    private fun confirmOnGlasses() {
        step("Verbinde mit der Brille …")
        val link = env.openLink(log)
        var battery: Battery? = null
        var answer: Boolean? = null
        var lastState = ""
        var lastDetail = ""
        val listener = object : FaceclawFlashPromptListener {
            override fun onLog(line: String?) {
                line?.let { log("prompt: $it") }
            }

            override fun onState(state: String?, detail: String?) {
                lastState = state.orEmpty()
                lastDetail = detail.orEmpty()
                when (state) {
                    "connected" -> step("Kopple beide Bügel – falls die Uhr fragt: bestätigen")
                    "prompting" -> step("Jetzt auf der Brille bestätigen: „Yes, flash“ wählen (Bügel wischen und tippen).")
                    "battery" -> step("Lese den Akku der Brille …")
                }
            }

            override fun onBattery(rightPercent: Int, leftPercent: Int) {
                battery = Battery(rightPercent, leftPercent)
            }

            override fun onResult(approved: Boolean) {
                log("prompt result: ${if (approved) "approved" else "declined"}")
                answer = approved
            }
        }
        try {
            FlashPromptFlow(link, pair.right, pair.left, glassesText(), false, listener, env.timings, env.platform).run()
        } finally {
            link.close()
        }
        when (answer) {
            true -> Unit
            false -> throw Stop("Auf der Brille abgelehnt. $UNTOUCHED")
            null -> throw Stop(promptFailure(lastState, lastDetail))
        }
        val b = battery ?: throw Stop("Der Akku der Brille ließ sich nicht lesen. $UNTOUCHED")
        log("glasses battery: $b")
        val low = b.right !in policy.minGlassesBattery..100 || b.left !in policy.minGlassesBattery..100
        if (low) {
            throw Stop(
                "Akku der Brille zu schwach oder nicht lesbar ($b). Beide Gläser auf mindestens " +
                    "${policy.minGlassesBattery} % laden und erneut versuchen. $UNTOUCHED",
            )
        }
    }

    private fun glassesText(): String = when (kind) {
        // Kept short for the lens' text grid, and without umlauts: the stock font may lack them.
        FirmwareKind.Custom -> "G2 Watch: Custom-Firmware ${FirmwareCatalog.CUSTOM_EXTENSION} aufspielen? Die Garantie erlischt. Brille dabei anlassen."
        FirmwareKind.Stock -> "G2 Watch: Original-Firmware ${FirmwareCatalog.STOCK_VERSION} aufspielen? Brille dabei anlassen."
    }

    private fun promptFailure(state: String, detail: String): String = when {
        detail == FlashPromptFlow.SILENT_MODE_MESSAGE -> SILENT_TEXT
        state == "timeout" -> "Auf der Brille wurde nichts gewählt. $UNTOUCHED"
        state == "disconnected" -> "Die Verbindung zur Brille ist abgerissen. $UNTOUCHED"
        else -> "${FaceclawMessages.german(detail.ifBlank { state })}. Brille aus dem Etui nehmen, am Handy Bluetooth ausschalten oder die Even-App beenden. $UNTOUCHED"
    }

    // --- 6. transfer ---------------------------------------------------------------------------

    /** The link of the transfer step, once it exists. */
    @Volatile
    private var transferLink: GuardedStockLink? = null

    /** True once a firmware frame actually went out to a lens (the guard let it through). */
    private val touched: Boolean get() = (transferLink?.otaWriteCount ?: 0) > 0

    private fun transfer(image: EvenOtaImage) {
        // The allow-list, once more, right before the first byte: the bytes that go out are exactly
        // the image with this hash (EvenOtaImage keeps a private copy).
        val hash = image.recomputeSha256()
        if (env.images.kindOf(hash) != kind) throw Stop("Das Image ist nicht auf der Liste erlaubter Firmware. $UNTOUCHED")
        val bytes = image.bytes()
        if (Digests.sha256(bytes) != hash) throw Stop("Das Image hat sich im Speicher verändert. $UNTOUCHED")

        env.keepAwake("Firmware wird übertragen – Uhr bei der Brille lassen")
        val link = env.openLink(log)
        transferLink = link
        var lens = ""
        val done = mutableListOf<String>()
        var ok = false
        var failure = ""
        // Faceclaw ends every failed component with "failed after 3 attempts"; the cause it logged
        // before (lost link, ack timeout, rejection) is what the wearer needs to hear.
        var lastCause = ""
        val listener = object : FaceclawFirmwareFlasherListener {
            override fun onLog(line: String?) {
                val text = line ?: return
                log("ota: $text")
                val marker = ": block phase failed: "
                val at = text.indexOf(marker)
                if (at >= 0) lastCause = text.substring(at + marker.length)
                else if ("END verify FAILED" in text) lastCause = text
            }

            override fun onState(state: String?, detail: String?) {
                when (state) {
                    "connecting" -> {
                        lens = detail.orEmpty()
                        step("${lensName(lens)}: verbinde …", progressFor(lens, 0.0))
                    }
                    "flashing" -> step("${lensName(lens)}: übertrage …", progressFor(lens, 0.0))
                    "rebooting" -> {
                        done += lens
                        step("Linkes Glas fertig. Die Brille startet kurz neu, dann folgt das rechte …", 50)
                    }
                    "done" -> if (lens.isNotEmpty() && lens !in done) done += lens
                }
            }

            override fun onProgress(lens: String?, componentIndex: Int, componentCount: Int, blockIndex: Int, blockCount: Int, bytesSent: Long, bytesTotal: Long) {
                val name = lens.orEmpty()
                val fraction = if (bytesTotal > 0) bytesSent.toDouble() / bytesTotal else 0.0
                val part = image.components.getOrNull(componentIndex - 1)?.let { " (${componentName(it.name)})" }.orEmpty()
                step("${lensName(name)}: Teil $componentIndex von $componentCount$part …", progressFor(name, fraction))
            }

            override fun onComplete(success: Boolean, detail: String?) {
                log("ota complete: $success ${detail.orEmpty()}")
                ok = success
                failure = detail.orEmpty()
            }
        }
        env.saveNotice(target, INTERRUPTED)
        noticeSaved = true
        link.arm()
        try {
            OtaFlashFlow(link, pair.right, pair.left, FirmwareCatalog.fileName(kind), listener, env.timings, env.platform) { bytes }.run()
        } finally {
            link.disarm()
            link.close()
            log("ota writes: ${link.otaWriteCount}, refused: ${link.refusedWriteCount}, MTU refusals: ${link.mtuRefusalCount}")
        }
        if (!ok) {
            val state = when {
                !touched -> UNTOUCHED
                done.isEmpty() -> FLASH_UNKNOWN
                else -> "Das linke Glas hat die neue Firmware, das rechte nicht. $FLASH_UNKNOWN"
            }
            val mtuHint = link.narrowMtu
                ?.let { " Die Bluetooth-Verbindung der Uhr war zu schmal (MTU $it, nötig ${GuardedStockLink.MIN_MTU})." }
                .orEmpty()
            val cause = if ("failed after" in failure && lastCause.isNotBlank()) lastCause else failure
            throw Stop("Übertragung abgebrochen: ${FaceclawMessages.german(cause)}.$mtuHint $state")
        }
        // Both lenses accepted every component; if the app dies now, only the check is missing.
        env.saveNotice(target, VERIFY_INTERRUPTED)
    }

    // --- 7. after the reboot -------------------------------------------------------------------

    /** What one lens says about itself on its own link: its field 100 and the reported versions. */
    private data class ArmReport(val extension: String, val leftVersion: String, val rightVersion: String) {
        fun describe(): String = extension.ifEmpty { "Original ${listOf(leftVersion, rightVersion).firstOrNull { it.isNotEmpty() } ?: "?"}" }
    }

    /**
     * Asks each lens on its own link. Faceclaw's probe reports only one lens (the right one, the
     * left one only when the right stays silent), which could hide a mixed pair; the custom
     * firmware adds field 100 to every settings reply of the lens it runs on.
     */
    private fun readArm(address: String, name: String): ArmReport? {
        val link = env.openLink(log)
        try {
            val session = StockLinkSession(link, log, env.timings, platform = env.platform)
            session.bringUp(address)
            if (session.authenticate(address, name) != StockLinkSession.AuthResult.SUCCESS) return null
            // Like Faceclaw: the prelude goes to the right arm only (acks come from the right arm), and
            // a missing ack is no reason to skip the read; the left arm's settings are read as they are.
            if (name == "right") preludeQuietly(session, address)
            repeat(2) {
                val pb = session.readSettings(address, env.timings.queryTimeoutMs) ?: session.unsolicitedSettingsPb
                val info = pb?.let { BleProtocol.parseSettingsFirmwareInfo(it) }
                if (info != null) return ArmReport(info.extension.trim(), info.leftVersion.trim(), info.rightVersion.trim())
            }
            return null
        } catch (e: IllegalStateException) {
            log("verify $name: ${e.message}")
            return null
        } finally {
            link.close()
        }
    }

    private fun verify(): FirmwareInstall {
        step("Die Brille startet neu …", 100)
        env.keepAwake("Firmware übertragen – Brille startet neu")
        env.sleep(policy.verifyDelayMs)
        var left: ArmReport? = null
        var right: ArmReport? = null
        for (attempt in 1..policy.verifyAttempts) {
            step("Prüfe die neue Firmware (Versuch $attempt von ${policy.verifyAttempts}) …", 100)
            if (right == null || !matchesTarget(right)) right = readArm(pair.right, "right") ?: right
            env.sleep(policy.settleMs)
            if (left == null || !matchesTarget(left)) left = readArm(pair.left, "left") ?: left
            log("verify: L ${left?.describe() ?: "keine Antwort"}, R ${right?.describe() ?: "keine Antwort"}")
            if (left != null && right != null && matchesTarget(left) && matchesTarget(right)) {
                return FirmwareInstall.Done(target, doneMessage(left, right))
            }
            if (attempt < policy.verifyAttempts) env.sleep(policy.verifyIntervalMs)
        }
        // Not green: the transfer ended, but the glasses do not (yet) show the chosen firmware.
        return FirmwareInstall.Failed(
            target,
            "Beide Gläser sind übertragen, aber die Kontrolle danach fand ${env.images.describe(kind)} nicht auf " +
                "beiden (links: ${left?.describe() ?: "keine Antwort"}, rechts: ${right?.describe() ?: "keine Antwort"}). " +
                "Brille einschalten, kurz warten und neu verbinden; bleibt ein Glas anders, erneut aufspielen.",
        )
    }

    private fun matchesTarget(a: ArmReport): Boolean {
        val versions = listOf(a.leftVersion, a.rightVersion).filter { it.isNotEmpty() }
        val onBase = versions.isNotEmpty() && versions.all { it == FirmwareCatalog.STOCK_VERSION }
        return when (kind) {
            FirmwareKind.Custom -> a.extension == FirmwareCatalog.CUSTOM_EXTENSION
            FirmwareKind.Stock -> a.extension.isEmpty() && onBase
        }
    }

    private fun doneMessage(left: ArmReport, right: ArmReport): String = when (kind) {
        FirmwareKind.Custom -> "Beide Gläser melden ${FirmwareCatalog.CUSTOM_EXTENSION} (Basis ${right.rightVersion.ifEmpty { left.leftVersion }})."
        FirmwareKind.Stock -> "Beide Gläser melden wieder die Original-Firmware ${FirmwareCatalog.STOCK_VERSION}."
    }

    private fun preludeQuietly(session: StockLinkSession, address: String) {
        try {
            session.sendPrelude(address)
        } catch (e: IllegalStateException) {
            log("prelude: ${e.message}")
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun step(text: String, percent: Int? = null) = report(FirmwareInstall.Running(target, text, percent))

    /** Left lens 0–50 %, right lens 50–100 %, by bytes. */
    private fun progressFor(lens: String, fraction: Double): Int {
        val base = if (lens == "right") 50.0 else 0.0
        return (base + fraction.coerceIn(0.0, 1.0) * 50.0).toInt().coerceIn(0, 100)
    }

    private fun lensName(lens: String) = when (lens) {
        "left" -> "Linkes Glas"
        "right" -> "Rechtes Glas"
        else -> "Glas"
    }

    /** The six parts of Even's image, in words. */
    private fun componentName(path: String) = when (path.substringAfterLast('/')) {
        "codec.bin" -> "Audio-Chip"
        "ble_em9305.bin" -> "Bluetooth-Chip"
        "touch.bin" -> "Touch"
        "box.bin" -> "Etui"
        "s200_bootloader.bin" -> "Bootloader"
        "s200_firmware_ota.bin" -> "Hauptprogramm"
        else -> path.substringAfterLast('/')
    }

    private fun armName(detail: String?) = when (detail) {
        "right" -> "rechter Bügel"
        "left" -> "linker Bügel"
        else -> detail.orEmpty()
    }

    private fun imageMessage(e: FirmwareBuildException): String {
        val text = e.message.orEmpty()
        return if (text.contains(UNTOUCHED)) text else "$text $UNTOUCHED"
    }

    companion object {
        const val UNTOUCHED = "Nichts wurde an der Brille verändert."
        const val SILENT_TEXT =
            "Die Brille ist im Lautlos-Modus und kann die Frage nicht zeigen. Beide Touchpads der Brille lange drücken, " +
                "um ihn zu verlassen, dann erneut versuchen. $UNTOUCHED"
        const val INTERRUPTED_PREFIX = "Die App wurde während der Übertragung beendet. "
        val INTERRUPTED: String get() = INTERRUPTED_PREFIX + FLASH_UNKNOWN
        const val VERIFY_INTERRUPTED =
            "Die Übertragung war abgeschlossen; die Kontrolle danach wurde unterbrochen. Brille einschalten, kurz " +
                "warten und neu verbinden; bleibt ein Glas anders, erneut aufspielen."
        const val FLASH_UNKNOWN =
            "Welche Firmware die Brille jetzt startet, ist unklar. Brille laden und neu starten (5× schnell auf beide " +
                "Touchflächen tippen); startet sie, die Übertragung erneut starten oder die Original-Firmware aufspielen. " +
                "Die App wiederholt nichts von selbst."
    }
}

package ch.madtreasures.g2watch.geckoprobe

import java.util.Locale
import kotlin.math.abs

/** What the watch reports about itself (05 §5.1, step 1). */
data class DeviceInfo(
    val model: String,
    val sdk: Int,
    /** `Build.SUPPORTED_ABIS`, i.e. `ro.product.cpu.abilist`. */
    val abis: List<String>,
    val totalRamMb: Int,
    val availRamMb: Int,
    /** `android.software.webview`; expected false on Wear OS. */
    val webView: Boolean,
    /** The ABI this APK was built for (flavour). */
    val apkAbi: String,
)

enum class Verdict(val mark: String) { OK("✓"), BORDER("~"), FAIL("✗") }

/** The 5 criteria of 05 §5.1 for "weiter mit GeckoView". */
object Limits {
    const val COLD_START_MS = 5_000L
    const val MEMORY_MB = 300
    const val TIMER_DEVIATION = 0.20
    const val BATTERY_PER_HOUR = 8.0
    const val ENDURANCE_MINUTES = 30
}

/** How regularly a 100 ms `setInterval` ran, from the reports of the timer page. */
data class TimerSummary(val reports: Int, val ticks: Int, val meanMs: Double, val maxGapMs: Double, val lateShare: Double) {
    /** Relative deviation of the mean interval from 100 ms. */
    val deviation: Double get() = abs(meanMs - INTERVAL_MS) / INTERVAL_MS

    companion object {
        const val INTERVAL_MS = 100.0

        /** Combines the page's 5 s reports (count, meanMs, maxGapMs, late) weighted by their ticks. */
        fun of(reports: List<TimerReport>): TimerSummary? {
            val ticks = reports.sumOf { it.count }
            if (ticks == 0) return null
            return TimerSummary(
                reports = reports.size,
                ticks = ticks,
                meanMs = reports.sumOf { it.meanMs * it.count } / ticks,
                maxGapMs = reports.maxOf { it.maxGapMs },
                lateShare = reports.sumOf { it.late }.toDouble() / ticks,
            )
        }
    }
}

data class TimerReport(val count: Int, val meanMs: Double, val maxGapMs: Double, val late: Int, val screenOn: Boolean)

/** One reading of the watch battery. */
data class BatterySample(val atMs: Long, val percent: Int, val chargeMicroAh: Long?)

object BatteryEstimate {
    /**
     * Percent per hour between the first and the last sample. With a charge counter the drop in µAh
     * is converted to percent through the capacity it implies, which is much finer than whole percent.
     */
    fun perHour(samples: List<BatterySample>): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val hours = (last.atMs - first.atMs) / 3_600_000.0
        if (hours <= 0.0) return null
        val c0 = first.chargeMicroAh
        val c1 = last.chargeMicroAh
        val drop = if (c0 != null && c1 != null && c0 > 0 && first.percent > 0) {
            val full = c0 * 100.0 / first.percent
            (c0 - c1) * 100.0 / full
        } else {
            (first.percent - last.percent).toDouble()
        }
        return drop / hours
    }
}

/** The render test of the M7 path: GeckoView paints into an unseen surface, web-raster converts it. */
data class RenderResult(
    val url: String,
    val loadMs: Long,
    val captureMs: Long,
    val layoutMs: Long,
    val rasterMs: Double,
    val width: Int,
    val height: Int,
    val textRuns: Int,
    val negativeRuns: Int,
    val overloaded: Boolean,
    val error: String? = null,
)

enum class Decision(val text: String) {
    YES("GeckoView ja"),
    SOME("GeckoView nur für manche Apps"),
    NO("GeckoView nein – Handy als Standard-Ort"),
    OPEN("noch offen – nicht alle Messungen gemacht"),
}

/** Everything measured so far; the watch shows it and writes it as the report to send back. */
data class ProbeResults(
    val device: DeviceInfo? = null,
    val geckoVersion: String = "",
    val runtimeMs: Long? = null,
    val coldStartMs: Long? = null,
    val roundTripMs: Long? = null,
    /** App name → null while waiting, "" when fine, else what went wrong. */
    val apps: Map<String, String?> = emptyMap(),
    val appDetails: Map<String, String> = emptyMap(),
    val pssMb: Int? = null,
    val pssPeakMb: Int? = null,
    val timerOn: TimerSummary? = null,
    val timerOff: TimerSummary? = null,
    val batteryPerHour: Double? = null,
    val enduranceMinutes: Int? = null,
    /** GeckoView reported a crashed or killed content process, or the runtime shut down. */
    val losses: List<String> = emptyList(),
    val render: RenderResult? = null,
) {
    fun coldStartVerdict(): Verdict? = coldStartMs?.let { if (it <= Limits.COLD_START_MS) Verdict.OK else if (it <= 2 * Limits.COLD_START_MS) Verdict.BORDER else Verdict.FAIL }

    fun memoryVerdict(): Verdict? = (pssPeakMb ?: pssMb)?.let { if (it <= Limits.MEMORY_MB) Verdict.OK else if (it <= 450) Verdict.BORDER else Verdict.FAIL }

    fun appsVerdict(): Verdict? {
        if (apps.isEmpty() || apps.values.any { it == null }) return null
        return if (apps.values.all { it == "" }) Verdict.OK else if (apps.values.count { it != "" } == 1) Verdict.BORDER else Verdict.FAIL
    }

    fun timerVerdict(): Verdict? {
        val worst = listOfNotNull(timerOn, timerOff).maxOfOrNull { it.deviation } ?: return null
        return if (worst < Limits.TIMER_DEVIATION) Verdict.OK else if (worst < 2 * Limits.TIMER_DEVIATION) Verdict.BORDER else Verdict.FAIL
    }

    fun batteryVerdict(): Verdict? = batteryPerHour?.let { if (it < Limits.BATTERY_PER_HOUR) Verdict.OK else if (it < 1.5 * Limits.BATTERY_PER_HOUR) Verdict.BORDER else Verdict.FAIL }

    fun enduranceVerdict(): Verdict? {
        val minutes = enduranceMinutes ?: return null
        return when {
            losses.isNotEmpty() -> Verdict.FAIL
            minutes >= Limits.ENDURANCE_MINUTES -> Verdict.OK
            else -> null
        }
    }

    /**
     * The decision of 05 §5.1: failing apps, a crash or a far too slow start mean no, as do two
     * failed criteria; all criteria met mean yes; anything in between means some apps only. Open
     * while a measurement is missing.
     */
    fun decision(): Decision {
        val verdicts = listOf(coldStartVerdict(), memoryVerdict(), appsVerdict(), timerVerdict(), batteryVerdict(), enduranceVerdict())
        val critical = appsVerdict() == Verdict.FAIL || enduranceVerdict() == Verdict.FAIL || coldStartVerdict() == Verdict.FAIL
        return when {
            critical || verdicts.count { it == Verdict.FAIL } >= 2 -> Decision.NO
            verdicts.any { it == null } -> Decision.OPEN
            verdicts.all { it == Verdict.OK } -> Decision.YES
            else -> Decision.SOME
        }
    }

    /** The lines the watch shows and the report file contains, in German. */
    fun lines(): List<String> {
        val out = ArrayList<String>()
        fun mark(v: Verdict?) = v?.mark?.let { " $it" }.orEmpty()
        device?.let { d ->
            out += "Gerät: ${d.model}, API ${d.sdk}"
            out += "ABI: ${d.abis.joinToString(",")} (APK: ${d.apkAbi})"
            out += "RAM: ${gb(d.totalRamMb)} GB, frei ${gb(d.availRamMb)} GB"
            out += "System-WebView: " + if (d.webView) "ja" else "nein"
        }
        if (geckoVersion.isNotEmpty()) out += "GeckoView $geckoVersion"
        runtimeMs?.let { out += "Engine gestartet: ${seconds(it)}" }
        coldStartMs?.let { out += "Kaltstart bis erster Aufruf: ${seconds(it)}${mark(coldStartVerdict())} (Ziel ≤ 5 s)" }
        if (apps.isNotEmpty()) {
            out += "Test-Apps: " + apps.entries.joinToString(" · ") { (name, state) ->
                name + when (state) {
                    null -> " …"
                    "" -> " ✓"
                    else -> " ✗ ($state)"
                }
            }
        }
        appDetails.forEach { (k, v) -> out += "  $k: $v" }
        roundTripMs?.let { out += "Brücke Uhr → App → Uhr: $it ms" }
        (pssPeakMb ?: pssMb)?.let { out += "Speicher (PSS, alle Prozesse): $it MB${mark(memoryVerdict())} (Ziel ≤ 300 MB)" }
        timerOn?.let { out += "Timer 100 ms, Bildschirm an: ${percent(it.deviation)} Abweichung, längste Lücke ${it.maxGapMs.toInt()} ms" }
        timerOff?.let { out += "Timer 100 ms, Bildschirm aus: ${percent(it.deviation)} Abweichung, längste Lücke ${it.maxGapMs.toInt()} ms" }
        timerVerdict()?.let { out += "Timer${mark(it)} (Ziel < 20 %)" }
        batteryPerHour?.let { out += "Akku: ${String.format(Locale.GERMANY, "%.1f", it)} %/h${mark(batteryVerdict())} (Ziel < 8 %/h)" }
        enduranceMinutes?.let { out += "Dauertest: $it min, " + (if (losses.isEmpty()) "kein Abbruch" else "Abbrüche: ${losses.joinToString()}") + mark(enduranceVerdict()) }
        render?.let { r ->
            out += if (r.error != null) {
                "Seite rendern: ${r.error}"
            } else {
                "Seite rendern (${r.width}×${r.height}): laden ${seconds(r.loadMs)}, aufnehmen ${r.captureMs} ms, " +
                    "Layout ${r.layoutMs} ms, Raster ${r.rasterMs.toInt()} ms; ${r.textRuns} Textzeilen, ${r.negativeRuns} negativ" +
                    if (r.overloaded) ", Fenster überladen" else ""
            }
        }
        out += "Empfehlung: ${decision().text}"
        return out
    }

    private fun gb(mb: Int) = String.format(Locale.GERMANY, "%.1f", mb / 1024.0)

    private fun seconds(ms: Long) = String.format(Locale.GERMANY, "%.1f s", ms / 1000.0)

    private fun percent(v: Double) = String.format(Locale.GERMANY, "%.0f %%", v * 100)
}

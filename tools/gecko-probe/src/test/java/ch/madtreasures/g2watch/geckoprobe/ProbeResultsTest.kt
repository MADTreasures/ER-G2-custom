package ch.madtreasures.g2watch.geckoprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultsTest {

    private val device = DeviceInfo("Google Pixel Watch 5", 37, listOf("armeabi-v7a", "armeabi"), 2048, 900, webView = false, apkAbi = "armeabi-v7a")

    private val timerOk = TimerSummary(reports = 24, ticks = 1200, meanMs = 104.0, maxGapMs = 180.0, lateShare = 0.01)

    /** Every criterion of 05 §5.1 met. */
    private val allGood = ProbeResults(
        device = device,
        geckoVersion = "157.0",
        runtimeMs = 900,
        coldStartMs = 3_200,
        roundTripMs = 14,
        apps = linkedMapOf("Text" to "", "Canvas" to "", "Vue+WASM" to ""),
        pssMb = 240,
        pssPeakMb = 260,
        timerOn = timerOk,
        timerOff = timerOk.copy(meanMs = 112.0),
        batteryPerHour = 6.5,
        enduranceMinutes = 30,
    )

    @Test
    fun `all criteria met means yes`() {
        assertEquals(Decision.YES, allGood.decision())
        assertEquals(Verdict.OK, allGood.coldStartVerdict())
        assertEquals(Verdict.OK, allGood.memoryVerdict())
        assertEquals(Verdict.OK, allGood.appsVerdict())
        assertEquals(Verdict.OK, allGood.timerVerdict())
        assertEquals(Verdict.OK, allGood.batteryVerdict())
        assertEquals(Verdict.OK, allGood.enduranceVerdict())
    }

    @Test
    fun `a missing measurement leaves the decision open`() {
        assertEquals(Decision.OPEN, ProbeResults().decision())
        assertEquals(Decision.OPEN, allGood.copy(batteryPerHour = null).decision())
        assertEquals(Decision.OPEN, allGood.copy(enduranceMinutes = null).decision())
        // An app still loading is not a result yet.
        assertNull(allGood.copy(apps = mapOf("Text" to "", "Canvas" to null)).appsVerdict())
    }

    @Test
    fun `a borderline value means some apps only`() {
        assertEquals(Decision.SOME, allGood.copy(pssPeakMb = 380).decision())
        assertEquals(Decision.SOME, allGood.copy(batteryPerHour = 10.0).decision())
        assertEquals(Decision.SOME, allGood.copy(timerOff = timerOk.copy(meanMs = 130.0)).decision())
        assertEquals(Decision.SOME, allGood.copy(apps = mapOf("Text" to "", "Canvas" to "", "Vue+WASM" to "kein Wasm")).decision())
    }

    @Test
    fun `failing apps, losses, a slow start or two failures mean no`() {
        assertEquals(Decision.NO, allGood.copy(apps = mapOf("Text" to "x", "Canvas" to "y", "Vue+WASM" to "")).decision())
        assertEquals(Decision.NO, allGood.copy(losses = listOf("Text abgestürzt (onCrash)")).decision())
        assertEquals(Decision.NO, allGood.copy(coldStartMs = 12_000).decision())
        assertEquals(Decision.NO, allGood.copy(pssPeakMb = 600, batteryPerHour = 20.0).decision())
        // A crash is decisive even while other values are missing.
        assertEquals(Decision.NO, ProbeResults(enduranceMinutes = 12, losses = listOf("x")).decision())
    }

    @Test
    fun `verdict boundaries follow the targets of 05`() {
        assertEquals(Verdict.OK, allGood.copy(coldStartMs = 5_000).coldStartVerdict())
        assertEquals(Verdict.BORDER, allGood.copy(coldStartMs = 5_001).coldStartVerdict())
        assertEquals(Verdict.OK, allGood.copy(pssPeakMb = 300).memoryVerdict())
        assertEquals(Verdict.BORDER, allGood.copy(pssPeakMb = 301).memoryVerdict())
        assertEquals(Verdict.FAIL, allGood.copy(pssPeakMb = 451).memoryVerdict())
        // Without a peak the last reading counts.
        assertEquals(Verdict.BORDER, allGood.copy(pssMb = 320, pssPeakMb = null).memoryVerdict())
        assertEquals(Verdict.BORDER, allGood.copy(batteryPerHour = 8.0).batteryVerdict())
        assertEquals(Verdict.FAIL, allGood.copy(timerOn = timerOk.copy(meanMs = 141.0)).timerVerdict())
        // A run that was cancelled early has no endurance verdict unless something died.
        assertNull(allGood.copy(enduranceMinutes = 10).enduranceVerdict())
    }

    @Test
    fun `timer reports are combined by their ticks`() {
        val s = TimerSummary.of(
            listOf(
                TimerReport(count = 50, meanMs = 100.0, maxGapMs = 120.0, late = 0, screenOn = true),
                TimerReport(count = 25, meanMs = 190.0, maxGapMs = 900.0, late = 10, screenOn = true),
            ),
        )!!
        assertEquals(2, s.reports)
        assertEquals(75, s.ticks)
        assertEquals(130.0, s.meanMs, 1e-9)
        assertEquals(900.0, s.maxGapMs, 1e-9)
        assertEquals(10 / 75.0, s.lateShare, 1e-9)
        assertEquals(0.30, s.deviation, 1e-9)
        assertNull(TimerSummary.of(emptyList()))
        assertNull(TimerSummary.of(listOf(TimerReport(0, 0.0, 0.0, 0, false))))
    }

    @Test
    fun `battery drain per hour prefers the charge counter`() {
        // 300 000 µAh at 100 % → capacity 300 000 µAh; 6 000 µAh in 30 minutes = 2 % in 0.5 h.
        val fine = listOf(BatterySample(0, 100, 300_000), BatterySample(1_800_000, 100, 294_000))
        assertEquals(4.0, BatteryEstimate.perHour(fine)!!, 1e-9)
        // Without a counter only whole percent.
        val coarse = listOf(BatterySample(0, 80, null), BatterySample(1_800_000, 77, null))
        assertEquals(6.0, BatteryEstimate.perHour(coarse)!!, 1e-9)
        assertNull(BatteryEstimate.perHour(listOf(BatterySample(0, 80, null))))
        assertNull(BatteryEstimate.perHour(listOf(BatterySample(5, 80, null), BatterySample(5, 79, null))))
    }

    @Test
    fun `the report shows every value with its verdict`() {
        val lines = allGood.copy(
            losses = emptyList(),
            render = RenderResult("http://127.0.0.1/render/index.html", 1_400, 35, 12, 21.6, 576, 260, 18, 4, overloaded = false),
        ).lines()
        val text = lines.joinToString("\n")
        assertTrue(text, lines.first().startsWith("Gerät: Google Pixel Watch 5, API 37"))
        assertTrue(text, "ABI: armeabi-v7a,armeabi (APK: armeabi-v7a)" in lines)
        assertTrue(text, "System-WebView: nein" in lines)
        assertTrue(text, "Kaltstart bis erster Aufruf: 3,2 s ✓ (Ziel ≤ 5 s)" in lines)
        assertTrue(text, "Test-Apps: Text ✓ · Canvas ✓ · Vue+WASM ✓" in lines)
        assertTrue(text, "Speicher (PSS, alle Prozesse): 260 MB ✓ (Ziel ≤ 300 MB)" in lines)
        assertTrue(text, "Timer 100 ms, Bildschirm aus: 12 % Abweichung, längste Lücke 180 ms" in lines)
        assertTrue(text, "Akku: 6,5 %/h ✓ (Ziel < 8 %/h)" in lines)
        assertTrue(text, "Dauertest: 30 min, kein Abbruch ✓" in lines)
        assertTrue(text, lines.any { it.startsWith("Seite rendern (576×260): laden 1,4 s") && it.endsWith("18 Textzeilen, 4 negativ") })
        assertEquals("Empfehlung: GeckoView ja", lines.last())
    }

    @Test
    fun `problems are named in the report`() {
        val lines = ProbeResults(
            apps = linkedMapOf("Text" to "", "Canvas" to "keine Antwort in 30 s", "Vue+WASM" to null),
            render = RenderResult("u", 0, 0, 0, 0.0, 576, 260, 0, 0, false, error = "IllegalStateException: capturePixels lieferte nichts"),
        ).lines()
        assertTrue(lines.toString(), "Test-Apps: Text ✓ · Canvas ✗ (keine Antwort in 30 s) · Vue+WASM …" in lines)
        assertTrue(lines.toString(), "Seite rendern: IllegalStateException: capturePixels lieferte nichts" in lines)
        assertEquals("Empfehlung: noch offen – nicht alle Messungen gemacht", lines.last())
    }
}

package ch.madtreasures.g2watch.geckoprobe

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException

/** Readings of the watch itself: architecture, memory, battery. */
object DeviceProbe {

    fun info(context: Context): DeviceInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = (mem.totalMem / (1024 * 1024)).toInt(),
            availRamMb = (mem.availMem / (1024 * 1024)).toInt(),
            webView = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW),
            apkAbi = BuildConfig.APK_ABI,
        )
    }

    /**
     * PSS of all processes of this app in MB: the probe and GeckoView's content, GPU, socket and
     * other service processes (all run under this app's user; isolated processes are switched off).
     * Read from `/proc/<pid>/smaps_rollup`, which an app may read for its own processes; only if
     * that fails from `getProcessMemoryInfo`, which Android refreshes at most every 5 minutes per
     * process for normal apps.
     */
    fun pssMb(context: Context): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        val uid = Process.myUid()
        val pids = am.runningAppProcesses.orEmpty().filter { it.uid == uid }.map { it.pid }.toIntArray()
        if (pids.isEmpty()) return null
        val fromProc = pids.map { procPssKb(it) }
        val kb = if (fromProc.all { it != null }) fromProc.sumOf { it ?: 0 } else am.getProcessMemoryInfo(pids).sumOf { it.totalPss }
        return kb / 1024
    }

    private fun procPssKb(pid: Int): Int? = try {
        File("/proc/$pid/smaps_rollup").useLines { pssKb(it) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** The `Pss:` line of an smaps_rollup file ("Pss:    123456 kB") in kB. */
    fun pssKb(lines: Sequence<String>): Int? =
        lines.firstOrNull { it.startsWith("Pss:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()

    /** The names of this app's running processes, to see what GeckoView started. */
    fun processes(context: Context): List<String> {
        val am = context.getSystemService(ActivityManager::class.java)
        val uid = Process.myUid()
        return am.runningAppProcesses.orEmpty().filter { it.uid == uid }.map { it.processName.substringAfter(':', "main") }
    }

    fun battery(context: Context, nowMs: Long): BatterySample {
        val bm = context.getSystemService(BatteryManager::class.java)
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }
        return BatterySample(nowMs, percent, charge)
    }

    fun charging(context: Context): Boolean = context.getSystemService(BatteryManager::class.java).isCharging
}

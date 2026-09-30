package ch.madtreasures.g2watch.geckoprobe

import android.app.Application

/**
 * GeckoView starts its content, GPU and socket processes with this Application class too; only the
 * main process owns the probe (05 §5: "Hauptprozess prüfen").
 */
class ProbeApp : Application() {
    val lab: GeckoLab by lazy {
        check(isMainProcess) { "Gecko-Test läuft nur im Hauptprozess" }
        GeckoLab(this)
    }

    val isMainProcess: Boolean get() = getProcessName() == packageName
}

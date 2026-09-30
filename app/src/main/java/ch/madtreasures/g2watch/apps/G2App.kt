package ch.madtreasures.g2watch.apps

/**
 * A watch app: a small Kotlin class built into the APK (docs/app-entwicklung/03). Register it in
 * [builtInApps]. Everything it does on the glasses goes through [AppContext].
 */
interface G2App {
    val manifest: AppManifest

    /**
     * Called on the app thread, never concurrently. Must return within 50 ms: slower calls are logged,
     * and a call over 500 ms ends the app ("reagiert zu langsam"). Longer work belongs on a computer.
     */
    fun onEvent(event: AppEvent, ui: AppContext)
}

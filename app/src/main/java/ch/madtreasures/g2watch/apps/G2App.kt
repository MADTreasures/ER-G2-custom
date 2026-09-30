package ch.madtreasures.g2watch.apps

/**
 * A watch app (03): a small Kotlin class built into the APK. It describes its screens as pages of
 * blocks and reacts to events; the host draws, routes input and keeps the history.
 */
interface G2App {
    val manifest: AppManifest

    /**
     * Called on the app thread, never concurrently. Must return within 50 ms: the host logs slower
     * calls and ends an app that takes longer than 500 ms ("reagiert zu langsam").
     */
    fun onEvent(event: AppEvent, ui: AppContext)
}

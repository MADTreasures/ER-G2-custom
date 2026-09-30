package ch.madtreasures.g2watch.apps

/**
 * Apps built into the APK, in launcher order (03 §1). Empty: every app, YouTube included, is an app
 * package installed on the watch (docs/app-entwicklung/09). Only an app that must ship with the watch
 * app itself would go here.
 */
val builtInApps: List<() -> G2App> = emptyList()

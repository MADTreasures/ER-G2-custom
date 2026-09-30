package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.apps.builtin.youtube.YouTubeApp

/**
 * The watch apps built into the APK, in launcher order (03 §1): only real apps. The examples Stoppuhr
 * and Einkauf live in the tests, which put them into their own hosts.
 */
val builtInApps: List<() -> G2App> = listOf({ YouTubeApp() })

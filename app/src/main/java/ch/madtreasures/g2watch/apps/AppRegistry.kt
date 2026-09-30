package ch.madtreasures.g2watch.apps

import ch.madtreasures.g2watch.apps.builtin.shopping.ShoppingListApp
import ch.madtreasures.g2watch.apps.builtin.stopwatch.StopwatchApp

/** The watch apps built into the APK, in launcher order (03 §1). */
val builtInApps: List<() -> G2App> = listOf({ StopwatchApp() }, { ShoppingListApp() })

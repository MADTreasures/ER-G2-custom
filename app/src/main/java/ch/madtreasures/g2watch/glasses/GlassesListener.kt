package ch.madtreasures.g2watch.glasses

import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.host.Gesture
import ch.madtreasures.g2watch.apps.host.GlassesStatus
import ch.madtreasures.g2watch.apps.host.InputRouter
import ch.madtreasures.g2watch.desktop.DesktopController

/** Where [GlassesConnection] sends what the glasses report for the apps: their input and their status. */
interface GlassesListener {
    /** One input report (Faceclaw's `onRingEvent`); returns the gesture it was, or null (none, or a repeat). */
    fun onInput(kind: String?, eventType: Int, eventSource: Int, ringTick: Long, ringType: Int): Gesture?

    /** Connected, battery, charging or wearing changed. */
    fun onStatus(status: GlassesStatus) = Unit

    companion object {
        /** Without the app host: a tap clicks at the pointer, a double tap closes the window. */
        fun desktopOnly(desktop: DesktopController): GlassesListener {
            val router = InputRouter { g ->
                when (g.kind) {
                    GestureKind.CLICK -> desktop.click()
                    GestureKind.DOUBLE_CLICK -> desktop.back()
                    else -> Unit
                }
            }
            return object : GlassesListener {
                override fun onInput(kind: String?, eventType: Int, eventSource: Int, ringTick: Long, ringType: Int) =
                    router.onGlassesEvent(kind, eventType, eventSource, ringTick, ringType)
            }
        }
    }
}

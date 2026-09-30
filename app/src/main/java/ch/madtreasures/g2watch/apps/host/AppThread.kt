package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.ThreadScheduler

/** The one thread all apps run on (03 §5): events, commands, timers and drawing, one after the other. */
object AppThread {
    const val NAME = "G2Watch-apps"

    fun scheduler(): Scheduler = ThreadScheduler(NAME)
}

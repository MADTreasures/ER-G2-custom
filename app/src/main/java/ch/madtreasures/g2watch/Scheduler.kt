package ch.madtreasures.g2watch

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs work on one thread, in order. Tests replace it with a fake that runs on demand. */
interface Scheduler {
    fun post(action: () -> Unit)

    fun postDelayed(delayMs: Long, action: () -> Unit)
}

/** [Scheduler] on a dedicated background thread. */
class ThreadScheduler(name: String) : Scheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    override fun post(action: () -> Unit) {
        executor.execute(action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    }
}

/** [Scheduler] on the main (UI) thread. */
class MainScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(action: () -> Unit) {
        handler.post(action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMs)
    }
}

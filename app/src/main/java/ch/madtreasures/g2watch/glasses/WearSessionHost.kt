package ch.madtreasures.g2watch.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.faceclaw.app.SessionDispatcher
import com.faceclaw.app.SessionHost
import com.faceclaw.app.SessionLogLevel

/**
 * The watch side of Faceclaw's [SessionHost], modelled on the host inside Faceclaw's
 * FaceclawBleCommunicator. A watch has no phone lock screen to report and no Even app that could
 * hold the link, so those two queries answer false. The "glasses screen on" wake lock is a
 * partial wake lock, so the session keeps running while the watch display is dark.
 */
class WearSessionHost(
    context: Context,
    /** Receives the session's warnings and errors (from any thread) for the in-app log. */
    private val logSink: (SessionLogLevel, String) -> Unit,
) : SessionHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainDispatcher = SessionDispatcher { action -> mainHandler.post { action() } }
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    @Volatile
    private var worker: Thread? = null

    override fun postToMain(action: () -> Unit) {
        mainHandler.post { action() }
    }

    override fun currentThreadDispatcher(): SessionDispatcher {
        val looper = Looper.myLooper() ?: return mainDispatcher
        val handler = Handler(looper)
        return SessionDispatcher { action -> handler.post { action() } }
    }

    override fun isPhoneLocked(): Boolean = false

    // No timeout, as in Faceclaw: held for as long as the glasses show the desktop and released
    // on charging, disconnect or process death.
    @SuppressLint("WakelockTimeout")
    override fun setScreenWakeLock(on: Boolean) {
        synchronized(wakeLock) {
            if (on && !wakeLock.isHeld) {
                wakeLock.acquire()
                Log.i(TAG, "wake lock acquired")
            } else if (!on && wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "wake lock released")
            }
        }
    }

    override fun isEvenAppActive(): Boolean = false

    override fun startWorker(body: () -> Unit) {
        val thread = Thread(body, "G2Watch-session")
        worker = thread
        thread.start()
    }

    override fun joinWorker(timeoutMs: Long) {
        val thread = worker ?: return
        thread.interrupt()
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
    }

    override fun log(level: SessionLogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            SessionLogLevel.DEBUG -> Log.d(tag, message)
            SessionLogLevel.INFO -> Log.i(tag, message)
            SessionLogLevel.WARN -> if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
            SessionLogLevel.ERROR -> if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        }
        // INFO fires for every frame; only what needs attention goes to the on-watch log.
        if (level == SessionLogLevel.WARN || level == SessionLogLevel.ERROR) logSink(level, message)
    }

    private companion object {
        const val TAG = "G2Watch"
        const val WAKE_LOCK_TAG = "G2Watch:glasses"
    }
}

package ch.madtreasures.g2watch.apps.host

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Background work of one app session ([ch.madtreasures.g2watch.apps.AppContext.work], interface 5, 03 §14):
 * at most [parallel] tasks at a time, those with one tag one after another, and a task still waiting gives
 * way to a newer one with its tag. The bookkeeping happens on the app thread; [run] executes a task on a
 * background thread, [post] brings its result back to the app thread, and [deliver] hands it to the app
 * there (under the host's rules for app code). [end] interrupts what runs and forgets the rest.
 */
internal class WorkQueue(
    private val run: (Runnable) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val deliver: (tag: String, block: () -> Unit) -> Unit,
    private val parallel: Int = 2,
) {
    private class Job(val tag: String, val task: () -> Any?, val onDone: (Result<Any?>) -> Unit)

    /** Tag → the newest task with it that has not started; only on the app thread. */
    private val waiting = LinkedHashMap<String, Job>()
    private val busy = HashSet<String>()

    /** Threads running a task of this queue, so [end] can interrupt them. */
    private val threads = HashSet<Thread>()

    @Volatile private var ended = false

    fun <T> submit(tag: String, task: () -> T, onDone: (Result<T>) -> Unit) {
        if (ended) return
        @Suppress("UNCHECKED_CAST")
        val job = Job(tag, task, onDone as (Result<Any?>) -> Unit)
        waiting.remove(tag)
        waiting[tag] = job
        startWaiting()
    }

    private fun startWaiting() {
        val jobs = waiting.values.iterator()
        while (busy.size < parallel && jobs.hasNext()) {
            val job = jobs.next()
            if (job.tag in busy) continue
            jobs.remove()
            busy += job.tag
            run(Runnable { execute(job) })
        }
    }

    private fun execute(job: Job) {
        val thread = Thread.currentThread()
        synchronized(threads) {
            if (ended) return
            threads += thread
        }
        val result = try {
            Result.success(job.task())
        } catch (t: Throwable) {
            Result.failure(t)
        }
        synchronized(threads) { threads -= thread }
        // A pool thread goes on to other work: no interrupt of this queue may stick to it.
        Thread.interrupted()
        post { finished(job, result) }
    }

    private fun finished(job: Job, result: Result<Any?>) {
        busy -= job.tag
        if (ended) return
        deliver(job.tag) { job.onDone(result) }
        startWaiting()
    }

    /** The app ended: running tasks are interrupted, waiting ones dropped, and no result reaches it any more. */
    fun end() {
        ended = true
        waiting.clear()
        synchronized(threads) { threads.forEach { it.interrupt() } }
    }
}

/** The threads of all apps' background work; low priority, so the glasses picture and input stay quick. */
internal object WorkPool : Executor by Executors.newFixedThreadPool(3, { r ->
    Thread(r, "G2Watch-work").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
    }
})

package ch.madtreasures.g2watch.apps.web

/**
 * When to take the next picture of a web page (05 §10). A picture costs two captures of the surface,
 * the layout report and the rasterizing, and every changed one goes over Bluetooth, so pictures come
 * when something happened, not on a clock:
 *
 * - soon after the wearer acted (scrolled, tapped, typed) or the page finished loading,
 * - while a page loads: once it painted, then every [LOADING_EVERY_MS],
 * - when the page reports changes (pictures loaded, content arrived), at most every [CHANGED_GAP_MS]
 *   and only within [IDLE_MS] after the wearer's last input, so a carousel cannot keep the link busy,
 * - never closer than [MIN_GAP_MS], and not at all while the page rests (app hidden, glasses away).
 *
 * In reading mode a page is held back until the extension decided whether it is an article, so the
 * glasses do not first show the cluttered page. Times are milliseconds of one monotonic clock.
 */
class CapturePlan {
    var active = true
        private set

    private var due: Long? = null
    private var last = Long.MIN_VALUE / 4
    private var lastInput = Long.MIN_VALUE / 4
    private var loading = false

    /** Reading mode wanted and not decided yet; pictures wait until [readerDeadline]. */
    private var readerPending = false
    private var readerDeadline = Long.MAX_VALUE

    /** A new document starts loading; [waitForReader] when reading mode is wanted. */
    fun started(now: Long, waitForReader: Boolean) {
        loading = true
        lastInput = now
        due = null
        readerPending = waitForReader
        readerDeadline = Long.MAX_VALUE
    }

    /** The loading page painted its first content. */
    fun painted(now: Long) {
        if (loading) want(now + PREVIEW_MS)
    }

    /** Loading went on; a picture now and then shows how far it is. */
    fun progressed(now: Long) {
        if (loading && due == null && now - last >= LOADING_EVERY_MS) want(now)
    }

    /** The page has loaded. */
    fun stopped(now: Long) {
        loading = false
        if (readerPending) readerDeadline = now + READER_WAIT_MS
        want(now + SETTLE_MS)
    }

    /** The extension decided about reading mode (shown or not); the picture waits until it is laid out. */
    fun readerDecided(now: Long) {
        readerPending = false
        readerDeadline = Long.MAX_VALUE
        due = now + READER_MS
    }

    /** The page reports that it changed by itself. */
    fun changed(now: Long) {
        if (!loading && now - lastInput > IDLE_MS) return
        want(maxOf(now + CHANGED_MS, last + CHANGED_GAP_MS))
    }

    /** The wearer acted on the page; the picture follows after [delayMs]. */
    fun acted(now: Long, delayMs: Long) {
        lastInput = now
        want(now + delayMs)
    }

    /** The page rests ([on] false) or works again; on waking up it gets a fresh picture once it painted. */
    fun activate(now: Long, on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            lastInput = now
            due = now + RESUME_MS
        } else {
            due = null
        }
    }

    /** When the next picture is due, or null if none is. */
    fun nextAt(): Long? {
        val d = due ?: return null
        if (!active) return null
        var at = maxOf(d, last + MIN_GAP_MS)
        if (readerPending) {
            if (readerDeadline == Long.MAX_VALUE) return null
            at = maxOf(at, readerDeadline)
        }
        return at
    }

    /** True if a picture is due at [now]; it then counts as taken. */
    fun take(now: Long): Boolean {
        val at = nextAt() ?: return false
        if (now < at) return false
        due = null
        last = now
        if (readerPending) {
            // The extension never answered (no script on this page): stop waiting for it.
            readerPending = false
            readerDeadline = Long.MAX_VALUE
        }
        return true
    }

    private fun want(at: Long) {
        due = minOf(due ?: Long.MAX_VALUE, at)
    }

    companion object {
        const val MIN_GAP_MS = 300L
        const val PREVIEW_MS = 600L
        const val LOADING_EVERY_MS = 2_500L
        const val SETTLE_MS = 500L
        const val READER_WAIT_MS = 1_200L
        const val READER_MS = 200L
        const val CHANGED_MS = 500L
        const val CHANGED_GAP_MS = 2_000L
        const val IDLE_MS = 60_000L
        const val RESUME_MS = 150L

        /** After the wearer acted: scrolling shows at once, a tap may start loading, typing may send. */
        const val AFTER_SCROLL_MS = 250L
        const val AFTER_TAP_MS = 700L
        const val AFTER_TYPE_MS = 600L
        const val AFTER_SETTING_MS = 250L
    }
}

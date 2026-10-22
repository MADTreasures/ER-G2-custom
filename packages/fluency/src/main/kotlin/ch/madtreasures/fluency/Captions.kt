package ch.madtreasures.fluency

import java.util.Locale

/**
 * The utterances Fluency reported and what the live page shows of them: the translations as a rolling
 * caption – the last finished ones, each on a line of its own, then those still being spoken – and the
 * original of the newest. Utterances keep the order in which they first appeared: a finished one may
 * still arrive after the next one started, and Fluency's numbers start again when it restarts.
 */
class Captions(private val keep: Int = HISTORY) {
    private class Entry(val seq: Long, var utterance: Utterance)

    /** This live session's utterances by id, open and finished. */
    private val entries = LinkedHashMap<Long, Entry>()
    private var seq = 0L

    /** Finished utterances of every session, oldest first. */
    private val done = ArrayDeque<Utterance>()

    /** Finished utterances, newest first, for the history page. */
    val history: List<Utterance> get() = done.reversed()

    /**
     * Takes a new state of an utterance; false if nothing changed for the live page. A result for an
     * utterance that is already finished changes nothing, unless it is finished too.
     */
    fun update(u: Utterance): Boolean {
        val entry = entries[u.id]
        if (u.gone) return entry != null && !entry.utterance.final && entries.remove(u.id) != null
        val wasFinal = entry?.utterance?.final == true
        if (wasFinal && !u.final) return false
        if (entry?.utterance == u) return false
        if (entry == null) entries[u.id] = Entry(seq++, u) else entry.utterance = u
        if (u.final) finish(u, again = wasFinal)
        trim()
        return true
    }

    /** [u] into the history; finished [again] (a correction), it takes the place of its earlier entry. */
    private fun finish(u: Utterance, again: Boolean) {
        val earlier = if (again) done.indexOfLast { it.id == u.id } else -1
        when {
            earlier >= 0 && u.text.isNotBlank() -> done[earlier] = u
            earlier >= 0 -> done.removeAt(earlier)
            u.text.isNotBlank() -> done.addLast(u)
        }
        while (done.size > keep) done.removeFirst()
    }

    /** At most [SESSION] utterances for the page: the oldest go (one Fluency never finished as well). */
    private fun trim() {
        while (entries.size > SESSION) entries.remove(entries.values.minBy { it.seq }.utterance.id)
    }

    /** A new live session: the live page starts empty, the history stays. */
    fun startSession() = entries.clear()

    fun clearHistory() = done.clear()

    private fun inOrder(): List<Utterance> = entries.values.sortedBy { it.seq }.map { it.utterance }

    /**
     * The caption: the last [finished] finished translations, then (with [partials]) the ones still being
     * spoken, each utterance on a line of its own. Empty while nothing is translated yet.
     */
    fun translation(partials: Boolean, finished: Int = 3): String {
        val all = inOrder().filter { it.text.isNotBlank() }
        val shownDone = all.filter { it.final }.takeLast(finished).toSet()
        return all.filter { it in shownDone || (partials && !it.final) }.joinToString("\n") { it.text.trim() }
    }

    /** The original of the newest utterance that has one (still being spoken only with [partials]), with its language if known. */
    fun original(partials: Boolean): String {
        val u = inOrder().lastOrNull { it.source.isNotBlank() && (it.final || partials) } ?: return ""
        return labelled(u)
    }

    companion object {
        /** Finished utterances the history keeps. */
        const val HISTORY = 30

        /** Utterances of a session kept for the live page (it shows at most a few). */
        const val SESSION = 8

        /** "EN: Where is the station?" when the spoken language is known, else the text alone. */
        fun labelled(u: Utterance): String {
            val from = u.from?.takeIf { it.isNotBlank() && it != Languages.AUTO }
            return if (from == null) u.source.trim() else "${from.uppercase(Locale.ROOT)}: ${u.source.trim()}"
        }
    }
}

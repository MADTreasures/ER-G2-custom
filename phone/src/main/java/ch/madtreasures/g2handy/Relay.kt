package ch.madtreasures.g2handy

import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import java.io.InputStream

/**
 * The heart of G2 Handy: passes the connections of apps on the watch through to partner apps on this
 * phone (packages/fluency/PLATTFORM.md §6). The watch speaks [PhoneLinkWire] over the Wear OS Data Layer;
 * a partner is reached through [Partners] (Android IPC, see [MessengerPartners]). One connection per
 * partner: a new "open" replaces the one before, as when the watch reconnects after a break.
 *
 * Thread-safe: messages from the watch come on binder threads, partner events on the main thread.
 */
class Relay(
    private val watch: Watch,
    private val partners: Partners,
    /** runs an action after a delay, cancellable */
    private val after: (Long, () -> Unit) -> Cancel,
    /** the connections changed (status page, notification) */
    private val onChange: (List<Status>) -> Unit = {},
) {
    fun interface Watch {
        fun send(node: String, path: String, data: ByteArray)
    }

    fun interface Cancel {
        fun cancel()
    }

    /** How a partner app on the phone is reached. */
    fun interface Partners {
        /** Starts connecting to [partner] for [opener]; what happens next comes to [events]. */
        fun connect(partner: String, opener: Opener, events: PartnerEvents): PartnerConnection
    }

    interface PartnerEvents {
        fun opened()

        fun message(text: String)

        /** The partner refused or ended the connection, or is gone; [reason] is German, may be null. */
        fun closed(reason: String?)
    }

    interface PartnerConnection {
        fun send(text: String)

        /** The watch's sound from now on ([input]), or none any more ([input] null). */
        fun audio(input: InputStream?)

        /** Ends the connection from this side; no event follows. */
        fun close()
    }

    /** One connection, for the status page and the notification. */
    data class Status(val partner: String, val app: String, val open: Boolean)

    private inner class Link(val partner: String, val node: String, val opener: Opener) : PartnerEvents {
        var connection: PartnerConnection? = null
        var open = false
        var ended = false
        var timeout: Cancel? = null

        override fun opened() {
            synchronized(this@Relay) {
                if (!current(this) || open) return
                open = true
                timeout?.cancel()
                toWatch(this, Type.OPENED, ByteArray(0))
                changed()
            }
        }

        override fun message(text: String) {
            synchronized(this@Relay) {
                if (!current(this) || !open) return
                val bytes = PhoneLinkWire.text(text)
                if (bytes.size <= PhoneLinkWire.MAX_TEXT_BYTES) toWatch(this, Type.MSG, bytes)
            }
        }

        override fun closed(reason: String?) {
            synchronized(this@Relay) {
                if (!current(this)) return
                val wasOpen = open
                end(this, closeConnection = false)
                val why = reason ?: if (wasOpen) "Die App auf dem Handy hat die Verbindung beendet" else "Die App auf dem Handy nimmt keine Verbindung an"
                toWatch(this, if (wasOpen) Type.CLOSE else Type.FAILED, PhoneLinkWire.reason(why))
            }
        }
    }

    private val links = HashMap<String, Link>()

    /** A message from the watch ([node]) on [path]. */
    @Synchronized
    fun fromWatch(node: String, path: String, data: ByteArray) {
        val (partner, type) = PhoneLinkWire.parse(path) ?: return
        val link = links[partner]
        when (type) {
            Type.OPEN -> open(node, partner, PhoneLinkWire.readOpen(data) ?: return)
            Type.MSG -> when {
                link == null -> watch.send(node, PhoneLinkWire.path(partner, Type.CLOSE), PhoneLinkWire.reason(PhoneLinkWire.LOST))
                link.node == node && link.open -> link.connection?.send(PhoneLinkWire.readText(data))
            }
            Type.CLOSE -> if (link != null && link.node == node) end(link, closeConnection = true)
            else -> Unit
        }
    }

    /** The answer to the watch's ping on [path]: whether the connection still stands. */
    @Synchronized
    fun ping(node: String, path: String): ByteArray {
        val partner = PhoneLinkWire.parse(path)?.first
        val alive = links[partner]?.node == node
        return PhoneLinkWire.text(if (alive) PhoneLinkWire.ALIVE else PhoneLinkWire.GONE)
    }

    /** The watch opened its microphone channel for [partner]; the sound comes from [input]. */
    @Synchronized
    fun audio(node: String, partner: String, input: InputStream) {
        val link = links[partner]
        if (link == null || link.node != node || !link.open) {
            runCatching { input.close() }
            return
        }
        link.connection?.audio(input)
    }

    @Synchronized
    fun status(): List<Status> = links.values.map { Status(it.partner, it.opener.app, it.open) }.sortedBy { it.partner }

    private fun open(node: String, partner: String, opener: Opener) {
        links[partner]?.let { end(it, closeConnection = true) }
        val link = Link(partner, node, opener)
        links[partner] = link
        link.timeout = after(OPEN_TIMEOUT_MS) {
            synchronized(this) {
                if (current(link) && !link.open) {
                    end(link, closeConnection = true)
                    toWatch(link, Type.FAILED, PhoneLinkWire.reason("Die App auf dem Handy antwortet nicht"))
                }
            }
        }
        changed()
        val connection = partners.connect(partner, opener, link)
        if (link.ended) connection.close() else link.connection = connection
    }

    private fun end(link: Link, closeConnection: Boolean) {
        link.ended = true
        link.timeout?.cancel()
        if (links[link.partner] === link) links.remove(link.partner)
        if (closeConnection) link.connection?.close()
        changed()
    }

    private fun current(link: Link) = links[link.partner] === link

    private fun toWatch(link: Link, type: Type, data: ByteArray) = watch.send(link.node, PhoneLinkWire.path(link.partner, type), data)

    private fun changed() = onChange(status())

    companion object {
        /** How long a partner may take to accept a connection. */
        const val OPEN_TIMEOUT_MS = 10_000L
    }
}

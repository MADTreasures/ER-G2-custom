package ch.madtreasures.g2watch.apps.phone

import ch.madtreasures.g2watch.Scheduler
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import java.io.OutputStream

/**
 * The watch's side of the connections to apps on the phone (packages/fluency/PLATTFORM.md §6). It finds
 * G2 Handy over the Wear OS Data Layer, opens one connection per partner app ([PhoneLinkWire]), passes
 * the messages both ways, asks every [PhoneLinkWire.PING_MS] whether the connection still stands, and
 * streams the watch's microphone into a channel. The Data Layer is behind [Transport], the microphone
 * behind [Microphone]; all state lives on [scheduler]'s thread.
 */
class PhoneLinks(
    private val transport: Transport,
    private val microphone: Microphone,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
) : PhoneEngine {

    interface Transport {
        /** The node of the phone with G2 Handy, or null when none is reachable; [done] from any thread. */
        fun findPhone(done: (String?) -> Unit)

        /** Sends a message; [failed] (any thread) when it could not be delivered. */
        fun send(node: String, path: String, data: ByteArray, failed: () -> Unit)

        /** Asks the phone; [done] (any thread) gets the answer, or null without one. */
        fun request(node: String, path: String, data: ByteArray, done: (ByteArray?) -> Unit)

        /** Opens a channel to the phone; [done] (any thread) gets its stream (closing it ends the channel), or null. */
        fun openChannel(node: String, path: String, done: (OutputStream?) -> Unit)

        /** From now on, the phone's messages on [PhoneLinkWire.PREFIX] go to [received] (any thread). */
        fun listen(received: (node: String, path: String, data: ByteArray) -> Unit)
    }

    interface Microphone {
        /** Whether the watch app may record (the Android permission). */
        val allowed: Boolean

        /** Asks the wearer on the watch's screen; when they allow it, [microphoneAllowed] follows. */
        fun ask()

        /**
         * Records into [sink] in blocks of [PhoneLinkWire.BLOCK_BYTES] (PCM s16le, 16 kHz, mono) until
         * [Recording.stop], which also closes [sink]; [failed] (any thread) when the microphone gave up.
         */
        fun record(sink: OutputStream, failed: (String) -> Unit): Recording
    }

    fun interface Recording {
        fun stop()
    }

    private enum class Stage { FINDING, OPENING, OPEN, ENDED }

    private inner class Link(val app: String, val version: String, val partner: String, val listener: PhoneListener) : PhoneLine {
        var stage = Stage.FINDING
        var node: String? = null

        /** Raised when the stage changes: timers and answers of an earlier stage then do nothing. */
        var token = 0
        var missed = 0
        var micWanted = false
        var askedMic = false
        var channelPending = false
        var recording: Recording? = null

        override fun send(text: String) = scheduler.post { sendNow(this, text) }

        override fun microphone(on: Boolean) = scheduler.post {
            micWanted = on
            syncMic(this)
        }

        override fun close() = scheduler.post { end(this, reason = null, tellPhone = true, notify = false) }
    }

    /** The connections by partner. */
    private val links = HashMap<String, Link>()

    init {
        transport.listen { node, path, data -> scheduler.post { received(node, path, data) } }
    }

    override fun connect(app: String, version: String, partner: String, listener: PhoneListener): PhoneLine {
        val link = Link(app, version, partner, listener)
        scheduler.post { open(link) }
        return link
    }

    /** The wearer allowed the microphone: connections that want it get it now. */
    fun microphoneAllowed() = scheduler.post {
        for (link in links.values) {
            link.askedMic = false
            syncMic(link)
        }
    }

    private fun open(link: Link) {
        if (link.stage == Stage.ENDED) return // closed before it began
        links[link.partner]?.let { end(it, "Eine andere App hat die Verbindung übernommen", tellPhone = true, notify = true) }
        links[link.partner] = link
        val token = ++link.token
        scheduler.postDelayed(OPEN_TIMEOUT_MS) {
            if (link.token == token) end(link, "Das Handy antwortet nicht", tellPhone = true, notify = true)
        }
        transport.findPhone { node -> scheduler.post { found(link, token, node) } }
    }

    private fun found(link: Link, token: Int, node: String?) {
        if (link.token != token || link.stage != Stage.FINDING) return
        if (node == null) {
            end(link, "Kein Handy mit G2 Handy erreichbar", tellPhone = false, notify = true)
            return
        }
        link.node = node
        link.stage = Stage.OPENING
        transport.send(node, PhoneLinkWire.path(link.partner, Type.OPEN), PhoneLinkWire.open(Opener(link.app, link.version))) {
            scheduler.post { if (link.token == token) end(link, "Das Handy ist nicht erreichbar", tellPhone = false, notify = true) }
        }
    }

    private fun received(node: String, path: String, data: ByteArray) {
        val (partner, type) = PhoneLinkWire.parse(path) ?: return
        val link = links[partner]?.takeIf { it.node == node } ?: return
        when (type) {
            Type.OPENED -> if (link.stage == Stage.OPENING) {
                link.stage = Stage.OPEN
                val token = ++link.token
                log("Handy: verbunden mit ${link.partner}")
                link.listener.onState(PhoneState.CONNECTED, null, null)
                ping(link, token)
                syncMic(link)
            }
            Type.FAILED -> if (link.stage == Stage.OPENING) {
                end(link, PhoneLinkWire.readReason(data) ?: "Die App auf dem Handy nimmt keine Verbindung an", tellPhone = false, notify = true)
            }
            Type.MSG -> if (link.stage == Stage.OPEN) link.listener.onState(PhoneState.MESSAGE, PhoneLinkWire.readText(data), null)
            Type.CLOSE -> end(link, PhoneLinkWire.readReason(data) ?: "Die App auf dem Handy hat die Verbindung beendet", tellPhone = false, notify = true)
            else -> Unit
        }
    }

    private fun sendNow(link: Link, text: String) {
        if (link.stage != Stage.OPEN) return
        val node = link.node ?: return
        val bytes = PhoneLinkWire.text(text)
        if (bytes.size > PhoneLinkWire.MAX_TEXT_BYTES) return
        val token = link.token
        transport.send(node, PhoneLinkWire.path(link.partner, Type.MSG), bytes) {
            scheduler.post { if (link.token == token) end(link, "Eine Nachricht kam nicht beim Handy an", tellPhone = true, notify = true) }
        }
    }

    /** Asks every [PhoneLinkWire.PING_MS]; two pings without an answer end the connection. */
    private fun ping(link: Link, token: Int) {
        scheduler.postDelayed(PhoneLinkWire.PING_MS) {
            val node = link.node
            if (link.token != token || node == null) return@postDelayed
            transport.request(node, PhoneLinkWire.path(link.partner, Type.PING), ByteArray(0)) { answer ->
                scheduler.post { pinged(link, token, answer) }
            }
        }
    }

    private fun pinged(link: Link, token: Int, answer: ByteArray?) {
        if (link.token != token) return
        when {
            answer == null -> {
                link.missed++
                if (link.missed >= MAX_MISSED) end(link, "Keine Verbindung zum Handy", tellPhone = true, notify = true) else ping(link, token)
            }
            PhoneLinkWire.readText(answer) == PhoneLinkWire.GONE -> end(link, PhoneLinkWire.LOST, tellPhone = false, notify = true)
            else -> {
                link.missed = 0
                ping(link, token)
            }
        }
    }

    private fun syncMic(link: Link) {
        if (!link.micWanted || link.stage != Stage.OPEN) {
            stopMic(link)
            return
        }
        if (link.recording != null || link.channelPending) return
        if (!microphone.allowed) {
            if (!link.askedMic) {
                link.askedMic = true
                microphone.ask()
                link.listener.onNotice(MIC_NOT_ALLOWED)
            }
            return
        }
        val node = link.node ?: return
        val token = link.token
        link.channelPending = true
        transport.openChannel(node, PhoneLinkWire.path(link.partner, Type.AUDIO)) { out -> scheduler.post { channelOpened(link, token, out) } }
    }

    private fun channelOpened(link: Link, token: Int, out: OutputStream?) {
        link.channelPending = false
        if (out == null) {
            if (link.token == token) link.listener.onNotice("Der Ton der Uhr kommt nicht zum Handy")
            return
        }
        if (link.token != token || !link.micWanted || link.stage != Stage.OPEN || link.recording != null) {
            runCatching { out.close() }
            return
        }
        link.recording = microphone.record(out) { message ->
            scheduler.post {
                if (link.recording != null && link.token == token) {
                    link.recording = null
                    link.listener.onNotice(message)
                }
            }
        }
    }

    private fun stopMic(link: Link) {
        link.recording?.stop()
        link.recording = null
    }

    private fun end(link: Link, reason: String?, tellPhone: Boolean, notify: Boolean) {
        if (link.stage == Stage.ENDED) return
        val node = link.node
        link.stage = Stage.ENDED
        link.token++
        stopMic(link)
        if (links[link.partner] === link) links.remove(link.partner)
        if (tellPhone && node != null) transport.send(node, PhoneLinkWire.path(link.partner, Type.CLOSE), PhoneLinkWire.reason(null)) {}
        if (reason != null) log("Handy: ${link.partner} – $reason")
        if (notify) link.listener.onState(PhoneState.DISCONNECTED, null, reason)
    }

    companion object {
        /** How long finding the phone and the partner's answer may take together. */
        const val OPEN_TIMEOUT_MS = 15_000L

        /** Pings in a row without an answer before the connection counts as lost. */
        const val MAX_MISSED = 2

        const val MIC_NOT_ALLOWED = "Mikrofon der Uhr: bitte auf der Uhr erlauben"
    }
}

package ch.madtreasures.g2handy

import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** G2 Handy's relay between the watch and the partner apps, with a fake watch, fake partners and fake timers. */
class RelayTest {
    private class Timer(val delayMs: Long, val action: () -> Unit) {
        var cancelled = false

        fun fire() {
            if (!cancelled) action()
        }
    }

    private class FakeConnection(val partner: String, val opener: Opener, val events: Relay.PartnerEvents) : Relay.PartnerConnection {
        val sent = mutableListOf<String>()
        val audio = mutableListOf<InputStream?>()
        var closed = false

        override fun send(text: String) {
            sent += text
        }

        override fun audio(input: InputStream?) {
            audio += input
        }

        override fun close() {
            closed = true
        }
    }

    private class Input : ByteArrayInputStream(ByteArray(640)) {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    /** What went to the watch: node, partner, type and payload. */
    private class ToWatch(val node: String, val partner: String, val type: Type, val data: ByteArray) {
        val text get() = PhoneLinkWire.readText(data)
        val reason get() = PhoneLinkWire.readReason(data)
    }

    private val toWatch = mutableListOf<ToWatch>()
    private val timers = mutableListOf<Timer>()
    private val connections = mutableListOf<FakeConnection>()
    private val changes = mutableListOf<List<Relay.Status>>()

    /** When set, partners refuse at once, while [Relay.Partners.connect] still runs. */
    private var refuseAtOnce: String? = null

    private val relay = Relay(
        watch = { node, path, data ->
            val (partner, type) = PhoneLinkWire.parse(path)!!
            toWatch += ToWatch(node, partner, type, data)
        },
        partners = { partner, opener, events ->
            FakeConnection(partner, opener, events).also { c ->
                connections += c
                refuseAtOnce?.let { events.closed(it) }
            }
        },
        after = { delay, action -> Timer(delay, action).also { timers += it }.let { t -> Relay.Cancel { t.cancelled = true } } },
        onChange = { changes += it },
    )

    private fun fromWatch(type: Type, data: ByteArray = ByteArray(0), node: String = WATCH, partner: String = FLUENCY) =
        relay.fromWatch(node, PhoneLinkWire.path(partner, type), data)

    private fun open(node: String = WATCH): FakeConnection {
        fromWatch(Type.OPEN, PhoneLinkWire.open(Opener(APP, "1.1.0")), node)
        return connections.last()
    }

    /** Opens, and the partner accepts. */
    private fun opened(): FakeConnection = open().also { it.events.opened() }

    private fun last() = toWatch.last()

    @Test
    fun `an open from the watch reaches the partner, and its yes goes back`() {
        val c = open()
        assertEquals(FLUENCY, c.partner)
        assertEquals(Opener(APP, "1.1.0"), c.opener)
        assertTrue(toWatch.isEmpty())
        assertEquals(listOf(Relay.Status(FLUENCY, APP, open = false)), relay.status())

        c.events.opened()
        assertEquals(Triple(WATCH, FLUENCY, Type.OPENED), Triple(last().node, last().partner, last().type))
        assertEquals(listOf(Relay.Status(FLUENCY, APP, open = true)), relay.status())
        assertEquals(relay.status(), changes.last())
        assertTrue(timers.single().cancelled)
        c.events.opened() // twice is once
        assertEquals(1, toWatch.size)
    }

    @Test
    fun `messages go both ways, unchanged`() {
        val c = opened()
        fromWatch(Type.MSG, PhoneLinkWire.text("""{"t":"hello","ü":"ß"}"""))
        assertEquals(listOf("""{"t":"hello","ü":"ß"}"""), c.sent)

        c.events.message("""{"t":"caption","text":"Guten Tag"}""")
        assertEquals(Type.MSG, last().type)
        assertEquals("""{"t":"caption","text":"Guten Tag"}""", last().text)

        c.events.message("x".repeat(PhoneLinkWire.MAX_TEXT_BYTES + 1)) // too big for the watch app
        assertEquals(Type.MSG, last().type)
        assertEquals(2, toWatch.size)
    }

    @Test
    fun `before the partner said yes, and from another watch, messages do not pass`() {
        val c = open()
        fromWatch(Type.MSG, PhoneLinkWire.text("zu früh"))
        c.events.message("auch zu früh")
        assertTrue(c.sent.isEmpty())
        assertTrue(toWatch.isEmpty())

        c.events.opened()
        fromWatch(Type.MSG, PhoneLinkWire.text("fremd"), node = "other-watch")
        fromWatch(Type.CLOSE, node = "other-watch")
        assertTrue(c.sent.isEmpty())
        assertFalse(c.closed)
    }

    @Test
    fun `when the partner refuses, the watch hears why`() {
        val c = open()
        c.events.closed("Fluency: die Brille ist in den Einstellungen aus")
        assertEquals(Type.FAILED, last().type)
        assertEquals("Fluency: die Brille ist in den Einstellungen aus", last().reason)
        assertTrue(relay.status().isEmpty())

        open().events.closed(null)
        assertEquals("Die App auf dem Handy nimmt keine Verbindung an", last().reason)
    }

    @Test
    fun `a partner that refuses at once is told goodbye too`() {
        refuseAtOnce = "Fluency ist auf dem Handy nicht installiert oder nimmt keine Verbindung an"
        val c = open()
        assertEquals(Type.FAILED, last().type)
        assertEquals(refuseAtOnce, last().reason)
        assertTrue(c.closed)
        assertTrue(relay.status().isEmpty())
    }

    @Test
    fun `when the partner ends the connection, the watch hears it, and the partner's late words stay here`() {
        val c = opened()
        c.events.closed(null)
        assertEquals(Type.CLOSE, last().type)
        assertEquals("Die App auf dem Handy hat die Verbindung beendet", last().reason)
        assertFalse(c.closed) // it closed itself
        val count = toWatch.size
        c.events.message("spät")
        c.events.closed("nochmals")
        assertEquals(count, toWatch.size)
    }

    @Test
    fun `when the watch ends the connection, the partner hears it`() {
        val c = opened()
        val count = toWatch.size
        fromWatch(Type.CLOSE, PhoneLinkWire.reason(null))
        assertTrue(c.closed)
        assertEquals(count, toWatch.size)
        assertTrue(relay.status().isEmpty())
        assertEquals(emptyList<Relay.Status>(), changes.last())
    }

    @Test
    fun `a partner that does not answer in time is given up`() {
        val c = open()
        val timer = timers.single()
        assertEquals(Relay.OPEN_TIMEOUT_MS, timer.delayMs)
        timer.fire()
        assertEquals(Type.FAILED, last().type)
        assertEquals("Die App auf dem Handy antwortet nicht", last().reason)
        assertTrue(c.closed)
        c.events.opened() // too late
        assertEquals(1, toWatch.size)
    }

    @Test
    fun `a message for a connection G2 Handy does not know ends it on the watch`() {
        fromWatch(Type.MSG, PhoneLinkWire.text("hallo"))
        assertEquals(Triple(WATCH, FLUENCY, Type.CLOSE), Triple(last().node, last().partner, last().type))
        assertEquals(PhoneLinkWire.LOST, last().reason)
        assertTrue(connections.isEmpty())
    }

    @Test
    fun `a ping is answered alive only for the watch that holds the connection`() {
        val ping = PhoneLinkWire.path(FLUENCY, Type.PING)
        assertEquals(PhoneLinkWire.GONE, PhoneLinkWire.readText(relay.ping(WATCH, ping)))
        opened()
        assertEquals(PhoneLinkWire.ALIVE, PhoneLinkWire.readText(relay.ping(WATCH, ping)))
        assertEquals(PhoneLinkWire.GONE, PhoneLinkWire.readText(relay.ping("other-watch", ping)))
        assertEquals(PhoneLinkWire.GONE, PhoneLinkWire.readText(relay.ping(WATCH, PhoneLinkWire.path("com.example.other", Type.PING))))
        assertEquals(PhoneLinkWire.GONE, PhoneLinkWire.readText(relay.ping(WATCH, "/not/a/link")))
    }

    @Test
    fun `a new open replaces the connection before, as after a break`() {
        val first = opened()
        val second = open()
        assertTrue(first.closed)
        assertFalse(second.closed)
        val count = toWatch.size
        first.events.message("vom alten")
        first.events.closed("alt")
        assertEquals(count, toWatch.size)
        second.events.opened()
        assertEquals(Type.OPENED, last().type)
        assertEquals(1, relay.status().size)
    }

    @Test
    fun `the watch's sound goes to the partner only while the connection stands`() {
        val early = Input()
        val c = open()
        relay.audio(WATCH, FLUENCY, early)
        assertTrue(early.closed)
        assertTrue(c.audio.isEmpty())

        c.events.opened()
        val sound = Input()
        relay.audio(WATCH, FLUENCY, sound)
        assertEquals(listOf<InputStream?>(sound), c.audio)
        assertFalse(sound.closed)

        val stranger = Input()
        relay.audio("other-watch", FLUENCY, stranger)
        assertTrue(stranger.closed)
        assertEquals(1, c.audio.size)
    }

    @Test
    fun `what is no open of a link is ignored`() {
        relay.fromWatch(WATCH, "/g2/other/thing", ByteArray(0))
        fromWatch(Type.OPEN, "kein JSON".encodeToByteArray())
        fromWatch(Type.OPENED)
        assertTrue(connections.isEmpty())
        assertTrue(toWatch.isEmpty())
        assertNull(changes.lastOrNull())
    }

    private companion object {
        const val WATCH = "watch-node"
        const val FLUENCY = "ch.madtreasures.fluency"
        const val APP = "ch.madtreasures.fluency"
    }
}

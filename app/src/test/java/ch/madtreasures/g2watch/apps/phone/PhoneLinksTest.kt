package ch.madtreasures.g2watch.apps.phone

import ch.madtreasures.g2watch.FakeScheduler
import ch.madtreasures.g2watch.apps.PhoneState
import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** The watch's side of the connections to the phone, with a simulated Data Layer, microphone and time. */
class PhoneLinksTest {
    private val scheduler = FakeScheduler()

    /** The Data Layer as a test drives it. */
    private inner class FakeTransport : PhoneLinks.Transport {
        var phone: String? = PHONE
        val sent = mutableListOf<Triple<String, String, ByteArray>>()
        val failSends = mutableSetOf<Type>()
        val requests = mutableListOf<Pair<String, (ByteArray?) -> Unit>>()
        val channels = mutableListOf<Pair<String, (OutputStream?) -> Unit>>()
        var received: ((String, String, ByteArray) -> Unit)? = null

        override fun findPhone(done: (String?) -> Unit) = done(phone)

        override fun send(node: String, path: String, data: ByteArray, failed: () -> Unit) {
            sent += Triple(node, path, data)
            if (PhoneLinkWire.parse(path)?.second in failSends) failed()
        }

        override fun request(node: String, path: String, data: ByteArray, done: (ByteArray?) -> Unit) {
            requests += path to done
        }

        override fun openChannel(node: String, path: String, done: (OutputStream?) -> Unit) {
            channels += path to done
        }

        override fun listen(received: (node: String, path: String, data: ByteArray) -> Unit) {
            this.received = received
        }

        /** The phone sends [type] for [partner]. */
        fun fromPhone(type: Type, data: ByteArray = ByteArray(0), partner: String = FLUENCY, node: String = PHONE) {
            received!!(node, PhoneLinkWire.path(partner, type), data)
            scheduler.runPending()
        }

        fun sentTypes(): List<Type> = sent.mapNotNull { PhoneLinkWire.parse(it.second)?.second }
    }

    private inner class FakeMicrophone : PhoneLinks.Microphone {
        override var allowed = true
        var asked = 0
        val recordings = mutableListOf<Pair<OutputStream, (String) -> Unit>>()
        var stopped = 0

        override fun ask() {
            asked++
        }

        override fun record(sink: OutputStream, failed: (String) -> Unit): PhoneLinks.Recording {
            recordings += sink to failed
            return PhoneLinks.Recording { stopped++ }
        }
    }

    private class Heard : PhoneListener {
        val states = mutableListOf<Triple<PhoneState, String?, String?>>()
        val notices = mutableListOf<String>()

        override fun onState(state: PhoneState, text: String?, message: String?) {
            states += Triple(state, text, message)
        }

        override fun onNotice(message: String) {
            notices += message
        }
    }

    private val transport = FakeTransport()
    private val mic = FakeMicrophone()
    private val links = PhoneLinks(transport, mic, scheduler)

    private fun connect(partner: String = FLUENCY, heard: Heard = Heard()): Pair<PhoneLine, Heard> {
        val line = links.connect("ch.madtreasures.fluency", "1.1.0", partner, heard)
        scheduler.runPending()
        return line to heard
    }

    /** Connects and lets the phone accept. */
    private fun connected(): Pair<PhoneLine, Heard> {
        val (line, heard) = connect()
        transport.fromPhone(Type.OPENED)
        return line to heard
    }

    @Test
    fun `it opens through G2 Handy and passes messages both ways`() {
        val (line, heard) = connect()
        val (node, path, data) = transport.sent.single()
        assertEquals(PHONE, node)
        assertEquals("/g2/link/ch.madtreasures.fluency/open", path)
        assertEquals(Opener("ch.madtreasures.fluency", "1.1.0"), PhoneLinkWire.readOpen(data))
        assertTrue(heard.states.isEmpty())

        transport.fromPhone(Type.OPENED)
        assertEquals(listOf(Triple(PhoneState.CONNECTED, null, null)), heard.states)

        line.send("""{"t":"hello"}""")
        scheduler.runPending()
        assertEquals("/g2/link/ch.madtreasures.fluency/msg", transport.sent.last().second)
        assertEquals("""{"t":"hello"}""", PhoneLinkWire.readText(transport.sent.last().third))

        transport.fromPhone(Type.MSG, PhoneLinkWire.text("""{"t":"welcome"}"""))
        assertEquals(Triple(PhoneState.MESSAGE, """{"t":"welcome"}""", null), heard.states.last())
    }

    @Test
    fun `without a phone, or when the partner refuses, the app hears why`() {
        transport.phone = null
        val (_, none) = connect()
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Kein Handy mit G2 Handy erreichbar"), none.states.single())
        assertTrue(transport.sent.isEmpty())

        transport.phone = PHONE
        val (_, refused) = connect()
        transport.fromPhone(Type.FAILED, PhoneLinkWire.reason("Fluency ist auf dem Handy nicht installiert"))
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Fluency ist auf dem Handy nicht installiert"), refused.states.single())
    }

    @Test
    fun `without an answer it gives up after the open timeout`() {
        val (_, heard) = connect()
        scheduler.advanceBy(PhoneLinks.OPEN_TIMEOUT_MS - 1)
        assertTrue(heard.states.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Das Handy antwortet nicht"), heard.states.single())
        assertEquals(Type.CLOSE, transport.sentTypes().last())
        transport.fromPhone(Type.OPENED) // too late
        assertEquals(1, heard.states.size)
    }

    @Test
    fun `it pings every ten seconds, and two pings without an answer end the connection`() {
        val (_, heard) = connected()
        scheduler.advanceBy(PhoneLinkWire.PING_MS)
        assertEquals("/g2/link/ch.madtreasures.fluency/ping", transport.requests.single().first)
        transport.requests.removeAt(0).second(PhoneLinkWire.text(PhoneLinkWire.ALIVE))
        scheduler.advanceBy(PhoneLinkWire.PING_MS)
        transport.requests.removeAt(0).second(null)
        scheduler.advanceBy(PhoneLinkWire.PING_MS)
        assertEquals(1, heard.states.size) // one missed ping is not enough
        transport.requests.removeAt(0).second(null)
        scheduler.runPending()
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Keine Verbindung zum Handy"), heard.states.last())
        assertEquals(Type.CLOSE, transport.sentTypes().last())
        scheduler.advanceBy(3 * PhoneLinkWire.PING_MS)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a ping G2 Handy no longer knows ends the connection`() {
        val (_, heard) = connected()
        scheduler.advanceBy(PhoneLinkWire.PING_MS)
        transport.requests.removeAt(0).second(PhoneLinkWire.text(PhoneLinkWire.GONE))
        scheduler.runPending()
        assertEquals(Triple(PhoneState.DISCONNECTED, null, PhoneLinkWire.LOST), heard.states.last())
    }

    @Test
    fun `the partner ends the connection`() {
        val (line, heard) = connected()
        transport.fromPhone(Type.CLOSE, PhoneLinkWire.reason("Fluency wurde auf dem Handy beendet"))
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Fluency wurde auf dem Handy beendet"), heard.states.last())
        val sent = transport.sent.size
        line.send("noch da?")
        scheduler.runPending()
        assertEquals(sent, transport.sent.size)
    }

    @Test
    fun `when the app closes, the phone hears it and the app hears nothing more`() {
        val (line, heard) = connected()
        line.close()
        scheduler.runPending()
        assertEquals(Type.CLOSE, transport.sentTypes().last())
        transport.fromPhone(Type.MSG, PhoneLinkWire.text("spät"))
        assertEquals(1, heard.states.size)
    }

    @Test
    fun `a message that does not arrive ends the connection`() {
        val (line, heard) = connected()
        transport.failSends += Type.MSG
        line.send("hallo")
        scheduler.runPending()
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Eine Nachricht kam nicht beim Handy an"), heard.states.last())
    }

    @Test
    fun `messages of another phone or another partner are not for this connection`() {
        val (_, heard) = connected()
        transport.fromPhone(Type.MSG, PhoneLinkWire.text("fremd"), node = "other-node")
        transport.fromPhone(Type.MSG, PhoneLinkWire.text("fremd"), partner = "com.example.other")
        assertEquals(1, heard.states.size)
    }

    @Test
    fun `a second app taking the same partner ends the first connection`() {
        val (_, first) = connected()
        val (_, second) = connect()
        assertEquals(Triple(PhoneState.DISCONNECTED, null, "Eine andere App hat die Verbindung übernommen"), first.states.last())
        transport.fromPhone(Type.OPENED)
        assertEquals(PhoneState.CONNECTED, second.states.single().first)
    }

    @Test
    fun `the microphone streams into a channel while it is wanted`() {
        val (line, _) = connected()
        line.microphone(true)
        scheduler.runPending()
        assertEquals("/g2/link/ch.madtreasures.fluency/audio", transport.channels.single().first)
        val stream = ByteArrayOutputStream()
        transport.channels.single().second(stream)
        scheduler.runPending()
        assertEquals(stream, mic.recordings.single().first)

        line.microphone(false)
        scheduler.runPending()
        assertEquals(1, mic.stopped)
        line.microphone(true)
        scheduler.runPending()
        assertEquals(2, transport.channels.size) // a new channel for the next time
    }

    @Test
    fun `a microphone wanted before the connection stands starts with it, and ends with it`() {
        val (line, _) = connect()
        line.microphone(true)
        scheduler.runPending()
        assertTrue(transport.channels.isEmpty())
        transport.fromPhone(Type.OPENED)
        transport.channels.single().second(ByteArrayOutputStream())
        scheduler.runPending()
        assertEquals(1, mic.recordings.size)
        transport.fromPhone(Type.CLOSE)
        assertEquals(1, mic.stopped)
    }

    @Test
    fun `without the permission the wearer is asked once, and allowing it starts the microphone`() {
        mic.allowed = false
        val (line, heard) = connected()
        line.microphone(true)
        line.microphone(false)
        line.microphone(true)
        scheduler.runPending()
        assertEquals(1, mic.asked)
        assertEquals(listOf(PhoneLinks.MIC_NOT_ALLOWED), heard.notices)
        assertTrue(transport.channels.isEmpty())

        mic.allowed = true
        links.microphoneAllowed()
        scheduler.runPending()
        assertEquals(1, transport.channels.size)
    }

    @Test
    fun `a channel that does not open, or a microphone that gives up, is a notice and not the end`() {
        val (line, heard) = connected()
        line.microphone(true)
        scheduler.runPending()
        transport.channels.single().second(null)
        scheduler.runPending()
        assertEquals("Der Ton der Uhr kommt nicht zum Handy", heard.notices.single())

        line.microphone(false)
        line.microphone(true)
        scheduler.runPending()
        transport.channels.last().second(ByteArrayOutputStream())
        scheduler.runPending()
        mic.recordings.single().second("Das Mikrofon der Uhr ist nicht verfügbar")
        scheduler.runPending()
        assertEquals("Das Mikrofon der Uhr ist nicht verfügbar", heard.notices.last())
        assertEquals(PhoneState.CONNECTED, heard.states.single().first)
    }

    @Test
    fun `a channel that opens after the microphone was turned off is closed again`() {
        val (line, _) = connected()
        line.microphone(true)
        scheduler.runPending()
        line.microphone(false)
        scheduler.runPending()
        var closed = false
        transport.channels.single().second(object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun close() {
                closed = true
            }
        })
        scheduler.runPending()
        assertTrue(closed)
        assertTrue(mic.recordings.isEmpty())
    }

    @Test
    fun `closed before it began, nothing goes to the phone`() {
        val line = links.connect("a.b", "1.0.0", FLUENCY, Heard())
        line.close()
        scheduler.runPending()
        assertTrue(transport.sent.isEmpty())
        assertNull(transport.requests.firstOrNull())
        assertFalse(transport.channels.any())
    }

    private companion object {
        const val PHONE = "phone-node"
        const val FLUENCY = "ch.madtreasures.fluency"
    }
}

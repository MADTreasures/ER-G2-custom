package ch.madtreasures.g2handy

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import ch.madtreasures.g2handy.MessengerPartners.Companion.ACTION_LINK
import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/** G2 Handy reaches a partner app over Android IPC: a fake partner service with a [Messenger], as Fluency has one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessengerPartnersTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    /** The partner's service: keeps what it got, answers through the reply messenger. */
    private class FakePartner {
        val got = mutableListOf<Pair<Int, Bundle>>()
        var replyTo: Messenger? = null
        val messenger = Messenger(
            Handler(Looper.getMainLooper()) { m ->
                val data = Bundle(m.data)
                // Between two processes Binder gives the partner its own copy of the pipe's end; G2 Handy may close its own.
                @Suppress("DEPRECATION")
                data.getParcelable<ParcelFileDescriptor>(MessengerPartners.KEY_PFD)?.let { data.putParcelable(MessengerPartners.KEY_PFD, it.dup()) }
                got += m.what to data
                replyTo = m.replyTo
                true
            },
        )

        fun answer(what: Int, data: Bundle = Bundle()) = replyTo!!.send(Message.obtain(null, what).apply { this.data = data })

        fun whats() = got.map { it.first }
    }

    private class Events : Relay.PartnerEvents {
        var opened = 0
        val messages = mutableListOf<String>()
        val closed = mutableListOf<String?>()

        override fun opened() {
            opened++
        }

        override fun message(text: String) {
            messages += text
        }

        override fun closed(reason: String?) {
            closed += reason
        }
    }

    private val partner = FakePartner()
    private val events = Events()
    private val partners = MessengerPartners(app)

    @Before
    fun setUp() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = FLUENCY
                applicationInfo = ApplicationInfo().apply {
                    packageName = FLUENCY
                    name = "Fluency"
                    nonLocalizedLabel = "Fluency"
                }
            },
        )
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(ACTION_LINK).setPackage(FLUENCY),
            ComponentName(FLUENCY, "$FLUENCY.glasses.GlassesService"),
            partner.messenger.binder,
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun connect(): Relay.PartnerConnection =
        partners.connect(FLUENCY, Opener(WATCH_APP, "1.1.0"), events).also { idle() }

    private fun opened(): Relay.PartnerConnection = connect().also {
        partner.answer(MessengerPartners.OPENED)
        idle()
    }

    @Test
    fun `it binds the partner's link service and says who connects`() {
        connect()
        val (what, data) = partner.got.single()
        assertEquals(MessengerPartners.OPEN, what)
        assertEquals(WATCH_APP, data.getString(MessengerPartners.KEY_APP))
        assertEquals("1.1.0", data.getString(MessengerPartners.KEY_VERSION))
        assertEquals(0, events.opened)

        partner.answer(MessengerPartners.OPENED)
        idle()
        assertEquals(1, events.opened)
    }

    @Test
    fun `messages go both ways`() {
        val c = opened()
        c.send("""{"t":"hello"}""")
        idle()
        assertEquals(MessengerPartners.MSG, partner.got.last().first)
        assertEquals("""{"t":"hello"}""", partner.got.last().second.getString(MessengerPartners.KEY_TEXT))

        partner.answer(MessengerPartners.MSG, Bundle().apply { putString(MessengerPartners.KEY_TEXT, """{"t":"caption"}""") })
        idle()
        assertEquals(listOf("""{"t":"caption"}"""), events.messages)
    }

    @Test
    fun `closing tells the partner and lets go of it, without an event`() {
        val c = opened()
        c.close()
        idle()
        assertEquals(MessengerPartners.CLOSE, partner.got.last().first)
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        assertTrue(events.closed.isEmpty())
        c.send("danach")
        idle()
        assertEquals(MessengerPartners.CLOSE, partner.got.last().first)
    }

    @Test
    fun `a partner that refuses or ends says why`() {
        connect()
        partner.answer(MessengerPartners.CLOSE, Bundle().apply { putString(MessengerPartners.KEY_MESSAGE, "Fluency: Brille in den Einstellungen einschalten") })
        idle()
        assertEquals(listOf<String?>("Fluency: Brille in den Einstellungen einschalten"), events.closed)
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        partner.answer(MessengerPartners.OPENED) // after the end
        idle()
        assertEquals(0, events.opened)
    }

    @Test
    fun `a partner that is not there, or whose process ends, is named for the wearer`() {
        shadowOf(app).declareActionUnbindable(ACTION_LINK)
        partners.connect(FLUENCY, Opener(WATCH_APP, "1.1.0"), events)
        idle()
        assertEquals(listOf<String?>("Fluency ist auf dem Handy nicht installiert oder nimmt keine Verbindung an"), events.closed)
    }

    @Test
    fun `when the partner's process ends, the relay hears it`() {
        opened()
        val connection = shadowOf(app).boundServiceConnections.single()
        connection.onServiceDisconnected(ComponentName(FLUENCY, "$FLUENCY.glasses.GlassesService"))
        idle()
        assertEquals(listOf<String?>("Fluency wurde auf dem Handy beendet"), events.closed)
    }

    @Test
    fun `the watch's sound reaches the partner through a pipe`() {
        val c = opened()
        val sound = ByteArray(5 * PhoneLinkWire.BLOCK_BYTES) { (it % 251).toByte() }
        c.audio(ByteArrayInputStream(sound))
        // The pump writes on its own thread; when the watch's stream has ended, G2 Handy stops the sound.
        val until = System.currentTimeMillis() + 5_000
        while (MessengerPartners.AUDIO_STOP !in partner.whats() && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(10)
        }
        assertEquals(listOf(MessengerPartners.AUDIO_START, MessengerPartners.AUDIO_STOP), partner.whats().takeLast(2))
        val (_, data) = partner.got.first { it.first == MessengerPartners.AUDIO_START }
        assertEquals(PhoneLinkWire.SAMPLE_RATE, data.getInt(MessengerPartners.KEY_RATE))
        @Suppress("DEPRECATION")
        val pipe = data.getParcelable<ParcelFileDescriptor>(MessengerPartners.KEY_PFD)!!
        val heard = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
        assertArrayEquals(sound, heard)
    }

    /** The watch's channel while the watch says nothing: reading waits until it is closed. */
    private class Silence : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("closed")
        }

        override fun close() = closed.countDown()
    }

    @Test
    fun `turning the sound off stops it at the partner and lets go of the watch's channel`() {
        val c = opened()
        val silence = Silence()
        c.audio(silence)
        idle()
        assertEquals(MessengerPartners.AUDIO_START, partner.got.last().first)
        c.audio(null)
        idle()
        assertEquals(MessengerPartners.AUDIO_STOP, partner.got.last().first)
        assertEquals(0L, silence.closed.count)
        idle()
        assertEquals(1, partner.whats().count { it == MessengerPartners.AUDIO_STOP })
    }

    @Test
    fun `closing the connection ends the sound too`() {
        val c = opened()
        val silence = Silence()
        c.audio(silence)
        idle()
        c.close()
        idle()
        assertEquals(listOf(MessengerPartners.AUDIO_STOP, MessengerPartners.CLOSE), partner.whats().takeLast(2))
        assertEquals(0L, silence.closed.count)
    }

    private companion object {
        const val FLUENCY = "ch.madtreasures.fluency"
        const val WATCH_APP = "ch.madtreasures.fluency"
    }
}

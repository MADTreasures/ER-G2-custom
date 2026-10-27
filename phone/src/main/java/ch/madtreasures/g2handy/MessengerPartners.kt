package ch.madtreasures.g2handy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Opener
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * How a partner app on the phone is reached (packages/fluency/PLATTFORM.md §6.2): the partner exports a
 * service with the action [ACTION_LINK]; G2 Handy binds it and talks to it with [Messenger] messages.
 * Fluency (MADTreasures/playground, `glasses/GlassesService`) is such a partner.
 *
 * | `what` | Value | Direction | Bundle |
 * |---|---|---|---|
 * | [OPEN] | 1 | G2 Handy → partner | [KEY_APP], [KEY_VERSION] |
 * | [OPENED] | 2 | partner → G2 Handy | – |
 * | [MSG] | 3 | both | [KEY_TEXT] |
 * | [CLOSE] | 4 | both | [KEY_MESSAGE]: why (optional); instead of [OPENED] when the partner refuses |
 * | [AUDIO_START] | 5 | G2 Handy → partner | [KEY_PFD]: read end of a pipe with the watch's sound, [KEY_RATE] |
 * | [AUDIO_STOP] | 6 | G2 Handy → partner | – |
 *
 * Every message carries [Message.replyTo]; the partner checks the caller ([Message.sendingUid]).
 * Everything of one connection happens on the main thread, in the order it was asked for.
 */
class MessengerPartners(private val context: Context) : Relay.Partners {

    override fun connect(partner: String, opener: Opener, events: Relay.PartnerEvents): Relay.PartnerConnection =
        Connection(partner, opener, events).also { c -> main.post { c.bind() } }

    private val main = Handler(Looper.getMainLooper())

    /** The partner's name for the wearer, e.g. "Fluency". */
    private fun label(partner: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(partner, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        partner
    }

    private inner class Connection(
        private val partner: String,
        private val opener: Opener,
        private val events: Relay.PartnerEvents,
    ) : ServiceConnection, Relay.PartnerConnection {
        private val replies = Messenger(Handler(Looper.getMainLooper()) { m -> receive(m); true })
        private var service: Messenger? = null
        private var bound = false
        private var done = false
        private var pump: Thread? = null
        private var pipe: Array<ParcelFileDescriptor>? = null
        private var sound: InputStream? = null

        fun bind() {
            if (done) return
            val intent = Intent(ACTION_LINK).setPackage(partner)
            bound = try {
                context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!bound) finish("${label(partner)} ist auf dem Handy nicht installiert oder nimmt keine Verbindung an")
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (done) return
            service = Messenger(binder)
            post(OPEN, Bundle().apply {
                putString(KEY_APP, opener.app)
                putString(KEY_VERSION, opener.version)
            })
        }

        override fun onServiceDisconnected(name: ComponentName) = finish("${label(partner)} wurde auf dem Handy beendet")

        override fun onBindingDied(name: ComponentName) = finish("${label(partner)} wurde auf dem Handy beendet")

        override fun onNullBinding(name: ComponentName) = finish("${label(partner)} nimmt keine Verbindung an")

        private fun receive(m: Message) {
            if (done) return
            when (m.what) {
                OPENED -> events.opened()
                MSG -> m.data.getString(KEY_TEXT)?.let(events::message)
                CLOSE -> finish(m.data.getString(KEY_MESSAGE))
            }
        }

        override fun send(text: String) {
            main.post { if (!done) post(MSG, Bundle().apply { putString(KEY_TEXT, text) }) }
        }

        override fun audio(input: InputStream?) {
            main.post {
                stopAudio()
                if (input == null) return@post
                if (done || service == null) {
                    runCatching { input.close() }
                    return@post
                }
                val (read, write) = ParcelFileDescriptor.createPipe()
                pipe = arrayOf(read, write)
                sound = input
                post(AUDIO_START, Bundle().apply {
                    putParcelable(KEY_PFD, read)
                    putInt(KEY_RATE, PhoneLinkWire.SAMPLE_RATE)
                })
                pump = thread(name = "g2handy-audio", isDaemon = true) {
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
                    try {
                        input.copyTo(out, PhoneLinkWire.BLOCK_BYTES)
                    } catch (e: IOException) {
                        // the watch or the partner ended the stream
                    } finally {
                        runCatching { input.close() }
                        runCatching { out.close() }
                        val me = Thread.currentThread()
                        main.post {
                            if (pump === me) {
                                pump = null
                                stopAudio()
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            main.post {
                if (done) return@post
                stopAudio()
                post(CLOSE, Bundle())
                done = true
                unbind()
            }
        }

        /** The partner's side ended the connection: tell the relay. */
        private fun finish(reason: String?) {
            if (done) return
            done = true
            stopAudio()
            unbind()
            events.closed(reason)
        }

        private fun stopAudio() {
            val p = pipe ?: return
            pipe = null
            pump = null
            sound?.let { runCatching { it.close() } } // ends a pump that waits for the watch
            sound = null
            p.forEach { runCatching { it.close() } } // ends a pump that waits to write
            post(AUDIO_STOP, Bundle())
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            runCatching { context.unbindService(this) }
        }

        private fun post(what: Int, data: Bundle) {
            val s = service ?: return
            val m = Message.obtain(null, what).apply {
                this.data = data
                replyTo = replies
            }
            try {
                s.send(m)
            } catch (e: RemoteException) {
                finish("${label(partner)} wurde auf dem Handy beendet")
            }
        }
    }

    companion object {
        /** The intent action of a partner's service. */
        const val ACTION_LINK = "ch.madtreasures.g2watch.action.LINK"

        const val OPEN = 1
        const val OPENED = 2
        const val MSG = 3
        const val CLOSE = 4
        const val AUDIO_START = 5
        const val AUDIO_STOP = 6

        const val KEY_APP = "app"
        const val KEY_VERSION = "version"
        const val KEY_TEXT = "text"
        const val KEY_MESSAGE = "message"
        const val KEY_PFD = "pfd"
        const val KEY_RATE = "rate"
    }
}

package ch.madtreasures.g2watch.apps.phone

import android.content.Context
import androidx.core.net.toUri
import ch.madtreasures.g2watch.link.PhoneLinkWire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * [PhoneLinks.Transport] over the Wear OS Data Layer (Google Play Services): G2 Handy is the node with
 * the capability [PhoneLinkWire.PHONE_CAPABILITY]; messages, requests and the sound channel go to it.
 * It only reaches an app with the watch app's id and key, which G2 Handy has (signing/README.md).
 */
class WearTransport(context: Context, private val log: (String) -> Unit) : PhoneLinks.Transport {
    private val context = context.applicationContext
    private val messages: MessageClient by lazy { Wearable.getMessageClient(this.context) }
    private val channels: ChannelClient by lazy { Wearable.getChannelClient(this.context) }

    override fun findPhone(done: (String?) -> Unit) {
        Wearable.getCapabilityClient(context).getCapability(PhoneLinkWire.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info -> done((info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull())?.id) }
            .addOnFailureListener { e ->
                log("Handy: Datenschicht nicht erreichbar – ${e.message}")
                done(null)
            }
    }

    override fun send(node: String, path: String, data: ByteArray, failed: () -> Unit) {
        messages.sendMessage(node, path, data).addOnFailureListener { failed() }
    }

    override fun request(node: String, path: String, data: ByteArray, done: (ByteArray?) -> Unit) {
        messages.sendRequest(node, path, data)
            .addOnSuccessListener { answer -> done(answer) }
            .addOnFailureListener { done(null) }
    }

    override fun openChannel(node: String, path: String, done: (OutputStream?) -> Unit) {
        channels.openChannel(node, path)
            .addOnSuccessListener { channel ->
                channels.getOutputStream(channel)
                    .addOnSuccessListener { out ->
                        // Closing the stream ends the channel too.
                        done(object : FilterOutputStream(out) {
                            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                            override fun close() {
                                runCatching { super.close() }
                                channels.close(channel)
                            }
                        })
                    }
                    .addOnFailureListener {
                        channels.close(channel)
                        done(null)
                    }
            }
            .addOnFailureListener { e ->
                log("Handy: Ton-Kanal ging nicht auf – ${e.message}")
                done(null)
            }
    }

    override fun listen(received: (node: String, path: String, data: ByteArray) -> Unit) {
        messages.addListener(
            { event -> received(event.sourceNodeId, event.path, event.data) },
            "wear://*${PhoneLinkWire.PREFIX}".toUri(),
            MessageClient.FILTER_PREFIX,
        )
    }
}

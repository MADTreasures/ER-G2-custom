package ch.madtreasures.g2handy

import android.util.Log
import ch.madtreasures.g2watch.link.PhoneLinkWire
import ch.madtreasures.g2watch.link.PhoneLinkWire.Type
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Where the watch reaches G2 Handy: Google Play Services wakes this service for the messages, pings and
 * the sound channel on `/g2/link/…` ([PhoneLinkWire]) and hands them to the [Relay].
 */
class LinkListenerService : WearableListenerService() {
    private val relay get() = G2HandyApp.of(this).relay

    override fun onMessageReceived(event: MessageEvent) {
        val (_, type) = PhoneLinkWire.parse(event.path) ?: return
        // While a connection stands, the foreground service keeps G2 Handy awake (if Android lets it start here).
        if (type == Type.OPEN) RelayService.start(this)
        relay.fromWatch(event.sourceNodeId, event.path, event.data)
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        val (_, type) = PhoneLinkWire.parse(path) ?: return null
        if (type != Type.PING) return null
        return Tasks.forResult(relay.ping(nodeId, path))
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val (partner, type) = PhoneLinkWire.parse(channel.path) ?: return
        if (type != Type.AUDIO) return
        val node = channel.nodeId
        Wearable.getChannelClient(this).getInputStream(channel)
            .addOnSuccessListener { input -> relay.audio(node, partner, input) }
            .addOnFailureListener { e -> Log.w(G2HandyApp.TAG, "sound channel of the watch failed", e) }
    }
}

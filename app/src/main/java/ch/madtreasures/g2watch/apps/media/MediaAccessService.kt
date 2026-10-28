package ch.madtreasures.g2watch.apps.media

import android.service.notification.NotificationListenerService

/**
 * The key to the watch's media controls (03 §15): Android lets an app read the players' sessions once the
 * wearer allowed it as a notification listener. This listener reads no notifications; it only tells
 * [AndroidMediaEngine] when the access came or went.
 */
class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() = AndroidMediaEngine.accessChanged()

    override fun onListenerDisconnected() = AndroidMediaEngine.accessChanged()
}

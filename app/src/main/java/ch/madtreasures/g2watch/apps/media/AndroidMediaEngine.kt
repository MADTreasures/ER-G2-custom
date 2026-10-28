package ch.madtreasures.g2watch.apps.media

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import ch.madtreasures.g2watch.apps.MediaAction
import ch.madtreasures.g2watch.apps.MediaInfo
import ch.madtreasures.g2watch.apps.MediaState
import ch.madtreasures.g2watch.desktop.GrayRaster
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * [MediaEngine] on the watch (03 §15): the players' media sessions through [MediaSessionManager], which the
 * watch app may read once the wearer allowed it as a notification listener ([MediaAccessService]); it reads no
 * notifications. Without that access the media keys of [AudioManager] still play, pause and skip in the player
 * that played last, and the volume works. Sessions, volume and headphones are followed on the thread
 * "G2Watch-media", only while an app follows them.
 */
class AndroidMediaEngine(context: Context, private val log: (String) -> Unit) : MediaEngine {
    private val context = context.applicationContext
    private val sessions = this.context.getSystemService(MediaSessionManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val component = ComponentName(this.context, MediaAccessService::class.java)
    private val thread = HandlerThread("G2Watch-media").apply { start() }
    private val handler = Handler(thread.looper)
    private val followers = CopyOnWriteArrayList<MediaListener>()

    /** The sessions as the media thread last saw them, for commands from the app thread. */
    @Volatile
    private var controllers: List<MediaController> = emptyList()

    // Only on the media thread.
    private var watching = false
    private var sessionsAttached = false
    private var reportPending = false
    private val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val labels = HashMap<String, String>()
    private val artworks = HashMap<String, Pair<String, String?>>()

    init {
        engines += this
    }

    override fun follow(listener: MediaListener): MediaLine {
        followers += listener
        handler.post {
            watch()
            listener.onChange(snapshot())
        }
        return MediaLine {
            followers.remove(listener)
            handler.post { if (followers.isEmpty()) unwatch() }
        }
    }

    override fun command(action: MediaAction): String? = when (action) {
        MediaAction.Follow, MediaAction.Unfollow -> null
        is MediaAction.Play -> transport(action.app, KeyEvent.KEYCODE_MEDIA_PLAY) { it.play() }
        is MediaAction.Pause -> transport(action.app, KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
        is MediaAction.PlayPause -> playPause(action.app)
        is MediaAction.Next -> transport(action.app, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
        is MediaAction.Previous -> transport(action.app, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }
        is MediaAction.Seek -> controllerFor(action.app)?.let {
            it.transportControls.seekTo(action.positionMs)
            null
        } ?: NO_PLAYER
        is MediaAction.Search -> search(action.query, action.app)
        is MediaAction.Open -> open(action.app)
        is MediaAction.Volume -> volume(action.steps)
    }

    // --- Following -------------------------------------------------------------------------------------

    private fun watch() {
        if (watching) return
        watching = true
        audio?.registerAudioDeviceCallback(devices, handler)
        // The volume lives in the system settings; a change there may be the volume.
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settings)
        attachSessions()
    }

    private fun unwatch() {
        if (!watching) return
        watching = false
        audio?.unregisterAudioDeviceCallback(devices)
        context.contentResolver.unregisterContentObserver(settings)
        detachSessions()
    }

    private fun attachSessions() {
        val manager = sessions ?: return
        if (!hasAccess()) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            sessionsAttached = true
            setControllers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            log("Musik: Mediensteuerung nicht lesbar – ${e.message}")
        }
    }

    private fun detachSessions() {
        if (sessionsAttached) {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
            sessionsAttached = false
        }
        setControllers(emptyList())
    }

    /** The wearer allowed or took back the access: look again. */
    private fun accessChanged() {
        if (!watching) return
        detachSessions()
        attachSessions()
        report()
    }

    private fun hasAccess(): Boolean = try {
        notifications?.isNotificationListenerAccessGranted(component) == true
    } catch (e: SecurityException) {
        false
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list.orEmpty()) }

    private fun setControllers(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val gone = callbacks.keys.filter { it !in tokens }
        for (token in gone) callbacks.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        for (c in list) {
            if (c.sessionToken in callbacks) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = report()

                override fun onMetadataChanged(metadata: MediaMetadata?) = report()

                override fun onSessionDestroyed() = report()
            }
            c.registerCallback(cb, handler)
            callbacks[c.sessionToken] = c to cb
        }
        controllers = list
        report()
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = report()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = report()
    }

    private val settings = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = report()
    }

    /** One report for a burst of changes (a new song changes state and metadata at once). */
    private fun report() {
        if (reportPending || followers.isEmpty()) return
        reportPending = true
        handler.postDelayed({
            reportPending = false
            val snapshot = snapshot()
            followers.forEach { it.onChange(snapshot) }
        }, REPORT_DELAY_MS)
    }

    private fun snapshot(): MediaSnapshot {
        val players = controllers.mapNotNull { runCatching { info(it) }.getOrNull() }
            // Android's order (the most important first), but what plays before what does not.
            .sortedBy { if (it.state == MediaState.PLAYING) 0 else 1 }
        val message = when {
            sessions == null || audio == null -> MediaEngine.NO_MEDIA
            !hasAccess() -> NO_ACCESS
            else -> null
        }
        return MediaSnapshot(
            players,
            audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
            audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0,
            output(),
            message,
        )
    }

    private fun info(c: MediaController): MediaInfo? {
        val app = c.packageName ?: return null
        val playback = c.playbackState
        val meta = c.metadata
        val actions = playback?.actions ?: 0L
        val known = actions != 0L
        val updated = playback?.lastPositionUpdateTime ?: 0L
        val positionAt = if (updated > 0) System.currentTimeMillis() - (SystemClock.elapsedRealtime() - updated) else 0L
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        return MediaInfo(
            app = app,
            appName = label(app),
            state = state(playback?.state),
            title = title,
            artist = artist,
            album = album,
            durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0),
            positionMs = (playback?.position ?: 0L).coerceAtLeast(0),
            positionAt = positionAt,
            speed = playback?.playbackSpeed ?: 1f,
            artwork = artwork(app, title, artist, album, meta),
            canNext = !known || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPrevious = !known || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canSearch = actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L,
        )
    }

    private fun state(state: Int?): MediaState = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> MediaState.PLAYING
        PlaybackState.STATE_PAUSED -> MediaState.PAUSED
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> MediaState.BUFFERING
        PlaybackState.STATE_ERROR -> MediaState.ERROR
        else -> MediaState.STOPPED
    }

    private fun label(app: String): String = labels.getOrPut(app) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            app.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    /** The cover in grey, converted once per song. */
    private fun artwork(app: String, title: String?, artist: String?, album: String?, meta: MediaMetadata?): String? {
        val bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val key = "$title|$artist|$album|${bitmap.width}x${bitmap.height}"
        artworks[app]?.let { (k, url) -> if (k == key) return url }
        val url = picture(bitmap)
        artworks[app] = key to url
        return url
    }

    private fun picture(source: Bitmap): String? = try {
        val bitmap = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val size = MediaInfo.ARTWORK_SIZE.toFloat()
        val scale = minOf(size / bitmap.width, size / bitmap.height, 1f)
        val w = maxOf(1, (bitmap.width * scale).toInt())
        val h = maxOf(1, (bitmap.height * scale).toInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val argb = IntArray(w * h)
        small.getPixels(argb, 0, w, 0, 0, w, h)
        val grey = GrayRaster(w, h)
        for (i in argb.indices) {
            val c = argb[i]
            val alpha = c ushr 24 and 0xFF
            val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            grey.pixels[i] = (luma * alpha / 255).toByte()
        }
        GrayPng.dataUrl(grey)
    } catch (e: RuntimeException) {
        log("Musik: Cover nicht lesbar – ${e.message}")
        null
    }

    /** Where the sound goes: headphones first, then the watch's speaker. */
    private fun output(): String? {
        val outputs = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
        outputs.firstOrNull { it.type in HEADPHONES }?.let { d ->
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
            return if (name == null) "Kopfhörer" else "Kopfhörer: $name"
        }
        if (outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) return "Lautsprecher der Uhr"
        return null
    }

    // --- Commands --------------------------------------------------------------------------------------

    /** The sessions now: those the media thread follows, or (nobody follows) asked for once. */
    private fun current(): List<MediaController> {
        if (watching) return controllers
        if (sessions == null || !hasAccess()) return emptyList()
        return try {
            sessions.getActiveSessions(component)
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun controllerFor(app: String?): MediaController? {
        val list = current()
        if (app != null) return list.firstOrNull { it.packageName == app }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }

    /** Through the player's session; without one, as the media key [code] to the player that played last. */
    private fun transport(app: String?, code: Int, use: (MediaController.TransportControls) -> Unit): String? {
        controllerFor(app)?.let {
            use(it.transportControls)
            return null
        }
        return key(code)
    }

    /** Pauses the player if it plays (or is about to), else plays; without a session, the play/pause key. */
    private fun playPause(app: String?): String? {
        controllerFor(app)?.let {
            when (it.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> it.transportControls.pause()
                else -> it.transportControls.play()
            }
            return null
        }
        return key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    private fun key(code: Int): String? {
        val a = audio ?: return MediaEngine.NO_MEDIA
        val now = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return null
    }

    /**
     * As a voice assistant asks: in the running player's session, else with the intent players answer. The
     * watch app starts it from its service, not an activity, so Android requires a task of its own.
     */
    @SuppressLint("WearRecents")
    private fun search(query: String, app: String): String? {
        controllerFor(app)?.let {
            it.transportControls.playFromSearch(query, Bundle())
            return null
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "${label(app)} kann so nicht suchen"
        } catch (e: RuntimeException) {
            "${label(app)} ließ sich nicht fragen: ${e.message}"
        }
    }

    /** The player's app on the watch's screen, in its own task, as the watch's launcher would open it. */
    @SuppressLint("WearRecents")
    private fun open(app: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(app) ?: return "„$app“ ist auf der Uhr nicht installiert"
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: RuntimeException) {
            "${label(app)} ließ sich nicht öffnen: ${e.message}"
        }
    }

    private fun volume(steps: Int): String? {
        val a = audio ?: return MediaEngine.NO_MEDIA
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(abs(steps)) { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        handler.post { report() }
        return null
    }

    companion object {
        /** On the glasses when the wearer has not allowed the access yet (03 §15 says how). */
        const val NO_ACCESS = "Musik der Uhr: Zugriff noch nicht erlaubt"
        const val NO_PLAYER = "Es spielt nichts"
        private const val REPORT_DELAY_MS = 150L

        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        private val engines = CopyOnWriteArrayList<AndroidMediaEngine>()

        /** The notification listener came or went ([MediaAccessService]). */
        internal fun accessChanged() = engines.forEach { e -> e.handler.post { e.accessChanged() } }
    }
}

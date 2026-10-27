package ch.madtreasures.g2watch.apps

import java.io.File

/**
 * Large files of an app (maps, media, indexes) in a folder of its own on the watch ([AppContext.files],
 * 03 §14). Unlike [AppStorage] there is no limit but the watch's free space. The folder stays across
 * restarts, updates and a removal of the app, so an app deletes what it no longer needs itself. The app
 * reads and writes its files with java.io, big ones on a background thread ([AppContext.work]). Since
 * interface version 5.
 */
interface AppFiles {
    /** The app's folder; it exists. Null where the host keeps no files (then [download] is refused). */
    val dir: File?

    /** Bytes still free on the watch's storage. */
    val freeBytes: Long

    /**
     * Loads [url] (HTTPS) into the file [name] of [dir] off the app thread. [AppEvent.Download] with [tag]
     * reports how far it got, about once a second, and then how it ended. Until it is complete the file is
     * called "[name].part"; a download of the same [name] that broke off goes on where it stopped, if the
     * server allows. [name] is a path inside [dir] ([isValidName]). [wifiOnly]: only over Wi-Fi, as big files
     * should go (the watch's Bluetooth link to the phone is slow, LTE may cost); otherwise Wi-Fi or LTE.
     * Needs [Permission.NETWORK]. Starting [tag] again replaces its running download. A download goes on
     * while the app is hidden and ends with the app.
     */
    fun download(tag: String, url: String, name: String, wifiOnly: Boolean = true)

    /** Stops download [tag]; [AppEvent.Download] with [DownloadState.CANCELLED] follows. Its "[name].part" stays. */
    fun cancelDownload(tag: String)

    companion object {
        /** Ending of a file that is still being loaded. */
        const val PART = ".part"

        /** Path segments of a file name: letters, digits, `.`, `_` and `-`, not `.` or `..` alone. */
        private val SEGMENT = Regex("[A-Za-z0-9._-]{1,100}")

        /** Whether [name] may name a file in [dir]: segments of [SEGMENT] separated by `/`, at most 200 characters. */
        fun isValidName(name: String): Boolean =
            name.length <= 200 && name.split('/').all { it.matches(SEGMENT) && it != "." && it != ".." }

        /** The files of a host that keeps none: no folder, and every download is refused. */
        val NONE: AppFiles = object : AppFiles {
            override val dir: File? = null
            override val freeBytes: Long = 0

            override fun download(tag: String, url: String, name: String, wifiOnly: Boolean) =
                throw CommandException.badValue("Diese Uhr-App hat keinen Ordner für Dateien")

            override fun cancelDownload(tag: String) = Unit
        }
    }
}

/** State of a download ([AppFiles.download]), as [AppEvent.Download] reports it. Since interface version 5. */
enum class DownloadState(val json: String) {
    /** Still loading; the event says how far. */
    RUNNING("running"),

    /** Complete: the file has its name. */
    DONE("done"),

    /** Broken off; [AppEvent.Download.message] says why (German). Starting it again goes on where it stopped. */
    FAILED("failed"),

    /** Stopped by [AppFiles.cancelDownload]. */
    CANCELLED("cancelled"),
    ;

    companion object {
        fun of(json: String?): DownloadState? = entries.firstOrNull { it.json == json }
    }
}

package ch.madtreasures.g2watch.apps.files

import ch.madtreasures.g2watch.apps.AppFiles
import ch.madtreasures.g2watch.apps.DownloadState
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What the app host needs to load big files for the apps ([AppFiles.download], interface 5, 03 §14). */
interface DownloadEngine {
    /** Starts loading [request]; [listener] hears from a background thread, [DownloadListener.onEnd] once. */
    fun start(request: DownloadRequest, listener: DownloadListener): DownloadJob

    companion object {
        val NONE: DownloadEngine = object : DownloadEngine {
            override fun start(request: DownloadRequest, listener: DownloadListener): DownloadJob {
                listener.onEnd(DownloadState.FAILED, 0, -1, "Downloads gibt es auf dieser Uhr nicht")
                return DownloadJob {}
            }
        }
    }
}

/** Load [url] into [target]; [wifiOnly]: not over LTE. */
data class DownloadRequest(val url: String, val target: File, val wifiOnly: Boolean)

interface DownloadListener {
    /** [bytes] of [total] (−1 while unknown) are in the file. */
    fun onProgress(bytes: Long, total: Long)

    /** The end: [DownloadState.DONE], [DownloadState.FAILED] with [message] (German) or [DownloadState.CANCELLED]. */
    fun onEnd(state: DownloadState, bytes: Long, total: Long, message: String?)
}

fun interface DownloadJob {
    /** Stops the download; [DownloadListener.onEnd] with [DownloadState.CANCELLED] follows, the part file stays. */
    fun cancel()
}

/** Opens HTTP connections for downloads: on the watch over Wi-Fi or LTE ([WearNetworkConnector]). */
interface Connector {
    /** Opens [url], from byte [from] on when it is above 0; throws [IOException] with a German message. */
    fun open(url: String, wifiOnly: Boolean, from: Long): Response

    /** An answer of the server; closing it ends the connection, also from another thread. */
    interface Response : Closeable {
        val status: Int

        /** Bytes of this answer's body, −1 if unknown. */
        val length: Long

        /** The whole file's size from `Content-Range` (status 206), −1 if none. */
        val rangeTotal: Long

        val body: InputStream
    }
}

/** [Connector] over [URLConnection]s that [open] makes: the default network unless told otherwise. */
open class UrlConnector(private val open: (URL, Boolean) -> URLConnection = { url, _ -> url.openConnection() }) : Connector {
    override fun open(url: String, wifiOnly: Boolean, from: Long): Connector.Response {
        val connection = open(URL(url), wifiOnly) as? HttpURLConnection ?: throw IOException("Keine HTTP-Adresse")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        return object : Connector.Response {
            override val status = status
            override val length = connection.contentLengthLong
            override val rangeTotal = parseRangeTotal(connection.getHeaderField("Content-Range"))
            override val body: InputStream by lazy { connection.inputStream }

            override fun close() = connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 20_000
        const val USER_AGENT = "G2Watch"

        /** `bytes 100-999/1000` → 1000; −1 for no header or an unknown size. */
        fun parseRangeTotal(header: String?): Long = header?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1
    }
}

/**
 * Loads files over HTTPS for the apps ([AppFiles.download]): into "<target>.part", from where an earlier
 * attempt stopped if the server allows ranges, and renamed to the target once complete. Reports progress
 * at most every [progressMs]. Refuses to start when the file would not fit: [freeBytes] of the target's
 * folder minus [reserveBytes] stay for the watch.
 */
class HttpDownloader(
    private val connector: Connector,
    private val executor: Executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "G2Watch-download").apply { isDaemon = true } },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val progressMs: Long = 1_000,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = 500L * 1024 * 1024,
) : DownloadEngine {

    override fun start(request: DownloadRequest, listener: DownloadListener): DownloadJob {
        val job = Job(request, listener)
        executor.execute(job)
        return job
    }

    private inner class Job(private val request: DownloadRequest, private val listener: DownloadListener) : Runnable, DownloadJob {
        @Volatile private var cancelled = false

        @Volatile private var response: Connector.Response? = null

        private var bytes = 0L
        private var total = -1L

        override fun cancel() {
            cancelled = true
            try {
                response?.close()
            } catch (_: Exception) {
                // Only to wake the reading thread; it ends the download itself.
            }
        }

        override fun run() {
            val (state, message) = try {
                load()
            } catch (e: IOException) {
                if (cancelled) DownloadState.CANCELLED to null
                else DownloadState.FAILED to "Verbindung unterbrochen (${e.message ?: e.javaClass.simpleName}); nochmals laden setzt fort"
            } catch (e: RuntimeException) {
                DownloadState.FAILED to "Download fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                response?.close()
            }
            listener.onEnd(state, bytes, total, message)
        }

        private fun load(): Pair<DownloadState, String?> {
            val target = request.target
            val part = File(target.path + AppFiles.PART)
            part.parentFile?.mkdirs()
            var from = if (part.isFile) part.length() else 0L
            var answer = open(from)
            if (answer.status == HTTP_RANGE_NOT_SATISFIABLE && from > 0) {
                // The part is longer than the file (it changed on the server): from the start.
                answer.close()
                part.delete()
                from = 0
                answer = open(0)
            }
            val append = when (answer.status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    total = answer.rangeTotal.takeIf { it > 0 } ?: answer.length.takeIf { it >= 0 }?.let { it + from } ?: -1
                    true
                }
                HttpURLConnection.HTTP_OK -> {
                    // The server ignored the range: everything again.
                    from = 0
                    total = answer.length
                    false
                }
                else -> return DownloadState.FAILED to "Der Server antwortet ${answer.status}"
            }
            if (cancelled) return DownloadState.CANCELLED to null
            val folder = part.parentFile ?: target.absoluteFile.parentFile
            if (total > 0) {
                val free = freeBytes(folder) - reserveBytes
                if (total - from > free) {
                    return DownloadState.FAILED to "Zu wenig Speicher auf der Uhr: braucht ${mb(total - from)}, frei sind ${mb(maxOf(0, free))}"
                }
            }
            bytes = from
            listener.onProgress(bytes, total)
            var lastReport = nowMs()
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER)
                val input = answer.body
                while (!cancelled) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    bytes += n
                    val now = nowMs()
                    if (now - lastReport >= progressMs) {
                        lastReport = now
                        listener.onProgress(bytes, total)
                    }
                }
            }
            if (cancelled) return DownloadState.CANCELLED to null
            if (total >= 0 && bytes != total) {
                return DownloadState.FAILED to "Unvollständig: ${mb(bytes)} von ${mb(total)}; nochmals laden setzt fort"
            }
            if (target.exists() && !target.delete()) return DownloadState.FAILED to "Alte Datei ${target.name} lässt sich nicht ersetzen"
            if (!part.renameTo(target)) return DownloadState.FAILED to "Datei ${target.name} lässt sich nicht anlegen"
            if (total < 0) total = bytes
            return DownloadState.DONE to null
        }

        private fun open(from: Long): Connector.Response =
            connector.open(request.url, request.wifiOnly, from).also { response = it }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Megabytes for messages: "1,5 MB", "320 MB". */
        fun mb(bytes: Long): String {
            val m = bytes / (1024.0 * 1024.0)
            return if (m < 10) String.format(java.util.Locale.GERMANY, "%.1f MB", m) else "${Math.round(m)} MB"
        }
    }
}

package ch.madtreasures.g2watch.apps.video

import ch.madtreasures.g2watch.apps.VideoItem
import ch.madtreasures.g2watch.apps.VideoSearchResult
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * YouTube (and the other portals NewPipeExtractor knows) on the watch, without Google's app or an API
 * key (03 §10): search, and the stream addresses of a video. Blocking network calls: never on the app
 * thread. NewPipeExtractor is GPL-3.0, like the Faceclaw core this app already contains.
 */
internal object NewPipeCatalog {
    const val MAX_HITS = 20

    @Volatile
    private var ready = false

    private fun ensure() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val locale = Locale.getDefault()
            NewPipe.init(
                WatchDownloader(),
                Localization.fromLocale(locale),
                ContentCountry(locale.country.ifEmpty { "CH" }),
            )
            ready = true
        }
    }

    fun search(query: String): VideoSearchResult = try {
        ensure()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(service, handler)
        val items = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.AUDIO_STREAM && it.streamType != StreamType.AUDIO_LIVE_STREAM }
            .take(MAX_HITS)
            .map {
                VideoItem(
                    url = it.url,
                    title = it.name,
                    channel = it.uploaderName.orEmpty(),
                    durationS = it.duration.coerceAtLeast(0),
                    live = it.streamType == StreamType.LIVE_STREAM,
                    views = it.viewCount,
                    uploaded = it.textualUploadDate.orEmpty(),
                )
            }
        VideoSearchResult(items)
    } catch (e: Throwable) {
        // Also an Error from NewPipe or Rhino: on a pool thread it would end the app and the glasses session.
        VideoSearchResult(emptyList(), explain(e))
    }

    /** What the player needs of a video page. */
    class Resolved(val title: String, val durationMs: Long, val live: Boolean, val options: List<StreamOption>, val hls: String?)

    fun resolve(url: String): Resolved {
        ensure()
        val info = StreamInfo.getInfo(url)
        val options = ArrayList<StreamOption>()
        fun progressive(s: org.schabi.newpipe.extractor.stream.Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        fun height(v: VideoStream) = v.height.takeIf { it > 0 } ?: v.getResolution().substringBefore('p').toIntOrNull() ?: 0
        for (v in info.videoOnlyStreams) if (progressive(v)) {
            options += StreamOption(v.content, StreamOption.Kind.VIDEO_ONLY, height(v), v.codec.orEmpty(), v.format?.mimeType.orEmpty(), v.bitrate)
        }
        for (v in info.videoStreams) if (progressive(v)) {
            options += StreamOption(v.content, StreamOption.Kind.MUXED, height(v), v.codec.orEmpty(), v.format?.mimeType.orEmpty(), v.bitrate)
        }
        for (a in info.audioStreams) if (progressive(a)) {
            val bitrate = if (a.averageBitrate > 0) a.averageBitrate * 1000 else a.bitrate
            val type = a.audioTrackType
            options += StreamOption(
                a.content, StreamOption.Kind.AUDIO, 0, a.codec.orEmpty(), a.format?.mimeType.orEmpty(), bitrate,
                original = type == null || type == AudioTrackType.ORIGINAL,
            )
        }
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        return Resolved(info.name, info.duration.coerceAtLeast(0) * 1000, live, options, info.hlsUrl?.takeIf { it.isNotEmpty() })
    }

    /** A German sentence for the wearer. */
    fun explain(e: Throwable): String = when (e) {
        is ReCaptchaException -> "YouTube verlangt eine Bestätigung (zu viele Anfragen) – später nochmal versuchen"
        is AgeRestrictedContentException -> "Das Video ist altersbeschränkt"
        is ContentNotAvailableException -> "Das Video ist nicht verfügbar"
        is IOException -> "Keine Verbindung (${e.message ?: e.javaClass.simpleName})"
        is ExtractionException -> "YouTube hat etwas geändert – die App braucht ein Update (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** NewPipeExtractor's HTTP on the watch: HttpURLConnection, on the calling thread. */
    private class WatchDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = URL(request.url()).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = request.httpMethod()
                connection.setRequestProperty("User-Agent", USER_AGENT)
                for ((name, values) in request.headers()) {
                    values.forEachIndexed { i, v -> if (i == 0) connection.setRequestProperty(name, v) else connection.addRequestProperty(name, v) }
                }
                request.dataToSend()?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                val body = (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val headers = connection.headerFields.filterKeys { it != null }
                return Response(code, connection.responseMessage, headers, body, connection.url.toString())
            } finally {
                connection.disconnect()
            }
        }
    }

    /** The desktop browser NewPipe itself claims to be; YouTube serves its pages to it. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val TIMEOUT_MS = 20_000
}

package ch.madtreasures.g2watch.apps.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * The user agent and headers YouTube's video servers expect for an address, as NewPipe's player does
 * it: an address fetched as the Android, iOS or visionOS client must be loaded as that client, web
 * addresses as a browser from youtube.com.
 */
internal object YoutubeClients {
    private val PATH_CLIENT = Regex("/c/([A-Z0-9_]+)/")

    fun userAgent(url: String): String {
        val pathClient = PATH_CLIENT.find(url)?.groupValues?.get(1)
        return when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) || pathClient?.startsWith("ANDROID") == true ->
                YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) || pathClient == "IOS" -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) || pathClient == "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> NewPipeCatalog.USER_AGENT
        }
    }

    fun isWeb(url: String) = YoutubeParsingHelper.isWebStreamingUrl(url) || YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url)

    fun headers(url: String): Map<String, String> = buildMap {
        put("User-Agent", userAgent(url))
        if (isWeb(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
        }
    }

    fun isGooglevideo(url: String) = url.toUri().host?.endsWith(".googlevideo.com") == true
}

/**
 * HTTP for a progressive file on YouTube's video servers, the way NewPipe loads them: the byte range
 * goes into the address (`range=a-b`) in pieces of at most [CHUNK] bytes with a request counter (`rn`),
 * web addresses are fetched with POST, because long open-ended requests get throttled or refused. Needs
 * the file length from the `clen` parameter; addresses without it go through [upstream] unchanged.
 */
@OptIn(UnstableApi::class)
internal class GooglevideoDataSource(private val upstream: DataSource) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var spec: DataSpec? = null
    private var direct = false
    private var position = 0L
    private var end = 0L
    private var chunkLeft = 0L
    private var requests = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        spec = dataSpec
        transferInitializing(dataSpec)
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        direct = clen == null
        val length: Long
        if (direct) {
            length = upstream.open(dataSpec)
        } else {
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) clen!! else minOf(clen!!, dataSpec.position + dataSpec.length)
            length = end - position
            if (length > 0) openChunk()
        }
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun openChunk() {
        val base = spec!!.uri
        val last = minOf(position + CHUNK, end) - 1
        val url = base.buildUpon()
            .appendQueryParameter("range", "$position-$last")
            .appendQueryParameter("rn", (++requests).toString())
            .build()
        val web = YoutubeClients.isWeb(base.toString())
        val chunk = DataSpec.Builder()
            .setUri(url)
            .setHttpMethod(if (web) DataSpec.HTTP_METHOD_POST else DataSpec.HTTP_METHOD_GET)
            .setHttpBody(if (web) byteArrayOf(0x78, 0) else null)
            .build()
        upstream.open(chunk)
        chunkLeft = last - position + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (direct) return upstream.read(buffer, offset, length).also { if (it > 0) bytesTransferred(it) }
        var empty = 0
        while (true) {
            if (position >= end) return C.RESULT_END_OF_INPUT
            if (chunkLeft == 0L) {
                upstream.close()
                openChunk()
            }
            val n = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                // The server ended the piece early: ask again from where it stopped, but not forever.
                if (++empty > 2) throw java.io.IOException("googlevideo sends no data at $position of $end")
                chunkLeft = 0
                continue
            }
            position += n
            chunkLeft -= n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Makes a [GooglevideoDataSource] over a fresh HTTP source with the headers of the address's client. */
    class Factory(private val url: String) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val headers = YoutubeClients.headers(url)
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"])
                .setDefaultRequestProperties(headers - "User-Agent")
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(false)
                .createDataSource()
            return GooglevideoDataSource(http)
        }
    }

    companion object {
        /** Bytes per request: about a minute of the small videos the glasses need. */
        const val CHUNK = 1L shl 20
        const val TIMEOUT_MS = 15_000
    }
}

package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.HttpRequest
import ch.madtreasures.g2watch.apps.HttpResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP request behind `fetch` (03 §2): at most [TIME_LIMIT_MS] in total and [MAX_BYTES] of
 * response; redirects are followed only to `https://`. Blocking, so never on the app thread. The
 * host checks the permission and the `https://` of the first request.
 */
object AppHttp {
    const val TIME_LIMIT_MS = 10_000L
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun run(request: HttpRequest, now: () -> Long = System::currentTimeMillis): HttpResult {
        val deadline = now() + TIME_LIMIT_MS
        var url = URL(request.url)
        repeat(MAX_REDIRECTS + 1) {
            val left = (deadline - now()).coerceAtLeast(1).toInt()
            val connection = try {
                url.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return HttpResult(0, error = "Keine Verbindung: ${e.message}")
            }
            try {
                connection.connectTimeout = left
                connection.readTimeout = left
                connection.instanceFollowRedirects = false
                connection.requestMethod = request.method
                connection.setRequestProperty("User-Agent", "G2Watch")
                request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return HttpResult(code, error = "Weiterleitung ohne Ziel")
                    val next = URL(url, location)
                    // Only the first request is checked by the host; an https page must not lead to http.
                    if (next.protocol != url.protocol && next.protocol != "https") return HttpResult(code, error = "Weiterleitung auf ${next.protocol}:// abgelehnt")
                    url = next
                    return@repeat
                }
                if (connection.contentLengthLong > MAX_BYTES) return HttpResult(code, error = "Antwort größer als 1 MB")
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { readCapped(it, deadline, now) } ?: ByteArray(0)
                val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
                return HttpResult(code, body, headers)
            } catch (e: TooLarge) {
                return HttpResult(0, error = "Antwort größer als 1 MB")
            } catch (e: TimeUp) {
                return HttpResult(0, error = "Zeitlimit von ${TIME_LIMIT_MS / 1000} s überschritten")
            } catch (e: IOException) {
                return HttpResult(0, error = "Keine Verbindung: ${e.message}")
            } finally {
                connection.disconnect()
            }
        }
        return HttpResult(0, error = "Zu viele Weiterleitungen")
    }

    private class TooLarge : IOException()

    private class TimeUp : IOException()

    private fun readCapped(input: InputStream, deadline: Long, now: () -> Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw TooLarge()
            if (now() > deadline) throw TimeUp()
        }
        return out.toByteArray()
    }
}

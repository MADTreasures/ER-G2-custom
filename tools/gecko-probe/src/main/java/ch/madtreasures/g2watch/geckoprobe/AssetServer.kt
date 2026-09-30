package ch.madtreasures.g2watch.geckoprobe

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny HTTP server on 127.0.0.1 for the test apps (05 §4: Even Hub apps are served from a local
 * origin, so ES modules, storage and the bridge's content script work as on a web server). GET and
 * HEAD only, one request per connection, no paths outside the given files.
 */
class AssetServer(private val files: (String) -> ByteArray?) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = socket?.localPort ?: 0

    fun start(): AssetServer {
        if (socket != null) return this
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "probe-http").apply { isDaemon = true } }
        socket = server
        pool = workers
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }, "probe-http-accept").apply { isDaemon = true }.start()
        return this
    }

    fun url(path: String): String = "http://127.0.0.1:$port/${path.removePrefix("/")}"

    fun stop() {
        socket?.close()
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty().substringBefore('?').substringBefore('#')
            val out = s.getOutputStream()
            if (method != "GET" && method != "HEAD") {
                out.write(head(405, "text/plain", 0))
                return
            }
            val path = decode(target).removePrefix("/").ifEmpty { "index.html" }.let { if (it.endsWith("/")) it + "index.html" else it }
            val body = if (path.split('/').any { it == ".." }) null else files(path)
            if (body == null) {
                val text = "not found".toByteArray()
                out.write(head(404, "text/plain", text.size))
                if (method == "GET") out.write(text)
                return
            }
            out.write(head(200, contentType(path), body.size))
            if (method == "GET") out.write(body)
            out.flush()
        }
    }

    private fun head(code: Int, type: String, length: Int): ByteArray {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        return ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

        private fun decode(s: String): String = try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}

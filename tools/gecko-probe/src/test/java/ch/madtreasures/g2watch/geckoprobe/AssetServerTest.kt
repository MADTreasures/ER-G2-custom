package ch.madtreasures.g2watch.geckoprobe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class AssetServerTest {

    private val files = mapOf(
        "index.html" to "<p>start</p>",
        "text/index.html" to "<p>text</p>",
        "common/even.js" to "window.even = {};",
    )
    private val requested = ArrayList<String>()
    private val server = AssetServer { path -> requested += path; files[path]?.toByteArray() }.start()

    @After
    fun stop() = server.stop()

    /** Sends one raw request and returns the status line, the headers and the body. */
    private fun request(line: String): Triple<String, Map<String, String>, String> {
        Socket(InetAddress.getByName("127.0.0.1"), server.port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("$line\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n").split("\r\n")
            val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Triple(head.first(), headers, text.substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun `serves the test apps from 127_0_0_1`() {
        assertTrue(server.port > 0)
        assertEquals("http://127.0.0.1:${server.port}/text/index.html", server.url("/text/index.html"))
        val (status, headers, body) = request("GET /common/even.js?v=2 HTTP/1.1")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("text/javascript; charset=utf-8", headers["content-type"])
        assertEquals("17", headers["content-length"])
        assertEquals("no-store", headers["cache-control"])
        assertEquals("window.even = {};", body)
    }

    @Test
    fun `folders serve their index page`() {
        assertEquals("<p>start</p>", request("GET / HTTP/1.1").third)
        assertEquals("<p>text</p>", request("GET /text/ HTTP/1.1").third)
    }

    @Test
    fun `unknown paths, parent folders and other methods are refused`() {
        assertEquals("HTTP/1.1 404 Not Found", request("GET /missing.html HTTP/1.1").first)
        assertEquals("HTTP/1.1 404 Not Found", request("GET /text/../../secret HTTP/1.1").first)
        assertEquals("HTTP/1.1 404 Not Found", request("GET /text/%2e%2e/%2e%2e/secret HTTP/1.1").first)
        assertEquals("HTTP/1.1 405 Method Not Allowed", request("POST /index.html HTTP/1.1").first)
        // The file source is never asked for a path with "..".
        assertTrue(requested.toString(), requested.none { it.contains("..") })
    }

    @Test
    fun `HEAD sends the headers only`() {
        val (status, headers, body) = request("HEAD /index.html HTTP/1.1")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("12", headers["content-length"])
        assertEquals("", body)
    }

    @Test
    fun `content types for the kinds of files test apps use`() {
        assertEquals("text/html; charset=utf-8", AssetServer.contentType("a/index.html"))
        assertEquals("text/javascript; charset=utf-8", AssetServer.contentType("vue.global.prod.js"))
        assertEquals("application/wasm", AssetServer.contentType("fib.WASM"))
        assertEquals("image/png", AssetServer.contentType("landschaft.png"))
        assertEquals("application/json", AssetServer.contentType("app.json"))
        assertEquals("application/octet-stream", AssetServer.contentType("README"))
    }
}

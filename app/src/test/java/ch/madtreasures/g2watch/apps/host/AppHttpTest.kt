package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.HttpRequest
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** `fetch` limits (03 §2) against a local server; the host itself allows only https. */
class AppHttpTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/klein") { ex ->
            val body = "Hallo".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/gross") { ex ->
            // No length announced: the cap has to hold while reading.
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> repeat(1100) { out.write(ByteArray(1024)) } }
        }
        createContext("/fehlt") { ex ->
            val body = "nicht da".toByteArray()
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/weiter") { ex ->
            ex.responseHeaders.add("Location", "ftp://example.org/datei")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun stop() = server.stop(0)

    @Test
    fun `a small answer comes through with status and headers`() {
        val r = AppHttp.run(HttpRequest("$base/klein"))
        assertEquals(200, r.status)
        assertEquals("Hallo", r.text())
        assertNull(r.error)
        assertTrue(r.ok)
    }

    @Test
    fun `an answer over 1 MB is refused`() {
        val r = AppHttp.run(HttpRequest("$base/gross"))
        assertEquals("Antwort größer als 1 MB", r.error)
    }

    @Test
    fun `error statuses keep their body`() {
        val r = AppHttp.run(HttpRequest("$base/fehlt"))
        assertEquals(404, r.status)
        assertEquals("nicht da", r.text())
        assertTrue(!r.ok)
    }

    @Test
    fun `redirects to other protocols are refused`() {
        val r = AppHttp.run(HttpRequest("$base/weiter"))
        assertEquals("Weiterleitung auf ftp:// abgelehnt", r.error)
    }
}

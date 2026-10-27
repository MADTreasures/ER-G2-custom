package ch.madtreasures.g2watch.apps.files

import ch.madtreasures.g2watch.apps.DownloadState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import kotlin.io.path.createTempDirectory

/** [HttpDownloader] against a server in memory: whole files, going on after a break, odd servers, cancelling. */
class HttpDownloaderTest {
    private val dir = createTempDirectory("g2-download").toFile().apply { deleteOnExit() }
    private val target = File(dir, "maps/test.map")
    private val part = File(target.path + ".part")
    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private var clock = 0L

    /** Runs the job right away on the test thread. */
    private val now = Executor { it.run() }

    /** A server with [content] that serves ranges unless told otherwise; [cut] ends the body early. */
    private inner class Server(var ranges: Boolean = true, var cut: Int = -1, var status: Int = 200) : Connector {
        val asked = mutableListOf<Long>()

        override fun open(url: String, wifiOnly: Boolean, from: Long): Connector.Response {
            asked += from
            val ranged = ranges && from > 0
            if (ranged && from > content.size) return response(416, ByteArray(0), -1)
            val body = if (ranged) content.copyOfRange(from.toInt(), content.size) else content
            val sent = if (cut >= 0) body.copyOf(minOf(cut, body.size)) else body
            return response(if (ranged) 206 else status, sent, body.size.toLong(), if (ranged) content.size.toLong() else -1)
        }

        private fun response(status: Int, bytes: ByteArray, length: Long, rangeTotal: Long = -1) = object : Connector.Response {
            override val status = status
            override val length = length
            override val rangeTotal = rangeTotal
            override val body: InputStream = object : ByteArrayInputStream(bytes) {
                // The body ends early when cut: like a connection that breaks.
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n < 0 && cut >= 0) throw IOException("Verbindung zurückgesetzt")
                    clock += 400
                    return n
                }
            }

            override fun close() = Unit
        }
    }

    private class Recorder : DownloadListener {
        val progress = mutableListOf<Pair<Long, Long>>()
        var end: Triple<DownloadState, Long, String?>? = null

        override fun onProgress(bytes: Long, total: Long) {
            progress += bytes to total
        }

        override fun onEnd(state: DownloadState, bytes: Long, total: Long, message: String?) {
            end = Triple(state, bytes, message)
        }
    }

    private fun downloader(server: Connector, free: Long = Long.MAX_VALUE / 2) =
        HttpDownloader(server, now, nowMs = { clock }, progressMs = 1_000, freeBytes = { free }, reserveBytes = 0)

    private fun load(server: Connector, free: Long = Long.MAX_VALUE / 2): Recorder =
        Recorder().also { downloader(server, free).start(DownloadRequest("https://example.org/test.map", target, wifiOnly = true), it) }

    @Test
    fun `a whole file arrives under its name, with progress about once a second`() {
        val r = load(Server())
        assertEquals(DownloadState.DONE, r.end!!.first)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
        assertEquals(0L to content.size.toLong(), r.progress.first())
        assertTrue(r.progress.size in 2..10)
    }

    @Test
    fun `after a break it goes on where it stopped`() {
        val server = Server(cut = 100_000)
        val first = load(server)
        assertEquals(DownloadState.FAILED, first.end!!.first)
        assertTrue("nochmals laden setzt fort" in first.end!!.third!!)
        assertEquals(100_000L, part.length())
        assertFalse(target.exists())

        server.cut = -1
        val second = load(server)
        assertEquals(DownloadState.DONE, second.end!!.first)
        assertEquals(listOf(0L, 100_000L), server.asked)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a server without ranges sends everything again`() {
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(5_000) { 9 })
        val r = load(Server(ranges = false))
        assertEquals(DownloadState.DONE, r.end!!.first)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a part longer than the file on the server starts over`() {
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(content.size + 10))
        val server = Server()
        val r = load(server)
        assertEquals(DownloadState.DONE, r.end!!.first)
        assertEquals(listOf(content.size + 10L, 0L), server.asked)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `an error status fails with the status`() {
        val r = load(Server(ranges = false, status = 404))
        assertEquals(DownloadState.FAILED, r.end!!.first)
        assertEquals("Der Server antwortet 404", r.end!!.third)
        assertFalse(target.exists())
    }

    @Test
    fun `a file that would not fit is not loaded`() {
        val r = load(Server(), free = 1_000)
        assertEquals(DownloadState.FAILED, r.end!!.first)
        assertTrue(r.end!!.third!!.startsWith("Zu wenig Speicher auf der Uhr"))
        assertFalse(part.exists() && part.length() > 0)
    }

    @Test
    fun `cancelling stops it and keeps what came`() {
        val recorder = object : DownloadListener {
            var job: DownloadJob? = null
            var end: DownloadState? = null

            override fun onProgress(bytes: Long, total: Long) {
                if (bytes > 0) job?.cancel()
            }

            override fun onEnd(state: DownloadState, bytes: Long, total: Long, message: String?) {
                end = state
            }
        }
        // The job starts when the executor runs it, so the listener knows it before the first byte.
        val queued = mutableListOf<Runnable>()
        val engine = HttpDownloader(Server(), { queued += it }, nowMs = { clock }, progressMs = 1_000, freeBytes = { Long.MAX_VALUE / 2 }, reserveBytes = 0)
        recorder.job = engine.start(DownloadRequest("https://example.org/test.map", target, true), recorder)
        queued.single().run()
        assertEquals(DownloadState.CANCELLED, recorder.end)
        assertFalse(target.exists())
        assertTrue(part.length() in 1 until content.size)
    }

    @Test
    fun `the total size comes from Content-Range`() {
        assertEquals(1000L, UrlConnector.parseRangeTotal("bytes 100-999/1000"))
        assertEquals(-1L, UrlConnector.parseRangeTotal("bytes 100-999/*"))
        assertEquals(-1L, UrlConnector.parseRangeTotal(null))
    }
}

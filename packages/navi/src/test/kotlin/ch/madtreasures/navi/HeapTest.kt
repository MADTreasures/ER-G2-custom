package ch.madtreasures.navi

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The heavy work fits into the memory of a watch: the search index of all of Switzerland and long routes,
 * each in a JVM of its own with a small heap. Needs -PnaviData with switzerland.map; skipped without.
 */
class HeapTest {
    private val ch = Fixtures.realMap("switzerland.map")

    @Test
    fun `the search index of all of Switzerland builds in 64 MB`() {
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        val out = File(createTempDirectory("navi-heap").toFile().apply { deleteOnExit() }, "switzerland.idx")
        val (code, log) = HeapProbe.run(64, "index", ch!!.path, out.path)
        println(log)
        assertEquals(log, 0, code)
        SearchIndex(out).use { assertEquals("Lugano", it.search("Lugano").first().name) }
    }

    @Test
    fun `long routes through Switzerland find their way in 64 MB`() {
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        // Zürich main station to Bern's (95 km straight), by bike and on foot; Zürich to Lucerne on foot.
        for (args in listOf(
            listOf("BIKE", "47.3779", "8.5403", "46.9490", "7.4390"),
            listOf("FOOT", "47.3779", "8.5403", "46.9490", "7.4390"),
            listOf("FOOT", "47.3779", "8.5403", "47.0502", "8.3093"),
        )) {
            val (code, log) = HeapProbe.run(64, "route", *args.toTypedArray(), ch!!.path)
            println("$args: $log")
            assertEquals(log, 0, code)
        }
    }
}

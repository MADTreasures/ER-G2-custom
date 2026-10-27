package ch.madtreasures.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** The search index of the Vaduz map: places, streets and points, found by name. With -PnaviData all of Switzerland. */
class SearchTest {
    companion object {
        private lateinit var index: SearchIndex

        @BeforeClass
        @JvmStatic
        fun build() {
            val dir = createTempDirectory("navi-index").toFile().apply { deleteOnExit() }
            val file = File(dir, "vaduz.idx")
            MapFile(Fixtures.vaduz).use { SearchIndexBuilder.build(it, file) }
            index = SearchIndex(file)
        }
    }

    @Test
    fun `a street by its name and the town it lies in`() {
        val hits = index.search("Städtle")
        assertEquals("Städtle · Vaduz", hits.first().label)
        assertTrue(hits.first().position.distanceTo(LatLon(47.139, 9.522)) < 600)
    }

    @Test
    fun `the start of a name is enough, with or without umlauts`() {
        assertTrue(index.search("äulestr").any { it.name == "Äulestrasse" })
        assertTrue(index.search("aulestrasse").any { it.name == "Äulestrasse" })
        assertTrue(index.search("STÄDTLE").any { it.name == "Städtle" })
    }

    @Test
    fun `the town comes first when its name is asked for`() {
        assertEquals("Vaduz", index.search("Vaduz").first().name)
    }

    @Test
    fun `several words in any order`() {
        assertEquals("Städtle", index.search("Vaduz Städtle").first().name)
        assertEquals("Städtle", index.search("Städtle Vaduz").first().name)
        assertTrue(index.search("Städtle Zürich").isEmpty())
    }

    @Test
    fun `an index merged from many small runs is the same file`() {
        val dir = createTempDirectory("navi-runs").toFile().apply { deleteOnExit() }
        val small = File(dir, "small.idx")
        val whole = File(dir, "whole.idx")
        MapFile(Fixtures.vaduz).use {
            SearchIndexBuilder.build(it, small, runEntries = 50)
            SearchIndexBuilder.build(it, whole)
        }
        assertTrue(small.length() > 1000)
        assertTrue(small.readBytes().contentEquals(whole.readBytes()))
        // Nothing is left over but the index.
        assertEquals(setOf("small.idx", "whole.idx"), dir.list()!!.toSet())
    }

    @Test
    fun `plain names fold case, accents and sharp s`() {
        assertEquals("stadtle", SearchIndexBuilder.normalize("Städtle"))
        assertEquals("rue de geneve", SearchIndexBuilder.normalize("Rue de Genève"))
        assertEquals("grossstrasse 3", SearchIndexBuilder.normalize("Großstraße  3"))
        assertEquals("st gallen", SearchIndexBuilder.normalize("St. Gallen"))
    }

    @Test
    fun `nothing for nonsense, and nothing for nothing`() {
        assertTrue(index.search("xqzzy").isEmpty())
        assertTrue(index.search("  ").isEmpty())
    }

    @Test
    fun `all of Switzerland`() {
        val ch = Fixtures.realMap("switzerland.map")
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        val dir = createTempDirectory("navi-index-ch").toFile().apply { deleteOnExit() }
        val file = File(dir, "switzerland.idx")
        val t0 = System.nanoTime()
        MapFile(ch!!).use { SearchIndexBuilder.build(it, file) }
        val t1 = System.nanoTime()
        SearchIndex(file).use { idx ->
            println("Switzerland: ${idx.size} entries, ${file.length() / 1024} KiB, built in ${(t1 - t0) / 1_000_000} ms")
            val zurich = LatLon(47.3769, 8.5417)
            for (q in listOf("Bahnhofstrasse Zürich", "Zürich HB", "Bundeshaus", "ETH", "Zytglogge", "Lugano", "Matterhorn", "Rue du Rhône Genève")) {
                val t = System.nanoTime()
                val hits = idx.search(q, zurich)
                println("  $q (${(System.nanoTime() - t) / 1_000_000} ms): ${hits.take(4).joinToString(" | ") { it.label }}")
            }
            assertTrue(idx.search("Bahnhofstrasse Zürich", zurich).first().label.startsWith("Bahnhofstrasse · Zürich"))
            assertEquals("Lugano", idx.search("Lugano", zurich).first().name)
            assertEquals("Genf", idx.search("Genève", zurich).first().name)
            assertTrue(idx.search("Rue du Rhône Genève", zurich).first().label.startsWith("Rue du Rhône · Genf"))
        }
    }
}

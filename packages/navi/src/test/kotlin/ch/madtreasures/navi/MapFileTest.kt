package ch.madtreasures.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

/**
 * The map reader on [Fixtures.vaduz]: two blocks of zoom 14 copied byte for byte out of the mapsforge server's
 * liechtenstein.map (tools/cut_map.py), so the encoding is the real one. With -PnaviData it also reads every
 * block of a whole real map.
 */
class MapFileTest {
    @Test
    fun `the header of a real map`() {
        MapFile(Fixtures.vaduz).use { map ->
            val h = map.header
            assertEquals(5, h.version)
            assertEquals(Fixtures.vaduz.length(), h.fileSize)
            assertEquals(256, h.tileSize)
            assertEquals(listOf("en", "de", "fr", "es"), h.languages)
            assertEquals("Map data (c) OpenStreetMap contributors", h.comment)
            assertEquals(1, h.subFiles.size)
            val sub = h.subFiles.single()
            assertEquals(Triple(14, 12, 21), Triple(sub.baseZoom, sub.minZoom, sub.maxZoom))
            assertEquals(1 to 2, sub.width to sub.height)
            assertTrue(h.wayTags.any { it.toString() == "highway=residential" })
            assertTrue(h.wayTags.any { it.toString() == "oneway=yes" })
            assertTrue(h.wayTags.any { it.variable })
        }
    }

    @Test
    fun `blocks hold Vaduz's streets and places with German names`() {
        MapFile(Fixtures.vaduz).use { map ->
            val sub = map.subFileFor(16)
            val blocks = map.blocksIn(sub, map.header.box).map { (x, y) -> map.block(sub, x, y)!! }
            assertEquals(2, blocks.size)
            val ways = blocks.flatMap { it.ways }
            val pois = blocks.flatMap { it.pois }
            assertTrue(ways.size > 1000)
            val highway = map.header.wayTags.withIndex().filter { it.value.key == "highway" }.map { it.index }.toSet()
            val streets = ways.filter { w -> w.tags.any { it in highway } }.mapNotNull { MapFile.localName(it.name) }.toSet()
            assertTrue(streets.toString(), "Städtle" in streets)
            assertTrue(streets.toString(), "Äulestrasse" in streets)
            val place = map.header.poiTags.withIndex().filter { it.value.key == "place" }.map { it.index }.toSet()
            val places = pois.filter { p -> p.tags.any { it in place } }.mapNotNull { MapFile.localName(it.name) }
            assertTrue(places.toString(), "Vaduz" in places)
            // Every node lies near its block: the offsets were applied to the right corner.
            for (b in blocks) for (w in b.ways) for (part in w.parts) for (ring in part) {
                for (i in ring.indices step 2) {
                    assertTrue(ring[i] in 47_100_000..47_190_000 && ring[i + 1] in 9_480_000..9_570_000)
                }
            }
        }
    }

    @Test
    fun `a way that crosses the border between two blocks meets itself there exactly`() {
        MapFile(Fixtures.vaduz).use { map ->
            val sub = map.subFileFor(21)
            val (a, b) = map.blocksIn(sub, map.header.box).map { (x, y) -> map.block(sub, x, y)!! }
            fun nodes(block: MapBlock) = block.ways.flatMap { w -> w.parts.flatMap { p -> p.flatMap { r -> r.indices.step(2).map { r[it].toLong() shl 32 or (r[it + 1].toLong() and 0xFFFFFFFFL) } } } }.toSet()
            val shared = nodes(a) intersect nodes(b)
            // The writer repeats ways that cross the border in both blocks; their nodes must agree to the microdegree.
            assertTrue("shared nodes: ${shared.size}", shared.size > 50)
        }
    }

    @Test
    fun `fewer items at lower zoom, and nothing outside the map`() {
        MapFile(Fixtures.vaduz).use { map ->
            val sub = map.subFileFor(14)
            val (x, y) = map.blocksIn(sub, map.header.box).first()
            val all = map.block(sub, x, y, 21)!!
            val coarse = map.block(sub, x, y, 12)!!
            assertTrue(coarse.ways.size < all.ways.size)
            assertTrue(coarse.pois.size <= all.pois.size)
            assertNull(map.block(sub, x - 5, y))
        }
    }

    @Test
    fun `names with translations give the German one`() {
        assertEquals("Genf", MapFile.localName("Genève\rde\bGenf\ren\bGeneva"))
        assertEquals("Genève", MapFile.localName("Genève\ren\bGeneva"))
        assertEquals("Vaduz", MapFile.localName("Vaduz"))
        assertNull(MapFile.localName(""))
        assertEquals("Geneva", MapFile.localName("Genève\ren\bGeneva", "en"))
    }

    @Test
    fun `a broken or cut off file is refused with a German reason`() {
        val dir = createTempDirectory("navi-map").toFile().apply { deleteOnExit() }
        val half = File(dir, "half.map").apply { writeBytes(Fixtures.vaduz.readBytes().copyOf(50_000)) }
        val e = assertThrows(IOException::class.java) { MapFile(half) }
        assertTrue(e.message!!, "unvollständig" in e.message!!)
        val text = File(dir, "text.map").apply { writeText("Grüezi, das ist keine Karte, sondern ein Text") }
        assertThrows(IOException::class.java) { MapFile(text) }
    }

    @Test
    fun `every block of a whole real map reads`() {
        val maps = Fixtures.realMaps()
        assumeTrue("no -PnaviData", maps.isNotEmpty())
        for (file in maps) MapFile(file).use { map ->
            var ways = 0L
            for (sub in map.header.subFiles) {
                for (y in sub.top..sub.bottom) for (x in sub.left..sub.right) ways += map.block(sub, x, y)!!.ways.size
            }
            println("${file.name}: $ways ways")
            assertTrue(ways > 0)
        }
    }
}

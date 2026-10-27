package ch.madtreasures.navi

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A mapsforge binary map (formats 3 to 5, as download.mapsforge.org has them), read block by block straight
 * from the file: the header with its tag lists and sub-files, and per block of a sub-file its points and ways
 * (mapsforge "Specification of the binary map file format"). Coordinates stay in microdegrees, exactly as the
 * map writer stored them. Thread-safe: the file is read under a lock, decoding happens outside it.
 */
class MapFile(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val header: MapHeader = try {
        readHeader()
    } catch (e: Exception) {
        raf.close()
        throw if (e is IOException) e else IOException("Keine lesbare Karte: ${file.name} (${e.message})", e)
    }
    private val indexes = arrayOfNulls<ByteArray>(header.subFiles.size)

    /** The sub-file for [zoom]: the one whose range holds it, else the nearest. */
    fun subFileFor(zoom: Int): SubFile =
        header.subFiles.firstOrNull { zoom in it.minZoom..it.maxZoom }
            ?: if (zoom > header.subFiles.maxOf { it.maxZoom }) header.subFiles.maxBy { it.maxZoom } else header.subFiles.minBy { it.minZoom }

    /**
     * Block ([x], [y]) at [sub]'s base zoom with everything that appears up to [zoom]; null outside the map.
     * A block without data comes back empty, with [MapBlock.water] when the sea covers it.
     */
    fun block(sub: SubFile, x: Int, y: Int, zoom: Int = sub.maxZoom): MapBlock? {
        if (x !in sub.left..sub.right || y !in sub.top..sub.bottom) return null
        val n = (y - sub.top) * sub.width + (x - sub.left)
        val index = index(sub)
        val entry = entry(index, n)
        val offset = entry and OFFSET_MASK
        val water = entry and WATER_BIT != 0L
        val end = if (n + 1 < sub.blocks) entry(index, n + 1) and OFFSET_MASK else sub.size
        val length = end - offset
        if (length <= 0) return MapBlock(x, y, sub.baseZoom, water, emptyList(), emptyList())
        if (offset < 0 || end > sub.size || length > MAX_BLOCK) throw IOException("Block $x/$y der Karte ${file.name} ist beschädigt")
        val bytes = read(sub.start + offset, length.toInt())
        return decode(bytes, sub, x, y, zoom, water)
    }

    /** The blocks of [sub] that cover [box], as (x, y) pairs, row by row. */
    fun blocksIn(sub: SubFile, box: Box): List<Pair<Int, Int>> {
        val l = Geo.tileX(box.minLon / 1e6, sub.baseZoom).coerceAtLeast(sub.left)
        val r = Geo.tileX(box.maxLon / 1e6, sub.baseZoom).coerceAtMost(sub.right)
        val t = Geo.tileY(box.maxLat / 1e6, sub.baseZoom).coerceAtLeast(sub.top)
        val b = Geo.tileY(box.minLat / 1e6, sub.baseZoom).coerceAtMost(sub.bottom)
        val out = ArrayList<Pair<Int, Int>>()
        for (y in t..b) for (x in l..r) out += x to y
        return out
    }

    override fun close() = raf.close()

    @Synchronized
    private fun read(position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        raf.seek(position)
        raf.readFully(bytes)
        return bytes
    }

    private fun index(sub: SubFile): ByteArray =
        indexes[sub.number] ?: read(sub.start + (if (header.debug) INDEX_SIGNATURE else 0), sub.blocks * 5).also { indexes[sub.number] = it }

    private fun entry(index: ByteArray, n: Int): Long {
        var v = 0L
        for (i in 0 until 5) v = (v shl 8) or (index[n * 5 + i].toLong() and 0xFF)
        return v
    }

    // --- Header ------------------------------------------------------------------------------------

    private fun readHeader(): MapHeader {
        if (raf.length() < 24) throw IOException("Keine Karte: ${file.name}")
        val start = read(0, 24)
        if (String(start, 0, 20, Charsets.US_ASCII) != MAGIC) throw IOException("Keine Mapsforge-Karte: ${file.name}")
        val size = Bytes(start, 20).int32()
        if (size < 20 || size > MAX_HEADER) throw IOException("Kopf der Karte ${file.name} ist beschädigt")
        val r = Bytes(read(24, size))
        val version = r.int32()
        if (version !in 3..5) throw IOException("Kartenformat $version wird nicht unterstützt (3 bis 5)")
        val fileSize = r.int64()
        if (fileSize != raf.length()) throw IOException("Karte ${file.name} ist unvollständig: ${raf.length()} von $fileSize Bytes")
        val date = r.int64()
        val box = Box(r.int32(), r.int32(), r.int32(), r.int32())
        val tileSize = r.int16()
        val projection = r.string()
        if (projection != "Mercator") throw IOException("Projektion $projection wird nicht unterstützt")
        val flags = r.u8()
        val start0 = if (flags and 0x40 != 0) LatLon.e6(r.int32(), r.int32()) else null
        val startZoom = if (flags and 0x20 != 0) r.u8() else null
        val languages = if (flags and 0x10 != 0) r.string().split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val comment = if (flags and 0x08 != 0) r.string() else null
        val createdBy = if (flags and 0x04 != 0) r.string() else null
        val poiTags = Array(r.int16()) { MapTag.parse(r.string()) }
        val wayTags = Array(r.int16()) { MapTag.parse(r.string()) }
        val subFiles = List(r.u8()) { n ->
            SubFile(n, r.u8(), r.u8(), r.u8(), r.int64(), r.int64(), box)
        }
        if (subFiles.isEmpty()) throw IOException("Karte ${file.name} hat keine Daten")
        return MapHeader(version, fileSize, date, box, tileSize, start0, startZoom, languages, comment, createdBy, poiTags, wayTags, subFiles, debug = flags and 0x80 != 0)
    }

    // --- Blocks ------------------------------------------------------------------------------------

    private fun decode(bytes: ByteArray, sub: SubFile, bx: Int, by: Int, zoom: Int, water: Boolean): MapBlock {
        val r = Bytes(bytes)
        if (header.debug) r.skip(BLOCK_SIGNATURE)
        val query = zoom.coerceIn(sub.minZoom, sub.maxZoom)
        var poiCount = 0
        var wayCount = 0
        for (z in sub.minZoom..sub.maxZoom) {
            val p = r.vbeu()
            val w = r.vbeu()
            if (z <= query) {
                poiCount += p
                wayCount += w
            }
        }
        val firstWay = r.vbeu()
        val waysAt = r.pos + firstWay
        // The writer stored every coordinate relative to the block's north-west corner in whole microdegrees.
        val originLat = (Geo.tileLat(by, sub.baseZoom) * 1e6).toInt()
        val originLon = (Geo.tileLon(bx, sub.baseZoom) * 1e6).toInt()
        val pois = ArrayList<MapPoi>(poiCount)
        repeat(poiCount) {
            if (header.debug) r.skip(ITEM_SIGNATURE)
            pois += readPoi(r, originLat, originLon)
        }
        r.pos = waysAt
        val ways = ArrayList<MapWay>(wayCount)
        repeat(wayCount) {
            if (header.debug) r.skip(ITEM_SIGNATURE)
            ways += readWay(r, originLat, originLon)
        }
        return MapBlock(bx, by, sub.baseZoom, water, pois, ways)
    }

    private fun readPoi(r: Bytes, originLat: Int, originLon: Int): MapPoi {
        val lat = originLat + r.vbes()
        val lon = originLon + r.vbes()
        val special = r.u8()
        val tags = IntArray(special and 0x0F) { r.vbeu() }
        skipValues(r, tags, header.poiTags)
        val flags = r.u8()
        val name = if (flags and 0x80 != 0) r.string() else null
        val house = if (flags and 0x40 != 0) r.string() else null
        val elevation = if (flags and 0x20 != 0) r.vbes() else null
        return MapPoi(lat, lon, special ushr 4, tags, name, house, elevation)
    }

    private fun readWay(r: Bytes, originLat: Int, originLon: Int): MapWay {
        val size = r.vbeu()
        val end = r.pos + size
        val subTiles = r.int16()
        val special = r.u8()
        val tags = IntArray(special and 0x0F) { r.vbeu() }
        skipValues(r, tags, header.wayTags)
        val flags = r.u8()
        val name = if (flags and 0x80 != 0) r.string() else null
        val house = if (flags and 0x40 != 0) r.string() else null
        val ref = if (flags and 0x20 != 0) r.string() else null
        val label = if (flags and 0x10 != 0) intArrayOf(r.vbes(), r.vbes()) else null
        val blockCount = if (flags and 0x08 != 0) r.vbeu() else 1
        val doubleDelta = flags and 0x04 != 0
        val parts = List(blockCount) {
            Array(r.vbeu()) { readNodes(r, originLat, originLon, doubleDelta) }
        }
        r.pos = end
        val labelAt = label?.let { first -> parts.firstOrNull()?.firstOrNull()?.let { intArrayOf(it[0] + first[0], it[1] + first[1]) } }
        return MapWay(special ushr 4, tags, name, house, ref, labelAt, parts, subTiles)
    }

    /** One ring of a way: lat, lon, lat, lon … in microdegrees. */
    private fun readNodes(r: Bytes, originLat: Int, originLon: Int, doubleDelta: Boolean): IntArray {
        val n = r.vbeu()
        val out = IntArray(n * 2)
        if (n == 0) return out
        var lat = originLat + r.vbes()
        var lon = originLon + r.vbes()
        out[0] = lat
        out[1] = lon
        var dLat = 0
        var dLon = 0
        for (i in 1 until n) {
            if (doubleDelta) {
                dLat += r.vbes()
                dLon += r.vbes()
                lat += dLat
                lon += dLon
            } else {
                lat += r.vbes()
                lon += r.vbes()
            }
            out[2 * i] = lat
            out[2 * i + 1] = lon
        }
        return out
    }

    /** Format 5 stores values of tags such as `height=%f` after the tag ids; nothing here needs them. */
    private fun skipValues(r: Bytes, tags: IntArray, table: Array<MapTag>) {
        for (id in tags) {
            if (id !in table.indices) throw IOException("Unbekanntes Merkmal $id in ${file.name}")
            val tag = table[id]
            if (!tag.variable) continue
            when (tag.value[1]) {
                'b' -> r.skip(1)
                'h' -> r.skip(2)
                'i', 'f' -> r.skip(4)
                's' -> r.string()
                else -> throw IOException("Unbekannte Wertart ${tag.value} in ${file.name}")
            }
        }
    }

    companion object {
        const val MAGIC = "mapsforge binary OSM"
        private const val MAX_HEADER = 1_000_000
        private const val MAX_BLOCK = 50_000_000L
        private const val OFFSET_MASK = 0x7FFFFFFFFFL
        private const val WATER_BIT = 0x8000000000L
        private const val INDEX_SIGNATURE = 16
        private const val BLOCK_SIGNATURE = 32
        private const val ITEM_SIGNATURE = 32

        /**
         * The name in [language] from a name with translations ("Genève\rde\bGenf\ren\bGeneva"), else the
         * name the map has first; null for none.
         */
        fun localName(raw: String?, language: String = "de"): String? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('\r')
            for (i in 1 until parts.size) {
                val pair = parts[i].split('\b')
                if (pair.size == 2 && pair[0].equals(language, ignoreCase = true) && pair[1].isNotBlank()) return pair[1]
            }
            return parts[0].takeIf { it.isNotBlank() } ?: parts.drop(1).firstNotNullOfOrNull { p -> p.split('\b').getOrNull(1)?.takeIf { it.isNotBlank() } }
        }

        /** Every form of a name with translations: the map's own and each translation, once. */
        fun allNames(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val parts = raw.split('\r')
            val out = LinkedHashSet<String>()
            if (parts[0].isNotBlank()) out += parts[0]
            for (i in 1 until parts.size) parts[i].split('\b').getOrNull(1)?.takeIf { it.isNotBlank() }?.let { out += it }
            return out.toList()
        }
    }
}

class MapHeader(
    val version: Int,
    val fileSize: Long,
    /** When the map was made, in ms since 1970. */
    val date: Long,
    val box: Box,
    val tileSize: Int,
    val start: LatLon?,
    val startZoom: Int?,
    val languages: List<String>,
    val comment: String?,
    val createdBy: String?,
    val poiTags: Array<MapTag>,
    val wayTags: Array<MapTag>,
    val subFiles: List<SubFile>,
    val debug: Boolean,
)

/** A tag of the header's lists; a value like `%f` means each item stores its own value. */
class MapTag(val key: String, val value: String) {
    val variable: Boolean get() = value.length == 2 && value[0] == '%'

    override fun toString() = "$key=$value"

    companion object {
        fun parse(s: String): MapTag {
            val eq = s.indexOf('=')
            return if (eq < 0) MapTag(s, "") else MapTag(s.substring(0, eq), s.substring(eq + 1))
        }
    }
}

/** One zoom interval of the map, stored as blocks of [baseZoom] tiles over the map's box. */
class SubFile(val number: Int, val baseZoom: Int, val minZoom: Int, val maxZoom: Int, val start: Long, val size: Long, box: Box) {
    val left = Geo.tileX(box.minLon / 1e6, baseZoom)
    val right = Geo.tileX(box.maxLon / 1e6, baseZoom)
    val top = Geo.tileY(box.maxLat / 1e6, baseZoom)
    val bottom = Geo.tileY(box.minLat / 1e6, baseZoom)
    val width = right - left + 1
    val height = bottom - top + 1
    val blocks = width * height
}

/** What block ([x], [y]) at [zoom] holds. */
class MapBlock(val x: Int, val y: Int, val zoom: Int, val water: Boolean, val pois: List<MapPoi>, val ways: List<MapWay>) {
    /** Memory the block takes, about (measured: a block of central Zurich, 12 000 ways, some 3 MB). */
    val bytes: Long by lazy { ways.size * 200L + ways.sumOf { w -> w.parts.sumOf { p -> p.sumOf { it.size } } } * 4L + pois.size * 100L }
}

/** A point of interest: [tags] index the header's POI tags; [layer] is 0–10 (5 = ground). */
class MapPoi(val lat: Int, val lon: Int, val layer: Int, val tags: IntArray, val name: String?, val house: String?, val elevation: Int?)

/**
 * A way: a line, or an area whose [parts] each list an outer ring and its holes; [tags] index the header's
 * way tags; [label] is where its name belongs (lat, lon), if the map says. [subTiles] tells which of the
 * block's 4 × 4 quarter-quarters (zoom + 2) the way touches: bit 15 − (row × 4 + column).
 */
class MapWay(
    val layer: Int,
    val tags: IntArray,
    val name: String?,
    val house: String?,
    val ref: String?,
    val label: IntArray?,
    val parts: List<Array<IntArray>>,
    val subTiles: Int = 0xFFFF,
) {
    val closed: Boolean
        get() = parts.firstOrNull()?.firstOrNull()?.let { r -> r.size >= 8 && r[0] == r[r.size - 2] && r[1] == r[r.size - 1] } == true
}

/** Reads the parts of a map file: big-endian numbers and mapsforge's variable-length ones. */
private class Bytes(private val b: ByteArray, var pos: Int = 0) {
    fun u8(): Int = b[pos++].toInt() and 0xFF

    fun int16(): Int = (u8() shl 8) or u8()

    fun int32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

    fun int64(): Long = (int32().toLong() shl 32) or (int32().toLong() and 0xFFFFFFFFL)

    fun skip(n: Int) {
        pos += n
    }

    /** Unsigned: 7 bits per byte, low bits first, the top bit says "more follows". */
    fun vbeu(): Int {
        var v = 0
        var shift = 0
        while (true) {
            val c = u8()
            v = v or ((c and 0x7F) shl shift)
            if (c and 0x80 == 0) return v
            shift += 7
        }
    }

    /** Signed: like [vbeu], but the last byte has 6 bits and the sign in 0x40. */
    fun vbes(): Int {
        var v = 0
        var shift = 0
        while (true) {
            val c = u8()
            if (c and 0x80 != 0) {
                v = v or ((c and 0x7F) shl shift)
                shift += 7
            } else {
                v = v or ((c and 0x3F) shl shift)
                return if (c and 0x40 != 0) -v else v
            }
        }
    }

    fun string(): String {
        val n = vbeu()
        val s = String(b, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }
}

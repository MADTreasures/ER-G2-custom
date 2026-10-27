package ch.madtreasures.navi

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min

/** A place to go to: a town, a street, a station, a museum … with the nearest town as [context]. */
data class Place(val name: String, val context: String?, val lat: Double, val lon: Double, val kind: Int) {
    val position: LatLon get() = LatLon(lat, lon)

    /** "Bahnhofstrasse · Zürich". */
    val label: String get() = if (context == null || context == name) name else "$name · $context"
}

/**
 * Builds a map's search index (format "navi-suche@1"): every named place, station and point of interest,
 * and every named street once per few hundred metres, with the nearest town or village, sorted by a plain
 * form of the name so a search is a binary search in the file. Runs once after a download, on a background
 * thread: it reads every block of the map.
 *
 * Memory stays small whatever the size of the country (a watch has little of it): the entries are sorted in
 * runs of [RUN_ENTRIES] that wait in temporary files next to the index, and then merged into it. Only the
 * towns and villages stay in memory, for the nearest one to every entry.
 */
object SearchIndexBuilder {
    private const val VERSION = 1

    /** Streets of one name are kept once per cell of this size (microdegrees of latitude). */
    private const val STREET_CELL = 6_000

    /** Entries sorted in memory at a time. */
    const val RUN_ENTRIES = 60_000

    /** Share of the progress for reading the map; merging the runs takes the rest. */
    private const val SCAN_SHARE = 0.85

    private class Entry(val plain: String, val shown: String, val lat: Int, val lon: Int, val kind: Int)

    private val ORDER = compareBy<Entry> { it.plain }.thenBy { it.kind }

    fun build(map: MapFile, out: File, runEntries: Int = RUN_ENTRIES, progress: (Double) -> Unit = {}) {
        val tags = MapTags(map.header)
        out.parentFile?.mkdirs()
        val work = File(out.path + ".runs")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val places = PlaceGrid()
            val runs = ArrayList<File>()
            val chunk = ArrayList<Entry>(runEntries)
            val plainOf = HashMap<String, String>()
            var count = 0
            fun flush() {
                if (chunk.isEmpty()) return
                chunk.sortWith(ORDER)
                val run = File(work, "run${runs.size}")
                DataOutputStream(BufferedOutputStream(FileOutputStream(run), 1 shl 16)).use { o ->
                    for (e in chunk) {
                        o.writeUTF(e.plain)
                        o.writeUTF(e.shown)
                        o.writeInt(e.lat)
                        o.writeInt(e.lon)
                        o.writeShort(e.kind)
                    }
                }
                runs += run
                count += chunk.size
                chunk.clear()
                plainOf.clear()
            }
            fun add(found: String, shown: String, lat: Int, lon: Int, kind: Int) {
                val plain = plainOf.getOrPut(found) { normalize(found) }
                if (plain.isEmpty()) return
                chunk += Entry(plain, shown, lat, lon, kind)
                if (chunk.size >= runEntries) flush()
            }
            // Copies of a street in neighbouring blocks count once: the cells of this row of blocks and the one before.
            var streets = HashSet<Long>()
            var streetsBefore = HashSet<Long>()
            val sub = map.subFileFor(Router.ROUTE_ZOOM)
            val total = sub.blocks.toDouble()
            var done = 0
            for (y in sub.top..sub.bottom) {
                streetsBefore = streets
                streets = HashSet()
                for (x in sub.left..sub.right) {
                    if (++done % 256 == 0) {
                        if (Thread.interrupted()) throw InterruptedException()
                        progress(done / total * SCAN_SHARE)
                    }
                    val block = map.block(sub, x, y, sub.maxZoom) ?: continue
                    for (poi in block.pois) {
                        val name = MapFile.localName(poi.name) ?: continue
                        val kind = tags.kind(poi)
                        if (kind == Kind.NONE) continue
                        add(name, name, poi.lat, poi.lon, kind)
                        if (Kind.isPlace(kind)) {
                            // A town is found by each of its names (Genève as well as Genf) and shown with the German one.
                            val forms = MapFile.allNames(poi.name).filter { it != name }
                            for (form in forms) add(form, name, poi.lat, poi.lon, kind)
                            if (kind != Kind.HAMLET) places.add((listOf(name) + forms).joinToString(ALIAS), poi.lat, poi.lon, kind)
                        }
                    }
                    for (way in block.ways) {
                        val name = MapFile.localName(way.name) ?: continue
                        val ring = way.parts.firstOrNull()?.firstOrNull() ?: continue
                        if (ring.size < 2) continue
                        val kind = tags.kind(way)
                        val mid = (ring.size / 4) * 2
                        val lat = way.label?.get(0) ?: ring[mid]
                        val lon = way.label?.get(1) ?: ring[mid + 1]
                        if (Kind.isRoad(kind)) {
                            val cell = (name.hashCode().toLong() shl 32) or ((lat / STREET_CELL).toLong() and 0xFFFF shl 16) or ((lon / STREET_CELL).toLong() and 0xFFFF)
                            val seen = cell in streetsBefore
                            if (!streets.add(cell) || seen) continue
                            add(name, name, lat, lon, Kind.MINOR)
                        } else if (way.closed && kind != Kind.WATER && kind != Kind.SEA && kind != Kind.LAND) {
                            // A named building or ground: a museum, a school, a stadium.
                            add(name, name, lat, lon, Kind.POI)
                        }
                    }
                }
            }
            flush()
            progress(SCAN_SHARE)
            merge(runs, count, places, out) { p -> progress(SCAN_SHARE + (1 - SCAN_SHARE) * p) }
            progress(1.0)
        } finally {
            work.deleteRecursively()
        }
    }

    /** One sorted run, read entry by entry. */
    private class Run(file: File, val number: Int) : Closeable {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 15))
        private var left = true
        lateinit var head: Entry

        fun next(): Boolean {
            if (!left) return false
            left = try {
                head = Entry(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readShort().toInt())
                true
            } catch (_: EOFException) {
                false
            }
            return left
        }

        override fun close() = input.close()
    }

    /**
     * Merges the sorted [runs] ([count] entries) into the index: the header, a record per entry, then the strings.
     * The records' place is known from [count], so records and strings are written side by side.
     */
    private fun merge(runs: List<File>, count: Int, places: PlaceGrid, out: File, progress: (Double) -> Unit) {
        val part = File(out.path + ".part")
        val readers = runs.mapIndexed { i, f -> Run(f, i) }
        try {
            RandomAccessFile(part, "rw").use { file ->
                file.setLength(0)
                val records = WriterAt(file, 16L)
                val stringsAt = 16L + count.toLong() * RECORD + 4
                val strings = WriterAt(file, stringsAt)
                val placeAt = IntArray(places.size) { -1 }
                var lastName: String? = null
                var lastNameAt = -1
                fun string(s: String): Int {
                    val at = (strings.position - stringsAt).toInt()
                    val bytes = s.toByteArray(Charsets.UTF_8)
                    strings.writeShort(bytes.size)
                    strings.write(bytes)
                    return at
                }
                val queue = PriorityQueue<Run>(maxOf(1, readers.size), compareBy<Run, Entry>(ORDER) { it.head }.thenBy { it.number })
                for (r in readers) if (r.next()) queue += r
                var written = 0
                while (queue.isNotEmpty()) {
                    val r = queue.poll()
                    val e = r.head
                    if (r.next()) queue += r
                    val name = e.plain + "\u0000" + e.shown
                    if (name != lastName) {
                        lastName = name
                        lastNameAt = string(name)
                    }
                    val town = places.nearest(e.lat, e.lon)
                    val contextAt = if (town < 0) -1 else placeAt[town].takeIf { it >= 0 } ?: string(places.name(town)).also { placeAt[town] = it }
                    records.writeInt(lastNameAt)
                    records.writeInt(e.lat)
                    records.writeInt(e.lon)
                    records.writeInt(contextAt)
                    records.writeShort(e.kind)
                    records.writeShort(0)
                    if (++written % 4096 == 0) {
                        if (Thread.interrupted()) throw InterruptedException()
                        progress(written.toDouble() / count)
                    }
                }
                if (written != count) throw IOException("Suchverzeichnis: $written statt $count Einträge")
                records.flush()
                val stringsSize = strings.position - stringsAt
                strings.flush()
                val head = WriterAt(file, 0L)
                head.write(MAGIC.toByteArray(Charsets.US_ASCII))
                head.writeInt(VERSION)
                head.writeInt(count)
                head.flush()
                WriterAt(file, stringsAt - 4).apply {
                    writeInt(stringsSize.toInt())
                    flush()
                }
                file.fd.sync()
            }
        } finally {
            readers.forEach { it.close() }
        }
        if (out.exists() && !out.delete()) throw IOException("Altes Suchverzeichnis lässt sich nicht ersetzen")
        if (!part.renameTo(out)) throw IOException("Suchverzeichnis lässt sich nicht anlegen")
    }

    /** Writes from [start] on in [file] through a buffer of its own, so several can write side by side. */
    private class WriterAt(private val file: RandomAccessFile, start: Long) {
        private val buffer = ByteArray(1 shl 16)
        private var used = 0
        private var flushedTo = start

        /** Where the next byte goes. */
        val position: Long get() = flushedTo + used

        fun write(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                if (used == buffer.size) flush()
                val n = minOf(buffer.size - used, bytes.size - at)
                System.arraycopy(bytes, at, buffer, used, n)
                used += n
                at += n
            }
        }

        fun writeInt(v: Int) {
            if (buffer.size - used < 4) flush()
            buffer[used++] = (v ushr 24).toByte()
            buffer[used++] = (v ushr 16).toByte()
            buffer[used++] = (v ushr 8).toByte()
            buffer[used++] = v.toByte()
        }

        fun writeShort(v: Int) {
            if (buffer.size - used < 2) flush()
            buffer[used++] = (v ushr 8).toByte()
            buffer[used++] = v.toByte()
        }

        fun flush() {
            if (used == 0) return
            file.seek(flushedTo)
            file.write(buffer, 0, used)
            flushedTo += used
            used = 0
        }
    }

    const val MAGIC = "NAVISRCH"
    const val RECORD = 20

    /** Separates a name from its other forms: "Genève\u0001Genf" is found as Genève and shown as Genf. */
    const val ALIAS = "\u0001"

    /** The name to show of a stored "found as\u0001shown as" (or a plain name). */
    fun shown(stored: String): String = stored.substringAfterLast(ALIAS)

    /** The plain form names are sorted and compared by: lower case, no accents, ß as ss, only letters, digits and single spaces. */
    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s.lowercase().replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("œ", "oe"), Normalizer.Form.NFD)
        val out = StringBuilder(folded.length)
        var space = false
        for (c in folded) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.append(c)
                }
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.append(c)
                }
                else -> space = true
            }
        }
        return out.toString()
    }

    /** The towns and villages (each with all its names), in cells, for the nearest one to any point. */
    private class PlaceGrid {
        private val names = ArrayList<String>()
        private val lat = IntList()
        private val lon = IntList()
        private val kind = IntList()
        private val cells = HashMap<Long, IntList>()

        val size: Int get() = names.size

        fun name(i: Int): String = names[i]

        fun add(name: String, la: Int, lo: Int, k: Int) {
            val i = names.size
            names += name
            lat.add(la)
            lon.add(lo)
            kind.add(k)
            cells.getOrPut(cell(la / CELL, lo / CELL)) { IntList(4) }.add(i)
        }

        private fun cell(cy: Int, cx: Int) = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)

        /** The nearest town, village or quarter within some 8 km (towns count a bit closer), or −1. */
        fun nearest(la: Int, lo: Int): Int {
            val cy = la / CELL
            val cx = lo / CELL
            val k = cos(Math.toRadians(la / 1e6))
            var best = -1
            var bestD = Double.MAX_VALUE
            for (dy in -1..1) for (dx in -1..1) {
                val list = cells[cell(cy + dy, cx + dx)] ?: continue
                for (j in 0 until list.size) {
                    val i = list[j]
                    val y = (lat[i] - la).toDouble()
                    val x = (lon[i] - lo) * k
                    val weight = when (kind[i]) {
                        Kind.CITY -> 0.55
                        Kind.TOWN -> 0.75
                        else -> 1.0
                    }
                    val d = (x * x + y * y) * weight * weight
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
            }
            return if (best < 0 || bestD > MAX_D * MAX_D) -1 else best
        }

        companion object {
            const val CELL = 60_000
            const val MAX_D = 75_000.0
        }
    }
}

/** A built search index of one map, searched straight in its file. Thread-safe. */
class SearchIndex(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val count: Int
    private val stringsAt: Long

    init {
        val magic = ByteArray(8).also { raf.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != SearchIndexBuilder.MAGIC || raf.readInt() != 1) {
            raf.close()
            throw IOException("Kein Suchverzeichnis: ${file.name}")
        }
        count = raf.readInt()
        stringsAt = 16L + count.toLong() * SearchIndexBuilder.RECORD + 4
    }

    val size: Int get() = count

    override fun close() = raf.close()

    private class Record(val plain: String, val name: String, val lat: Int, val lon: Int, val context: String?, val kind: Int)

    @Synchronized
    private fun record(i: Int): Record {
        raf.seek(16L + i.toLong() * SearchIndexBuilder.RECORD)
        val nameAt = raf.readInt()
        val lat = raf.readInt()
        val lon = raf.readInt()
        val contextAt = raf.readInt()
        val kind = raf.readShort().toInt()
        val both = string(nameAt)
        val cut = both.indexOf('\u0000')
        return Record(both.substring(0, cut), both.substring(cut + 1), lat, lon, if (contextAt < 0) null else string(contextAt), kind)
    }

    private fun string(at: Int): String {
        raf.seek(stringsAt + at)
        val n = raf.readUnsignedShort()
        val bytes = ByteArray(n)
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** The first record whose plain name is not below [prefix]. */
    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (record(mid).plain < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Places whose name starts with one of the words of [query] and that hold the other words in name or
     * town, best first: exact names, towns before streets, near [near] before far.
     */
    fun search(query: String, near: LatLon? = null, limit: Int = 8): List<Place> = scored(query, near, limit).map { it.second }

    /** [search] with each place's score. */
    fun scored(query: String, near: LatLon?, limit: Int): List<Pair<Double, Place>> {
        val words = SearchIndexBuilder.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        val found = HashMap<String, Pair<Double, Place>>()
        // A run of the words starts the name ("Zürich Bahnhofstrasse" as well as "Bahnhofstrasse Zürich",
        // "Rue du Rhône Genève"); the longest runs first, so common starts like "rue" are not scanned alone.
        val runs = LinkedHashSet<String>()
        for (from in words.indices) for (to in from + 1..words.size) runs += words.subList(from, to).joinToString(" ")
        for (anchor in runs.sortedByDescending { it.length }.take(MAX_ANCHORS)) {
            var i = lowerBound(anchor)
            var scanned = 0
            while (i < count && scanned < MAX_SCAN) {
                val r = record(i++)
                scanned++
                if (!r.plain.startsWith(anchor)) break
                val haystack = r.plain + " " + (r.context?.let { SearchIndexBuilder.normalize(it.replace(SearchIndexBuilder.ALIAS, " ")) } ?: "")
                if (words.any { it !in haystack }) continue
                val place = Place(r.name, r.context?.substringBefore(SearchIndexBuilder.ALIAS), r.lat / 1e6, r.lon / 1e6, r.kind)
                val score = score(r, whole, near)
                val key = place.label
                val old = found[key]
                if (old == null || old.first < score) found[key] = score to place
            }
        }
        return found.values.sortedByDescending { it.first }.take(limit)
    }

    private fun score(r: Record, whole: String, near: LatLon?): Double {
        var s = when {
            r.plain == whole -> 100.0
            r.plain.startsWith("$whole ") -> 70.0
            r.plain.startsWith(whole) -> 50.0
            else -> 30.0
        }
        s += when (r.kind) {
            Kind.CITY -> 45.0
            Kind.TOWN -> 38.0
            Kind.STATION -> 32.0
            Kind.VILLAGE -> 28.0
            Kind.SUBURB -> 22.0
            Kind.MINOR -> 15.0
            Kind.PEAK -> 12.0
            Kind.POI -> 10.0
            else -> 6.0
        }
        s -= min(30.0, r.plain.length / 4.0)
        if (near != null) {
            val km = Geo.distance(near.lat, near.lon, r.lat / 1e6, r.lon / 1e6) / 1000
            s -= 14 * log10(km + 1)
        }
        return s
    }

    companion object {
        /** Records looked at per start at most, and starts tried per search. */
        const val MAX_SCAN = 4_000
        const val MAX_ANCHORS = 6

        /** Results of several maps' indexes together, best first; a place in the strip two maps share comes once. */
        fun searchAll(indexes: List<SearchIndex>, query: String, near: LatLon?, limit: Int = 8): List<Place> {
            val seen = HashSet<String>()
            return indexes.flatMap { it.scored(query, near, limit) }
                .sortedByDescending { it.first }
                .map { it.second }
                .filter { p -> seen.add(p.label + "@" + (p.lat * 500).toInt() + "," + (p.lon * 500).toInt()) }
                .take(limit)
        }
    }
}

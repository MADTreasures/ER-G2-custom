package ch.madtreasures.navi

import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What a map picture shows: [center] at point ([anchorX], [anchorY]) of a [w] × [h] picture, at [zoom]
 * (Web Mercator, 256 px tiles), turned so that the direction [rotation] (degrees clockwise from north)
 * points up: 0 is north up, the direction of travel gives "heading up".
 */
class MapView(
    val center: LatLon,
    val zoom: Double,
    val w: Int,
    val h: Int,
    val rotation: Double = 0.0,
    val anchorX: Double = w / 2.0,
    val anchorY: Double = h / 2.0,
) {
    private val cx = Geo.pixelX(center.lon, zoom)
    private val cy = Geo.pixelY(center.lat, zoom)
    private val cosR = cos(Math.toRadians(-rotation))
    private val sinR = sin(Math.toRadians(-rotation))
    private val size = Geo.mapSize(zoom)

    /** Metres per pixel at the centre. */
    val metresPerPixel: Double = Geo.metresPerPixel(center.lat, zoom)

    /** Screen x and y of a point in microdegrees, into [out] at [at]. */
    fun project(latE6: Int, lonE6: Int, out: DoubleArray, at: Int) {
        val wx = ((lonE6 / 1e6) + 180) / 360 * size - cx
        val s = sin(Math.toRadians((latE6 / 1e6).coerceIn(-85.0511, 85.0511)))
        val wy = (0.5 - kotlin.math.ln((1 + s) / (1 - s)) / (4 * Math.PI)) * size - cy
        out[at] = wx * cosR - wy * sinR + anchorX
        out[at + 1] = wx * sinR + wy * cosR + anchorY
    }

    fun project(p: LatLon): Pair<Double, Double> {
        val out = DoubleArray(2)
        project(p.latE6, p.lonE6, out, 0)
        return out[0] to out[1]
    }

    /** The point under screen ([sx], [sy]). */
    fun latLon(sx: Double, sy: Double): LatLon {
        val dx = sx - anchorX
        val dy = sy - anchorY
        // Inverse rotation.
        val wx = dx * cosR + dy * sinR + cx
        val wy = -dx * sinR + dy * cosR + cy
        return LatLon(Geo.latOfPixel(wy, zoom), Geo.lonOfPixel(wx, zoom))
    }

    /** The box of everything on screen, [margin] px around it. */
    fun box(margin: Double = 0.0): Box {
        val corners = listOf(latLon(-margin, -margin), latLon(w + margin, -margin), latLon(-margin, h + margin), latLon(w + margin, h + margin))
        return Box(
            (corners.minOf { it.lat } * 1e6).toInt(), (corners.minOf { it.lon } * 1e6).toInt(),
            (corners.maxOf { it.lat } * 1e6).toInt() + 1, (corners.maxOf { it.lon } * 1e6).toInt() + 1,
        )
    }
}

/** A map file to draw from, with its decoded blocks kept for the next pictures. */
class MapSource(val file: File) : Closeable {
    val map = MapFile(file)
    val tags = MapTags(map.header)
    val box: Box get() = map.header.box

    /** The highest zoom the map has data for. */
    val maxZoom: Int = map.header.subFiles.maxOf { it.maxZoom }

    /** Whether the map holds every street, for routes and search: a sub-file of zoom 14 blocks (not so the world overview). */
    val routable: Boolean = map.header.subFiles.any { it.baseZoom >= DETAIL_ZOOM }
    private val cache = LinkedHashMap<Long, MapBlock>(32, 0.75f, true)
    private var cachedBytes = 0L

    /** Block ([x], [y]) of [sub] with what appears up to [zoom]; the last ones drawn stay, up to [CACHE_BYTES]. */
    fun block(sub: SubFile, x: Int, y: Int, zoom: Int): MapBlock? {
        val key = (sub.number.toLong() shl 58) or (zoom.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()
        synchronized(cache) { cache[key]?.let { return it } }
        val b = map.block(sub, x, y, zoom) ?: return null
        synchronized(cache) {
            cachedBytes += b.bytes - (cache.put(key, b)?.bytes ?: 0)
            val it = cache.entries.iterator()
            while (cachedBytes > CACHE_BYTES && it.hasNext()) {
                val old = it.next()
                if (old.key == key) continue
                cachedBytes -= old.value.bytes
                it.remove()
            }
        }
        return b
    }

    override fun close() = map.close()

    companion object {
        /** Decoded blocks kept for the next pictures: a few blocks of a city, many more of the countryside. */
        const val CACHE_BYTES = 16L shl 20

        /** Base zoom of the sub-file with every way, as the mapsforge server's country maps have it. */
        const val DETAIL_ZOOM = 14

        /** A map is left out where the view needs data this many zoom levels finer than it has (the world overview close up). */
        const val ZOOM_REACH = 3
    }
}

/** The route on the map: points in microdegrees; up to [done] (index and fraction of the next leg) it is behind. */
class RouteLine(val lat: IntArray, val lon: IntArray, val done: Int = 0, val doneFraction: Double = 0.0)

/** What lies over the map. [heading]: direction of the position arrow in degrees, null shows a dot. */
data class Overlay(
    val route: RouteLine? = null,
    val position: LatLon? = null,
    val heading: Double? = null,
    val accuracyM: Double? = null,
    val start: LatLon? = null,
    val destination: LatLon? = null,
    val labels: Boolean = true,
    val attribution: Boolean = true,
    /** 2: everything the zoom allows; 1: no paths, service roads, streams and buildings (for a smaller picture). */
    val detail: Int = 2,
)

/**
 * Draws maps for the glasses: streets as lines whose brightness and width tell their weight, paths dashed,
 * water dim, buildings only close up, place names with a black rim; over it the route bright with a black
 * edge, the destination and the position arrow. Calm on purpose, like Even's own navigation.
 */
class MapRenderer(private val fonts: Fonts) {

    private class Item(val way: MapWay, val kind: Int, val flags: Int)

    private class Label(val text: String, val kind: Int, val lat: Int, val lon: Int)

    /** Draws [view] from [sources] into [r] with [overlay]; returns how many ways were drawn. */
    fun render(r: Raster, view: MapView, sources: List<MapSource>, overlay: Overlay = Overlay()): Int {
        val zoom = view.zoom
        val dataZoom = dataZoom(zoom)
        val box = view.box(margin = 24.0)
        val areas = ArrayList<Item>()
        val lines = ArrayList<Item>()
        val labels = ArrayList<Label>()
        val seaBlocks = ArrayList<Box>()
        for (src in sources) {
            if (!src.box.intersects(box) || dataZoom > src.maxZoom + MapSource.ZOOM_REACH) continue
            val sub = src.map.subFileFor(dataZoom)
            for ((bx, by) in src.map.blocksIn(sub, box)) {
                val block = src.block(sub, bx, by, dataZoom) ?: continue
                if (block.water && block.ways.isEmpty()) seaBlocks += blockBox(bx, by, sub.baseZoom)
                val mask = subTileMask(box, bx, by, sub.baseZoom)
                for (way in block.ways) {
                    if (way.subTiles and mask == 0) continue
                    val kind = src.tags.kind(way)
                    if (kind == Kind.NONE || !visible(kind, zoom, overlay.detail)) continue
                    val flags = src.tags.flags(way)
                    val item = Item(way, kind, flags)
                    if (isArea(kind, flags, way)) areas += item else lines += item
                }
                if (overlay.labels) for (poi in block.pois) {
                    val kind = src.tags.kind(poi)
                    if (!Kind.isPlace(kind) || !labelVisible(kind, zoom)) continue
                    val name = MapFile.localName(poi.name) ?: continue
                    labels += Label(name, kind, poi.lat, poi.lon)
                }
            }
        }
        val scale = 2.0.pow((zoom - 16) * 0.5).coerceIn(0.45, 1.8)
        val buf = Buffers()
        for (b in seaBlocks) fillBox(r, view, b, Ink.level(3))
        // The map writer lays the sea over whole blocks and the land on top of it: land clears the sea again.
        for (a in areas.sortedBy { areaOrder(it.kind) }) drawArea(r, view, a, buf)
        // Lines in a fixed order; brightening makes overlaps even, so only the dark tunnels go first.
        for (l in lines.sortedByDescending { Kind.rank(it.kind) }) drawLine(r, view, l, scale, zoom, buf)
        if (overlay.labels) drawLabels(r, view, labels)
        overlay.route?.let { drawRoute(r, view, it, scale) }
        overlay.start?.let { drawStart(r, view, it) }
        overlay.destination?.let { drawDestination(r, view, it) }
        overlay.position?.let { drawPosition(r, view, it, overlay.heading, overlay.accuracyM) }
        if (overlay.attribution) fonts.small.draw(r, ATTRIBUTION, 6.0, r.h - 6.0, Ink.level(6), halo = 1)
        return areas.size + lines.size
    }

    // --- What to draw --------------------------------------------------------------------------------

    private fun visible(kind: Int, zoom: Double, detail: Int): Boolean = when (kind) {
        Kind.BUILDING, Kind.SERVICE, Kind.TRACK, Kind.PATH, Kind.CYCLEWAY, Kind.STEPS, Kind.STREAM, Kind.AERIALWAY ->
            detail >= 2 && visible(kind, zoom)
        else -> visible(kind, zoom)
    }

    private fun visible(kind: Int, zoom: Double): Boolean = when (kind) {
        Kind.BUILDING -> zoom >= 16.5
        Kind.SERVICE, Kind.TRACK, Kind.PATH, Kind.CYCLEWAY, Kind.STEPS, Kind.PEDESTRIAN -> zoom >= 14
        Kind.MINOR -> zoom >= 12.5
        Kind.STREAM -> zoom >= 13
        Kind.SUBWAY, Kind.FOREST -> false
        Kind.AERIALWAY -> zoom >= 14
        else -> true
    }

    private fun labelVisible(kind: Int, zoom: Double): Boolean = when (kind) {
        Kind.CITY -> zoom in 6.0..15.5
        Kind.TOWN -> zoom in 9.5..16.5
        Kind.VILLAGE -> zoom in 11.5..17.5
        Kind.SUBURB -> zoom in 12.5..16.5
        Kind.HAMLET -> zoom in 14.5..18.0
        else -> false
    }

    /** Sea first, then the land that clears it, then lakes and the rest. */
    private fun areaOrder(kind: Int) = when (kind) {
        Kind.SEA -> 0
        Kind.LAND -> 1
        else -> 2
    }

    private fun isArea(kind: Int, flags: Int, way: MapWay) = when (kind) {
        Kind.WATER, Kind.SEA, Kind.LAND, Kind.GLACIER -> true
        Kind.PEDESTRIAN -> flags and Flag.AREA != 0 && way.closed
        else -> false
    }

    // --- Areas ---------------------------------------------------------------------------------------

    private class Buffers {
        var xy = DoubleArray(1024)

        fun ensure(n: Int) {
            if (xy.size < n) xy = DoubleArray(max(n, xy.size * 2))
        }
    }

    private fun drawArea(r: Raster, view: MapView, item: Item, buf: Buffers) {
        val ink = when (item.kind) {
            Kind.SEA -> Ink.level(3)
            Kind.LAND -> Ink.ERASE
            Kind.WATER -> Ink.level(3)
            Kind.GLACIER -> Ink.level(3)
            Kind.PEDESTRIAN -> Ink.level(3)
            else -> return
        }
        for (part in item.way.parts) {
            val rings = part.map { ring -> projectRing(view, ring) }
            if (rings.all { offScreen(it, r, 0.0) }) continue
            r.polygon(rings, ink)
        }
        // Squares get an outline like the streets they belong to.
        if (item.kind == Kind.PEDESTRIAN) {
            for (part in item.way.parts) for (ring in part) {
                val xy = projectRing(view, ring)
                polylineXY(r, xy, 1.2, Ink.level(7), null)
            }
        }
    }

    private fun fillBox(r: Raster, view: MapView, b: Box, ink: Ink) {
        val ring = DoubleArray(8)
        view.project(b.maxLat, b.minLon, ring, 0)
        view.project(b.maxLat, b.maxLon, ring, 2)
        view.project(b.minLat, b.maxLon, ring, 4)
        view.project(b.minLat, b.minLon, ring, 6)
        r.polygon(listOf(ring), ink)
    }

    // --- Lines ---------------------------------------------------------------------------------------

    private class Style(val width: Double, val level: Int, val dash: DoubleArray? = null)

    private fun style(kind: Int, flags: Int, zoom: Double): Style? = when (kind) {
        Kind.MOTORWAY -> Style(4.6, 12)
        Kind.TRUNK -> Style(4.4, 12)
        Kind.PRIMARY -> Style(4.0, 11)
        Kind.SECONDARY -> Style(3.6, 11)
        Kind.TERTIARY -> Style(3.2, 10)
        Kind.MINOR -> Style(2.6, 9)
        Kind.SERVICE -> Style(1.6, 7)
        Kind.PEDESTRIAN -> Style(2.2, 8)
        Kind.TRACK -> Style(1.6, 6, doubleArrayOf(5.0, 3.0))
        Kind.PATH -> Style(1.2, 5)
        Kind.CYCLEWAY -> Style(1.4, 6)
        Kind.STEPS -> Style(1.8, 6, doubleArrayOf(1.5, 1.5))
        Kind.RAIL -> Style(1.6, 7, doubleArrayOf(7.0, 3.0))
        Kind.TRAM -> Style(1.2, 6)
        Kind.RIVER -> Style(3.2, 6)
        Kind.STREAM -> Style(1.3, 5)
        Kind.FERRY -> Style(1.4, 6, doubleArrayOf(4.0, 4.0))
        Kind.AERIALWAY -> Style(1.0, 5, doubleArrayOf(2.0, 4.0))
        Kind.BUILDING -> Style(1.0, 3)
        else -> if (flags and Flag.BOUNDARY != 0 && flags and Flag.ADMIN_2 != 0 && zoom <= 15) Style(1.8, 8, doubleArrayOf(8.0, 4.0)) else null
    }

    private fun drawLine(r: Raster, view: MapView, item: Item, scale: Double, zoom: Double, buf: Buffers) {
        val s = style(item.kind, item.flags, zoom) ?: return
        val roadOrWater = Kind.isRoad(item.kind) || item.kind == Kind.RIVER || item.kind == Kind.STREAM
        val width = if (item.kind == Kind.BUILDING) 1.0 else max(1.0, s.width * scale)
        var level = s.level
        var dash = s.dash?.let { d -> DoubleArray(d.size) { d[it] * max(1.0, scale) } }
        if (item.flags and Flag.TUNNEL != 0 && roadOrWater) {
            level = max(Raster.MIN_LEVEL, level - 5)
            dash = doubleArrayOf(4.0, 4.0)
        }
        for (part in item.way.parts) for (ring in part) {
            val xy = projectRing(view, ring)
            if (offScreen(xy, r, width + 2)) continue
            polylineXY(r, xy, width, Ink.level(level), dash)
        }
    }

    private fun polylineXY(r: Raster, xy: DoubleArray, width: Double, ink: Ink, dash: DoubleArray?) {
        val n = xy.size / 2
        val xs = DoubleArray(n) { xy[2 * it] }
        val ys = DoubleArray(n) { xy[2 * it + 1] }
        r.polyline(xs, ys, n, width, ink, dash)
    }

    // --- Labels --------------------------------------------------------------------------------------

    private fun drawLabels(r: Raster, view: MapView, labels: List<Label>) {
        val placed = ArrayList<IntArray>()
        val xy = DoubleArray(2)
        for (l in labels.distinctBy { it.text to it.kind }.sortedBy { it.kind }) {
            view.project(l.lat, l.lon, xy, 0)
            val font = if (l.kind == Kind.CITY || l.kind == Kind.TOWN) fonts.label else fonts.small
            val w = font.width(l.text)
            val x0 = (xy[0] - w / 2).toInt()
            val y0 = (xy[1] - font.ascent / 2.0 - 2).toInt()
            val rect = intArrayOf(x0 - 4, y0 - font.ascent - 2, x0 + w.toInt() + 4, y0 + font.descent + 2)
            if (rect[2] < 0 || rect[0] > r.w || rect[3] < 0 || rect[1] > r.h - 20) continue
            if (placed.any { it[0] < rect[2] && rect[0] < it[2] && it[1] < rect[3] && rect[1] < it[3] }) continue
            placed += rect
            font.draw(r, l.text, x0.toDouble(), y0.toDouble(), Ink.level(13), halo = 2)
        }
    }

    // --- Route and markers ---------------------------------------------------------------------------

    private fun drawRoute(r: Raster, view: MapView, route: RouteLine, scale: Double) {
        val n = route.lat.size
        if (n < 2) return
        val xy = DoubleArray(2 * n)
        for (i in 0 until n) view.project(route.lat[i], route.lon[i], xy, 2 * i)
        val width = max(4.0, 6.0 * scale)
        val split = route.done.coerceIn(0, n - 1)
        val fx = xy[2 * split] + ((if (split + 1 < n) xy[2 * split + 2] else xy[2 * split]) - xy[2 * split]) * route.doneFraction
        val fy = xy[2 * split + 1] + ((if (split + 1 < n) xy[2 * split + 3] else xy[2 * split + 1]) - xy[2 * split + 1]) * route.doneFraction
        val xs = DoubleArray(n) { xy[2 * it] }
        val ys = DoubleArray(n) { xy[2 * it + 1] }
        r.polyline(xs, ys, n, width + 5, Ink.ERASE)
        // Behind: dim; ahead: as bright as the glasses go.
        val behindX = DoubleArray(split + 2)
        val behindY = DoubleArray(split + 2)
        for (i in 0..split) {
            behindX[i] = xs[i]
            behindY[i] = ys[i]
        }
        behindX[split + 1] = fx
        behindY[split + 1] = fy
        r.polyline(behindX, behindY, split + 2, width * 0.6, Ink.level(7))
        val aheadX = DoubleArray(n - split)
        val aheadY = DoubleArray(n - split)
        aheadX[0] = fx
        aheadY[0] = fy
        for (i in split + 1 until n) {
            aheadX[i - split] = xs[i]
            aheadY[i - split] = ys[i]
        }
        r.polyline(aheadX, aheadY, n - split, width, Ink.level(15))
    }

    private fun drawStart(r: Raster, view: MapView, p: LatLon) {
        val (x, y) = view.project(p)
        r.circle(x, y, 10.0, Ink.ERASE)
        r.ring(x, y, 6.5, 2.5, Ink.level(12))
    }

    private fun drawDestination(r: Raster, view: MapView, p: LatLon) {
        val (x, y) = view.project(p)
        r.circle(x, y, 14.0, Ink.ERASE)
        r.ring(x, y, 9.5, 3.5, Ink.level(15))
        r.circle(x, y, 3.5, Ink.level(15))
    }

    /** The position: an arrow in the direction of travel, or a dot while it is unknown. */
    fun drawPosition(r: Raster, view: MapView, p: LatLon, heading: Double?, accuracyM: Double?) {
        val (x, y) = view.project(p)
        accuracyM?.let { acc ->
            val radius = acc / view.metresPerPixel
            if (acc > 15 && radius in 14.0..220.0) r.ring(x, y, radius, 1.4, Ink.level(5))
        }
        r.circle(x, y, 17.0, Ink.ERASE)
        if (heading == null) {
            r.circle(x, y, 6.5, Ink.level(15))
            r.ring(x, y, 11.5, 2.0, Ink.level(11))
            return
        }
        arrow(r, x, y, heading - view.rotation, 15.0, Ink.level(15))
    }

    companion object {
        const val ATTRIBUTION = "© OpenStreetMap"

        /** Which zoom's data a picture at [zoom] needs: below 13.5 the overview sub-file, with main roads only. */
        fun dataZoom(zoom: Double): Int = when {
            zoom >= 13.5 -> ceil(zoom - 1e-9).toInt().coerceAtMost(21)
            zoom >= 8 -> min(ceil(zoom - 1e-9).toInt(), 11)
            else -> ceil(zoom - 1e-9).toInt().coerceAtLeast(0)
        }

        /** An arrow head of [size] pointing in [direction] (degrees, 0 = up), centred on ([cx], [cy]). */
        fun arrow(r: Raster, cx: Double, cy: Double, direction: Double, size: Double, ink: Ink) {
            val a = Math.toRadians(direction)
            fun pt(dx: Double, dy: Double): Pair<Double, Double> = (cx + dx * cos(a) - dy * sin(a)) to (cy + dx * sin(a) + dy * cos(a))
            val tip = pt(0.0, -size)
            val right = pt(size * 0.72, size * 0.75)
            val notch = pt(0.0, size * 0.32)
            val left = pt(-size * 0.72, size * 0.75)
            r.polygon(listOf(doubleArrayOf(tip.first, tip.second, right.first, right.second, notch.first, notch.second, left.first, left.second)), ink)
        }

        fun projectRing(view: MapView, ring: IntArray): DoubleArray {
            val n = ring.size / 2
            val out = DoubleArray(2 * n)
            for (i in 0 until n) view.project(ring[2 * i], ring[2 * i + 1], out, 2 * i)
            return out
        }

        fun offScreen(xy: DoubleArray, r: Raster, margin: Double): Boolean {
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (i in xy.indices step 2) {
                minX = min(minX, xy[i])
                maxX = max(maxX, xy[i])
                minY = min(minY, xy[i + 1])
                maxY = max(maxY, xy[i + 1])
            }
            return maxX < -margin || minX > r.w + margin || maxY < -margin || minY > r.h + margin
        }

        /** The box of block ([x], [y]) at [zoom]. */
        fun blockBox(x: Int, y: Int, zoom: Int): Box = Box(
            (Geo.tileLat(y + 1, zoom) * 1e6).toInt(), (Geo.tileLon(x, zoom) * 1e6).toInt(),
            (Geo.tileLat(y, zoom) * 1e6).toInt(), (Geo.tileLon(x + 1, zoom) * 1e6).toInt(),
        )

        /** The quarter-quarters (zoom + 2) of block ([bx], [by]) that [box] touches, as the ways' [MapWay.subTiles] bits. */
        fun subTileMask(box: Box, bx: Int, by: Int, zoom: Int): Int {
            val z = zoom + 2
            val c0 = Geo.tileX(box.minLon / 1e6, z) - bx * 4
            val c1 = Geo.tileX(box.maxLon / 1e6, z) - bx * 4
            val r0 = Geo.tileY(box.maxLat / 1e6, z) - by * 4
            val r1 = Geo.tileY(box.minLat / 1e6, z) - by * 4
            var mask = 0
            for (row in max(0, r0)..min(3, r1)) for (col in max(0, c0)..min(3, c1)) mask = mask or (1 shl (15 - (row * 4 + col)))
            return mask
        }
    }
}

package ch.madtreasures.navi

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** A point on the earth in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    /** Microdegrees, as map files store them. */
    val latE6: Int get() = Math.round(lat * 1e6).toInt()
    val lonE6: Int get() = Math.round(lon * 1e6).toInt()

    fun distanceTo(other: LatLon): Double = Geo.distance(lat, lon, other.lat, other.lon)

    fun bearingTo(other: LatLon): Double = Geo.bearing(lat, lon, other.lat, other.lon)

    override fun toString() = String.format(java.util.Locale.ROOT, "%.6f,%.6f", lat, lon)

    companion object {
        fun e6(lat: Int, lon: Int) = LatLon(lat / 1e6, lon / 1e6)
    }
}

/** A box in microdegrees, south-west to north-east. */
data class Box(val minLat: Int, val minLon: Int, val maxLat: Int, val maxLon: Int) {
    fun contains(lat: Int, lon: Int) = lat in minLat..maxLat && lon in minLon..maxLon

    fun intersects(o: Box) = o.minLat <= maxLat && o.maxLat >= minLat && o.minLon <= maxLon && o.maxLon >= minLon

    val center: LatLon get() = LatLon((minLat + maxLat) / 2e6, (minLon + maxLon) / 2e6)

    companion object {
        /** The box of [points] (lat, lon pairs in degrees), grown by [marginM] metres on every side. */
        fun around(points: List<LatLon>, marginM: Double = 0.0): Box {
            val dLat = marginM / Geo.M_PER_DEG
            val midLat = points.sumOf { it.lat } / points.size
            val dLon = marginM / (Geo.M_PER_DEG * cos(Math.toRadians(midLat)))
            return Box(
                ((points.minOf { it.lat } - dLat) * 1e6).toInt(), ((points.minOf { it.lon } - dLon) * 1e6).toInt(),
                ((points.maxOf { it.lat } + dLat) * 1e6).toInt(), ((points.maxOf { it.lon } + dLon) * 1e6).toInt(),
            )
        }
    }
}

/** Distances and directions on the earth, and the Web Mercator tiling of map files (256 px per tile). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Metres per degree of latitude. */
    const val M_PER_DEG = EARTH_RADIUS_M * PI / 180

    /** Great-circle distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Distance in metres between two points in microdegrees. */
    fun distanceE6(lat1: Int, lon1: Int, lat2: Int, lon2: Int) = distance(lat1 / 1e6, lon1 / 1e6, lat2 / 1e6, lon2 / 1e6)

    /** Initial direction from the first to the second point: degrees clockwise from north, 0 until 360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The turn from direction [from] to [to] in degrees, −180 (left) until 180 (right). */
    fun turn(from: Double, to: Double): Double {
        var d = (to - from) % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    // --- Web Mercator ----------------------------------------------------------------------------

    fun mapSize(zoom: Double): Double = 256.0 * Math.pow(2.0, zoom)

    /** Map pixel x of [lon] at [zoom] (0 at 180° W). */
    fun pixelX(lon: Double, zoom: Double): Double = (lon + 180) / 360 * mapSize(zoom)

    /** Map pixel y of [lat] at [zoom] (0 at the north edge). */
    fun pixelY(lat: Double, zoom: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0511, 85.0511)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * mapSize(zoom)
    }

    fun lonOfPixel(x: Double, zoom: Double): Double = x / mapSize(zoom) * 360 - 180

    fun latOfPixel(y: Double, zoom: Double): Double {
        val n = PI - 2 * PI * y / mapSize(zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** The tile column of [lon] at integer [zoom], as map files count it. */
    fun tileX(lon: Double, zoom: Int): Int =
        floor(pixelX(lon, zoom.toDouble()) / 256).toInt().coerceIn(0, (1 shl zoom) - 1)

    fun tileY(lat: Double, zoom: Int): Int =
        floor(pixelY(lat, zoom.toDouble()) / 256).toInt().coerceIn(0, (1 shl zoom) - 1)

    /** West edge of tile column [x]. */
    fun tileLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360 - 180

    /** North edge of tile row [y]. */
    fun tileLat(y: Int, zoom: Int): Double {
        val n = PI - 2 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Metres per map pixel at [lat] and [zoom]. */
    fun metresPerPixel(lat: Double, zoom: Double): Double = 2 * PI * EARTH_RADIUS_M * cos(Math.toRadians(lat)) / mapSize(zoom)
}

/**
 * A flat view of a few kilometres around [origin], in metres east ([x]) and north ([y]): close enough for
 * snapping to a road and measuring along it.
 */
class LocalFrame(val origin: LatLon) {
    private val kx = Geo.M_PER_DEG * cos(Math.toRadians(origin.lat))

    fun x(lon: Double) = (lon - origin.lon) * kx

    fun y(lat: Double) = (lat - origin.lat) * Geo.M_PER_DEG

    fun lon(x: Double) = origin.lon + x / kx

    fun lat(y: Double) = origin.lat + y / Geo.M_PER_DEG
}

/** Where a point lies on a segment: [t] from 0 (start) to 1 (end), [distance] from the point in metres. */
data class SegmentHit(val t: Double, val distance: Double)

/** The nearest point of segment ([ax], [ay])–([bx], [by]) to ([px], [py]), all in metres. */
fun nearestOnSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): SegmentHit {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 <= 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * dx
    val cy = ay + t * dy
    return SegmentHit(t, sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)))
}

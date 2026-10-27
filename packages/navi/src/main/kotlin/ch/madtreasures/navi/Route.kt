package ch.madtreasures.navi

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What to do at a point of the route. */
enum class Turn(val text: String) {
    START("Los"),
    STRAIGHT("Geradeaus"),
    SLIGHT_LEFT("Leicht links"),
    LEFT("Links abbiegen"),
    SHARP_LEFT("Scharf links"),
    SLIGHT_RIGHT("Leicht rechts"),
    RIGHT("Rechts abbiegen"),
    SHARP_RIGHT("Scharf rechts"),
    UTURN("Wenden"),
    ARRIVE("Ziel erreicht"),
    ;

    companion object {
        /** The turn for an angle in degrees, −180 (left) until 180 (right). */
        fun of(angle: Double): Turn {
            val a = abs(angle)
            return when {
                a < 25 -> STRAIGHT
                a < 55 -> if (angle < 0) SLIGHT_LEFT else SLIGHT_RIGHT
                a < 130 -> if (angle < 0) LEFT else RIGHT
                a < 168 -> if (angle < 0) SHARP_LEFT else SHARP_RIGHT
                else -> UTURN
            }
        }
    }
}

/** A turn hint at point [index] of the route, onto [street]. */
data class Maneuver(val index: Int, val turn: Turn, val street: String?, val angle: Double = 0.0)

/**
 * A route: its points in microdegrees, the distance to each from the start, for every leg (point i to
 * i + 1) the name and kind of its way, and the turn hints.
 */
class Route(
    val lat: IntArray,
    val lon: IntArray,
    val cum: DoubleArray,
    val names: Array<String?>,
    val kinds: IntArray,
    val maneuvers: List<Maneuver>,
    val profile: Profile,
) {
    val size: Int get() = lat.size

    /** Metres from start to destination. */
    val length: Double get() = cum.last()

    /** Seconds it takes at the profile's speed. */
    val seconds: Double get() = length / profile.speedMs

    fun point(i: Int) = LatLon.e6(lat[i], lon[i])

    val start: LatLon get() = point(0)
    val end: LatLon get() = point(size - 1)

    /** The way's name at metre [at] from the start, or what kind of way it is ("Fussweg"). */
    fun nameAt(at: Double): String {
        val i = legAt(at)
        return names[i] ?: Kind.label(kinds[i])
    }

    /** The leg (point i to i + 1) holding metre [at]. */
    fun legAt(at: Double): Int {
        var lo = 0
        var hi = size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, maxOf(0, size - 2))
    }

    /** The route as the map draws it, behind up to metre [done]. */
    fun line(done: Double = 0.0): RouteLine {
        val i = legAt(done)
        val legLen = if (i + 1 < size) cum[i + 1] - cum[i] else 0.0
        val f = if (legLen > 0) ((done - cum[i]) / legLen).coerceIn(0.0, 1.0) else 0.0
        return RouteLine(lat, lon, i, f)
    }

    /** The box of the whole route, [marginM] metres around it. */
    fun box(marginM: Double = 0.0) = Box.around(List(size) { point(it) }, marginM)
}

/** Puts a route together point by point; legs get the way's name and kind with [segment]. */
class RouteBuilder(private val profile: Profile) {
    private val lat = IntList()
    private val lon = IntList()
    private val degree = IntList()
    private val names = ArrayList<String?>()
    private val kinds = IntList()
    private var named = 0

    fun point(latE6: Int, lonE6: Int) {
        if (lat.size > 0 && lat[lat.size - 1] == latE6 && lon[lon.size - 1] == lonE6) return
        lat.add(latE6)
        lon.add(lonE6)
        degree.add(0)
    }

    /** The last point is a node where [ways] ways meet. */
    fun markJunction(ways: Int) {
        if (degree.size > 0) degree.data[degree.size - 1] = ways
    }

    /** The legs since the last call run on a way called [name] of [kind]. */
    fun segment(name: String?, kind: Int) {
        while (named < lat.size - 1) {
            names += name
            kinds.add(kind)
            named++
        }
    }

    fun build(): Route {
        if (lat.size == 1) point(lat[0], lon[0] + 1)
        while (names.size < lat.size - 1) {
            names += null
            kinds.add(Kind.NONE)
        }
        val n = lat.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distanceE6(lat[i - 1], lon[i - 1], lat[i], lon[i])
        val la = lat.toArray()
        val lo = lon.toArray()
        val nm = names.toTypedArray()
        val kd = kinds.toArray()
        return Route(la, lo, cum, nm, kd, maneuvers(la, lo, cum, nm, degree.toArray(), kd), profile)
    }

    private fun maneuvers(lat: IntArray, lon: IntArray, cum: DoubleArray, names: Array<String?>, degree: IntArray, kinds: IntArray): List<Maneuver> {
        val n = lat.size
        val total = cum[n - 1]
        fun pointAt(at: Double): LatLon {
            val d = at.coerceIn(0.0, total)
            var i = 0
            while (i < n - 2 && cum[i + 1] < d) i++
            val leg = cum[i + 1] - cum[i]
            val f = if (leg > 0) (d - cum[i]) / leg else 0.0
            return LatLon((lat[i] + (lat[i + 1] - lat[i]) * f) / 1e6, (lon[i] + (lon[i + 1] - lon[i]) * f) / 1e6)
        }
        fun bearingIn(i: Int) = pointAt(cum[i] - LOOK_M).bearingTo(LatLon.e6(lat[i], lon[i]))
        fun bearingOut(i: Int) = LatLon.e6(lat[i], lon[i]).bearingTo(pointAt(cum[i] + LOOK_M))
        fun street(i: Int) = names.getOrNull(i) ?: Kind.label(kinds.getOrNull(i) ?: Kind.NONE)
        val raw = ArrayList<Maneuver>()
        for (i in 1 until n - 1) {
            if (degree[i] < 3 || cum[i] < EDGE_M || total - cum[i] < EDGE_M) continue
            val angle = Geo.turn(bearingIn(i), bearingOut(i))
            val turn = Turn.of(angle)
            if (turn == Turn.STRAIGHT) continue
            // A street that only bends at a junction needs no hint, nor a path that bends onto the next path.
            val slight = turn == Turn.SLIGHT_LEFT || turn == Turn.SLIGHT_RIGHT
            val same = if (names[i - 1] != null) names[i - 1] == names.getOrNull(i) else names.getOrNull(i) == null && kinds[i - 1] == kinds.getOrNull(i)
            if (slight && same) continue
            raw += Maneuver(i, turn, street(i), angle)
        }
        // Turns close together are one manoeuvre (a junction drawn as two nodes, a roundabout without tags).
        val merged = ArrayList<Maneuver>()
        var k = 0
        while (k < raw.size) {
            var j = k
            while (j + 1 < raw.size && cum[raw[j + 1].index] - cum[raw[j].index] < MERGE_M) j++
            if (j == k) {
                merged += raw[k]
            } else {
                val angle = Geo.turn(bearingIn(raw[k].index), bearingOut(raw[j].index))
                val turn = Turn.of(angle)
                if (turn != Turn.STRAIGHT) merged += Maneuver(raw[j].index, turn, street(raw[j].index), angle)
            }
            k = j + 1
        }
        val out = ArrayList<Maneuver>()
        out += Maneuver(0, Turn.START, names.firstOrNull { it != null } ?: street(0), bearingOut(0))
        out += merged
        out += Maneuver(n - 1, Turn.ARRIVE, null)
        return out
    }

    private companion object {
        /** How far before and after a node its directions are measured. */
        const val LOOK_M = 15.0

        /** Turns closer than this become one. */
        const val MERGE_M = 25.0

        /** No turn hint this close to start or destination. */
        const val EDGE_M = 12.0
    }
}

/** Distances and times as the glasses show them. */
object Format {
    /** "35 m", "150 m", "1,2 km", "12 km". */
    fun distance(m: Double): String = when {
        m < 100 -> "${(m / 5).roundToInt() * 5} m"
        m < 995 -> "${(m / 10).roundToInt() * 10} m"
        m < 9_950 -> String.format(Locale.GERMANY, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToLong()} km"
    }

    /** "1 Min", "12 Min", "1 h 05". */
    fun duration(seconds: Double): String {
        val min = maxOf(1L, (seconds / 60).roundToLong())
        return if (min < 60) "$min Min" else String.format(Locale.GERMANY, "%d h %02d", min / 60, min % 60)
    }

    /** "14:05". */
    fun clock(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val t = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        return String.format(Locale.GERMANY, "%02d:%02d", t.hour, t.minute)
    }

    /** "Norden", "Nordosten" … for a direction in degrees. */
    fun compass(bearing: Double): String = DIRECTIONS[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    private val DIRECTIONS = listOf("Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten")
}

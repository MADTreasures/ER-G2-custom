package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.AppEvent
import kotlin.math.max
import kotlin.math.min

/** Where the wearer is on the route, as the navigation screen shows it. */
data class NavState(
    /** Metres from the start along the route. */
    val along: Double,
    val remaining: Double,
    val secondsLeft: Double,
    /** The next hint ahead (the arrival at the end), and how far it is. */
    val next: Maneuver,
    val toNext: Double,
    /** The hint after it, when it follows soon. */
    val then: Maneuver?,
    /** The way the wearer is on. */
    val street: String,
    /** Where to draw the arrow: on the route while on it, else where the GPS says. */
    val position: LatLon,
    /** Direction of travel for the arrow and a heading-up map, or null while unknown. */
    val heading: Double?,
    val accuracyM: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/**
 * Follows the wearer along [route]: every GPS fix is placed on the route near where the last one was
 * (forward rather than back), which gives the next hint and what is left. Two fixes in a row far from the
 * route mean the wearer left it ([NavState.offRoute]; the app then looks for a new route).
 */
class Navigator(val route: Route) {
    private var along = 0.0
    private var leg = 0
    private var away = 0
    private var arrived = false

    /** The state after [fix]. */
    fun update(fix: AppEvent.Location): NavState {
        val p = LatLon(fix.lat, fix.lon)
        val frame = LocalFrame(p)
        var bestCost = Double.MAX_VALUE
        var bestAlong = along
        var bestLeg = leg
        var bestDistance = Double.MAX_VALUE
        // Near the last position: a little back, a good way ahead; after a long gap all of it.
        val from = max(0, leg - BACK_LEGS)
        val to = min(route.size - 2, leg + AHEAD_LEGS)
        fun look(range: IntRange) {
            for (i in range) {
                val ax = frame.x(route.lon[i] / 1e6)
                val ay = frame.y(route.lat[i] / 1e6)
                val bx = frame.x(route.lon[i + 1] / 1e6)
                val by = frame.y(route.lat[i + 1] / 1e6)
                val hit = nearestOnSegment(0.0, 0.0, ax, ay, bx, by)
                val at = route.cum[i] + (route.cum[i + 1] - route.cum[i]) * hit.t
                // Going back along the route costs a little, so a route that passes twice keeps its order.
                val cost = hit.distance + max(0.0, along - at) * 0.5
                if (cost < bestCost) {
                    bestCost = cost
                    bestAlong = at
                    bestLeg = i
                    bestDistance = hit.distance
                }
            }
        }
        look(from..to)
        if (bestDistance > OFF_ROUTE_M) look(0..route.size - 2)
        val tolerance = ON_ROUTE_M + min(fix.acc.toDouble(), MAX_ACCURACY_BONUS)
        val onRoute = bestDistance <= tolerance
        if (onRoute) {
            away = 0
            along = bestAlong
            leg = bestLeg
        } else {
            away++
        }
        val offRoute = away >= OFF_ROUTE_FIXES && bestDistance > OFF_ROUTE_M
        if (route.length - along < ARRIVE_M || p.distanceTo(route.end) < ARRIVE_M) arrived = true
        val next = route.maneuvers.firstOrNull { route.cum[it.index] > along + 1 } ?: route.maneuvers.last()
        val toNext = max(0.0, route.cum[next.index] - along)
        val after = route.maneuvers.firstOrNull { route.cum[it.index] > route.cum[next.index] + 1 }
        val then = after?.takeIf { route.cum[it.index] - route.cum[next.index] < THEN_M }
        val moving = (fix.speed ?: 0f) >= MOVING_MS && fix.bearing != null
        val heading = when {
            moving -> fix.bearing!!.toDouble()
            onRoute -> routeBearing(along)
            else -> null
        }
        val position = if (onRoute) pointAt(along) else p
        val remaining = max(0.0, route.length - along)
        return NavState(
            along, remaining, remaining / route.profile.speedMs, next, toNext, then, route.nameAt(along), position, heading,
            fix.acc.toDouble(), offRoute && !arrived, arrived,
        )
    }

    /** The point at metre [at] of the route. */
    fun pointAt(at: Double): LatLon {
        val i = route.legAt(at)
        val legLen = route.cum[i + 1] - route.cum[i]
        val f = if (legLen > 0) ((at - route.cum[i]) / legLen).coerceIn(0.0, 1.0) else 0.0
        return LatLon((route.lat[i] + (route.lat[i + 1] - route.lat[i]) * f) / 1e6, (route.lon[i] + (route.lon[i + 1] - route.lon[i]) * f) / 1e6)
    }

    /** The route's direction around metre [at] (over some metres, so a kink does not turn the map). */
    fun routeBearing(at: Double): Double = pointAt(at - 5).bearingTo(pointAt(at + 15))

    companion object {
        /** A fix this close (plus its accuracy, up to [MAX_ACCURACY_BONUS]) is on the route. */
        const val ON_ROUTE_M = 25.0
        const val MAX_ACCURACY_BONUS = 25.0

        /** Off the route: farther than this, so many fixes in a row. */
        const val OFF_ROUTE_M = 40.0
        const val OFF_ROUTE_FIXES = 2

        /** Arrived this close to the destination. */
        const val ARRIVE_M = 20.0

        /** "Then …" for a hint that follows the next one within this distance. */
        const val THEN_M = 80.0

        /** Faster than this the GPS direction counts. */
        const val MOVING_MS = 0.8f
        private const val BACK_LEGS = 3
        private const val AHEAD_LEGS = 60
    }
}

/**
 * A pretend trip along a route for the demo: a fix every [stepMs] at the profile's speed, with the GPS's
 * direction and a few metres of accuracy, so navigation can be tried while sitting.
 */
class DemoTrip(private val route: Route, private val startMs: Long, private val speedMs: Double = route.profile.speedMs) {
    private val nav = Navigator(route)

    /** The fix [elapsedMs] after the start; at the end it stays at the destination. */
    fun fixAt(elapsedMs: Long): AppEvent.Location {
        val at = min(route.length, speedMs * elapsedMs / 1000.0)
        val p = nav.pointAt(at)
        val done = at >= route.length
        return AppEvent.Location(
            p.lat, p.lon, 5f, startMs + elapsedMs,
            speed = if (done) 0f else speedMs.toFloat(),
            bearing = nav.routeBearing(at).toFloat(),
        )
    }

    /** Whether the trip reached the destination [elapsedMs] after the start. */
    fun finished(elapsedMs: Long) = speedMs * elapsedMs / 1000.0 >= route.length
}

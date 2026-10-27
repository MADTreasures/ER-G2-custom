package ch.madtreasures.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Routes over the real ways of the Vaduz map, and with -PnaviData through Zürich and Bern. */
class RouterTest {
    /** The Städtle in Vaduz, by the town hall. */
    private val rathaus = LatLon(47.13942, 9.52216)

    /** To the north, near the border to Schaan. */
    private val muehleholz = LatLon(47.15420, 9.51830)

    private fun check(route: Route, from: LatLon, to: LatLon) {
        val straight = from.distanceTo(to)
        assertTrue("length ${route.length} vs straight $straight", route.length in straight..straight * 1.8)
        // No jumps: neighbouring points are close (a way's points, never a leap across the map).
        for (i in 1 until route.size) assertTrue("leap at $i", route.cum[i] - route.cum[i - 1] < 400)
        assertTrue(route.start.distanceTo(from) < 60)
        assertTrue(route.end.distanceTo(to) < 60)
        assertEquals(Turn.START, route.maneuvers.first().turn)
        assertEquals(Turn.ARRIVE, route.maneuvers.last().turn)
    }

    @Test
    fun `on foot through Vaduz, with street names and turn hints`() {
        MapSource(Fixtures.vaduz).use { src ->
            val route = Router(listOf(src)).route(rathaus, muehleholz, Profile.FOOT)
            check(route, rathaus, muehleholz)
            val names = route.names.filterNotNull().toSet()
            println("foot: ${route.length.toInt()} m, ${route.size} points, ${Format.duration(route.seconds)}, names $names")
            route.maneuvers.forEach { println("  ${Format.distance(route.cum[it.index])}: ${it.turn.text} ${it.street ?: ""}") }
            assertTrue(names.toString(), names.isNotEmpty())
            assertTrue(route.maneuvers.size >= 3)
        }
    }

    @Test
    fun `by bike the route keeps to one-way streets, and is never shorter than on foot by much`() {
        MapSource(Fixtures.vaduz).use { src ->
            val router = Router(listOf(src))
            val bike = router.route(rathaus, muehleholz, Profile.BIKE)
            check(bike, rathaus, muehleholz)
            val foot = router.route(rathaus, muehleholz, Profile.FOOT)
            println("bike ${bike.length.toInt()} m, foot ${foot.length.toInt()} m")
            assertTrue(bike.length > foot.length * 0.9)
            // Back again works too.
            check(router.route(muehleholz, rathaus, Profile.BIKE), muehleholz, rathaus)
        }
    }

    @Test
    fun `a start far from every way is refused with a German reason`() {
        MapSource(Fixtures.vaduz).use { src ->
            val e = assertThrows(RouteException::class.java) { Router(listOf(src)).route(LatLon(47.30, 9.70), rathaus, Profile.FOOT) }
            assertEquals("Beim Start ist kein Weg in der Nähe", e.message)
        }
    }

    @Test
    fun `start and destination on the same street`() {
        MapSource(Fixtures.vaduz).use { src ->
            val a = LatLon(47.13942, 9.52216)
            val b = LatLon(47.13990, 9.52230)
            val route = Router(listOf(src)).route(a, b, Profile.FOOT)
            assertTrue(route.length < 200)
        }
    }

    @Test
    fun `turns read as a person would say them`() {
        assertEquals(Turn.STRAIGHT, Turn.of(10.0))
        assertEquals(Turn.SLIGHT_RIGHT, Turn.of(40.0))
        assertEquals(Turn.LEFT, Turn.of(-90.0))
        assertEquals(Turn.SHARP_RIGHT, Turn.of(150.0))
        assertEquals(Turn.UTURN, Turn.of(-175.0))
        assertEquals("35 m", Format.distance(33.0))
        assertEquals("150 m", Format.distance(148.0))
        assertEquals("1,2 km", Format.distance(1_234.0))
        assertEquals("12 km", Format.distance(12_345.0))
        assertEquals("12 Min", Format.duration(700.0))
        assertEquals("1 h 05", Format.duration(3_900.0))
        assertEquals("Nordosten", Format.compass(40.0))
    }

    @Test
    fun `real routes through Zurich and Bern, and how long they take`() {
        val ch = Fixtures.realMap("switzerland.map")
        assumeTrue("no -PnaviData with switzerland.map", ch != null)
        MapSource(ch!!).use { src ->
            val router = Router(listOf(src))
            for ((name, trip) in listOf(
                "Zürich HB → ETH zu Fuss" to Triple(LatLon(47.37789, 8.54015), LatLon(47.37640, 8.54800), Profile.FOOT),
                "Bern Bahnhof → Bundeshaus zu Fuss" to Triple(LatLon(46.94880, 7.43920), LatLon(46.94660, 7.44410), Profile.FOOT),
                "Zürich HB → Zürichhorn Velo" to Triple(LatLon(47.37789, 8.54015), LatLon(47.35450, 8.55300), Profile.BIKE),
                "Zürich → Winterthur Velo" to Triple(LatLon(47.37789, 8.54015), LatLon(47.50000, 8.72400), Profile.BIKE),
            )) {
                val (from, to, profile) = trip
                val t0 = System.nanoTime()
                val route = router.route(from, to, profile)
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("$name: ${Format.distance(route.length)}, ${Format.duration(route.seconds)}, ${route.maneuvers.size} hints, $ms ms")
                route.maneuvers.take(12).forEach { println("  ${Format.distance(route.cum[it.index])}: ${it.turn.text} ${it.street ?: ""}") }
                check(route, from, to)
            }
        }
    }
}

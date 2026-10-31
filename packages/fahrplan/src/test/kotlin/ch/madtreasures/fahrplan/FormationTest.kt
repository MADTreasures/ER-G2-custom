package ch.madtreasures.fahrplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Reading the Train Formation Service ([Fixtures.FORMATION]) and its formation short strings, and finding a train in it. */
class FormationTest {
    private val stops = Formation.stops(Fixtures.text(Fixtures.FORMATION))

    @Test
    fun `the cars of IR 16 at Zürich HB, from the head of the train, with their sectors`() {
        assertEquals(listOf("Zürich HB", "Baden", "Brugg AG", "Aarau", "Olten", "Bern"), stops.map { it.name })
        val zurich = stops.first()
        assertEquals("8503000", zurich.uic)
        assertEquals("15", zurich.track)
        assertEquals(listOf("8", "7", "6", "5", "4", "3", "2", "1"), zurich.cars.map { it.number })
        assertEquals(
            listOf(
                CarKind.FAMILY, CarKind.SECOND, CarKind.SECOND, CarKind.SECOND,
                CarKind.DINING_SECOND, CarKind.FIRST, CarKind.FIRST, CarKind.FIRST,
            ),
            zurich.cars.map { it.kind },
        )
        // The empty platform ahead in C and D is no part of the train.
        assertEquals(listOf("B", "B", "B", "B", "A", "A", "A", "A"), zurich.cars.map { it.sector })
        assertEquals(listOf("B", "A"), zurich.sectors)
        assertEquals("Sektoren A–B", zurich.sectorText())
        assertEquals(setOf(CarService.PRAM, CarService.LOW_FLOOR), zurich.cars[0].services)
        assertEquals(setOf(CarService.BIKE, CarService.PRAM, CarService.LOW_FLOOR), zurich.cars[1].services)
        assertEquals(setOf(CarService.WHEELCHAIR, CarService.LOW_FLOOR), zurich.cars[3].services)
        assertEquals(setOf(CarService.BIKE, CarService.BUSINESS, CarService.LOW_FLOOR), zurich.cars[7].services)
        // The dining car is open but not served.
        assertTrue(zurich.cars[4].notServed)
        assertFalse(zurich.cars.any { it.closed })
        assertNull(zurich.destinationOf(0))
    }

    @Test
    fun `in Aarau the train stands in three sectors and a car is reserved for a group`() {
        val aarau = stops[3]
        assertEquals("5", aarau.track)
        assertEquals(listOf("D", "D", "C", "C", "C", "C", "B", "B"), aarau.cars.map { it.sector })
        assertEquals("Sektoren B–D", aarau.sectorText())
        assertTrue(aarau.cars[1].groups)
        assertEquals("Sektoren B–E", stops.last().sectorText())
    }

    @Test
    fun `closed cars, locomotives, parked and fictitious cars, sectors apart`() {
        val cars = Formation.cars("@A,F,X,[LK,-2:12,12:11#BHP@B,WR,CC:9,WL,D]@C,F")
        assertEquals(listOf(CarKind.LOCO, CarKind.SECOND, CarKind.BOTH, CarKind.DINING, CarKind.COUCHETTE, CarKind.SLEEPER, CarKind.BAGGAGE), cars.map { it.kind })
        assertTrue(cars[1].closed)
        assertEquals(listOf(null, "12", "11", null, "9", null, null), cars.map { it.number })
        assertEquals(listOf("A", "A", "A", "B", "B", "B", "B"), cars.map { it.sector })
        assertEquals("Sektoren A, C", StopFormation(null, "X", null, Formation.cars("@A,[2,2]@B,F@C,[1]"), emptyList()).sectorText())
        assertEquals("Sektor F", StopFormation(null, "X", null, Formation.cars("@F,[(2:3,2:2,1):1]"), emptyList()).sectorText())
        assertTrue(Formation.cars("@A,F,F").isEmpty())
    }

    @Test
    fun `a train that splits sends its parts to different places`() {
        val goals = listOf(Goal(1, 2, "Thayngen"), Goal(3, 4, "Schaffhausen"))
        val split = StopFormation(null, "Winterthur", "3", Formation.cars("@E,[2,2@F,2,1]"), goals)
        assertEquals(listOf("Thayngen", "Thayngen", "Schaffhausen", "Schaffhausen"), split.cars.indices.map { split.destinationOf(it) })
    }

    @Test
    fun `the train number and the railway undertaking of a journey, and the request`() {
        assertEquals("2162", Formation.trainNumber("002162"))
        assertEquals("20422", Formation.trainNumber("S 24 20422"))
        assertNull(Formation.trainNumber("000"))
        assertNull(Formation.trainNumber(null))
        assertEquals("SBBP", Formation.evu("SBB"))
        assertEquals("SOB", Formation.evu("SOB-sob"))
        assertEquals("BLSP", Formation.evu("BLS-bls"))
        assertEquals("THURBO", Formation.evu("THURBO"))
        assertEquals("RhB", Formation.evu("RhB"))
        assertEquals("OeBB", Formation.evu("ÖBB"))
        // Not every railway publishes its formations.
        assertNull(Formation.evu("DB"))
        assertNull(Formation.evu("SBB GmbH"))
        assertNull(Formation.evu(null))
        assertEquals(
            "https://api.opentransportdata.swiss/formation/v2/formations_stop_based?evu=SBBP&operationDate=2026-11-02&trainNumber=2162",
            Formation.url("SBBP", "2162", LocalDate.of(2026, 11, 2)),
        )
    }

    @Test
    fun `what is no formation answer`() {
        assertTrue(runCatching { Formation.stops("""{"error":"No realtime data available for the given date."}""") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Formation.stops("<html>") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(Formation.stops("""{"formationsAtScheduledStops":[]}""").isEmpty())
    }
}

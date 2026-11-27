// Tests for Rutgers campus buses: reading the timetable, the best bus for a time, swapping a long walk for a bus, live
// times on the step, reading the live feed, and an Uber's order step
package io.github.adenkushnir.datebook.buses
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.journeys.JourneyStep
import io.github.adenkushnir.datebook.journeys.STEP_ARRIVE
import io.github.adenkushnir.datebook.journeys.STEP_BUS
import io.github.adenkushnir.datebook.journeys.STEP_DRIVE
import io.github.adenkushnir.datebook.journeys.STEP_ORDER
import io.github.adenkushnir.datebook.journeys.STEP_WALK
import io.github.adenkushnir.datebook.journeys.asUber
import io.github.adenkushnir.datebook.places.TRAVEL_UBER
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the campus bus timetable, rides, swaps and live times
class CampusBusesTest {
    private val table = parseTimetable(mapOf(
        "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,College Avenue Student Center,40.5000,-74.4500\n" +
            "B,Livingston Student Center,40.5250,-74.4350",
        "routes.txt" to "route_id,route_short_name,route_long_name,route_color\nlx,LX,LX Route,CB99FE",
        "trips.txt" to "route_id,service_id,trip_id,trip_headsign\nlx,week,t1,Livingston\nlx,week,t2,Livingston",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "t1,14:14:00,14:14:00,A,1\nt1,14:27:00,14:27:00,B,2\nt2,14:22:00,14:22:00,A,1\nt2,14:35:00,14:35:00,B,2",
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
            "week,1,1,1,1,1,0,0,20260101,20271231",
    ))
    private val near = Pair(40.5005, -74.4500)  // a minute from College Ave
    private val class_ = Pair(40.5255, -74.4350)  // a minute from Livingston

    // A time on Monday, November 30, 2026 in New Jersey, in milliseconds
    private fun at(hour: Int, minute: Int): Long {
        return LocalDateTime.of(2026, 11, 30, hour, minute).atZone(CAMPUS_ZONE).toInstant().toEpochMilli()
    }

    // A protobuf field: a nested message or text
    private fun message(number: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(number shl 3 or 2)
        out.write(body.size)
        out.write(body)
        return out.toByteArray()
    }

    // A protobuf field: a number (as a varint)
    private fun number(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(field shl 3)
        var rest = value

        // Seven bits at a time
        while (rest >= 0x80) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest shr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    @Test
    fun quotedFieldsKeepTheirCommas() {
        assertEquals(listOf("1", "Lot 48, north", ""), csvFields("1,\"Lot 48, north\","))
    }

    @Test
    fun latestBusStillGetsYouThere() {
        val ride = latestBus(table, near, class_, at(14, 40))!!
        assertEquals("t2", ride.trip.id)
        assertEquals(at(14, 21), ride.leave)
        assertEquals(at(14, 36), ride.there)
    }

    @Test
    fun noBusOnTheWeekend() {
        val saturday = LocalDateTime.of(2026, 11, 28, 14, 40).atZone(CAMPUS_ZONE).toInstant().toEpochMilli()
        assertNull(latestBus(table, near, class_, saturday))
    }

    @Test
    fun aLongWalkBecomesTheBus() {
        val walk = JourneyStep(STEP_WALK, "Leave · 40 min walk", "to Tillett Hall", starts = at(14, 0),
            ends = at(14, 40), toName = "Tillett Hall", fromLat = near.first, fromLon = near.second,
            toLat = class_.first, toLon = class_.second)
        val journey = Journey(listOf(walk, JourneyStep(STEP_ARRIVE, "Arrive · Tillett Hall", starts = at(14, 40),
            ends = at(14, 40))), at(14, 0), at(14, 40), 40)
        val swapped = withCampusBuses(journey, table)
        assertEquals(listOf(STEP_WALK, STEP_BUS, STEP_WALK, STEP_ARRIVE), swapped.steps.map { it.kind })
        assertEquals("LX bus to Livingston Student Center", swapped.steps[1].title)
        assertTrue(swapped.steps[1].under.startsWith("Scheduled · 13 min · 1 stop"))
        assertEquals(at(14, 21), swapped.leave)
        assertEquals(at(14, 36), swapped.arrive)
        val picker = campusChoices(swapped, table)
        assertEquals(2, picker.size)  // the 2:14 and yours
        val live = withCampusLive(swapped, mapOf("t2" to mapOf("A" to listOf(at(14, 24)))))
        assertTrue(live.steps[1].under.startsWith("Live · 2 min late · 13 min"))
    }

    @Test
    fun aShortWalkStaysAWalk() {
        val walk = JourneyStep(STEP_WALK, "Leave · 15 min walk", "to Tillett Hall", starts = at(14, 25),
            ends = at(14, 40), fromLat = near.first, fromLon = near.second, toLat = class_.first,
            toLon = class_.second)
        val journey = Journey(listOf(walk), at(14, 25), at(14, 40), 15)
        assertEquals(journey, withCampusBuses(journey, table))
    }

    @Test
    fun readsTheLiveFeed() {
        val event = number(2, at(14, 24) / 1000)
        val stopUpdate = message(4, "A".toByteArray()) + message(3, event)
        val update = message(1, message(1, "t2".toByteArray())) + message(2, stopUpdate)
        val feed = message(1, number(3, 1)) + message(2, message(1, "e1".toByteArray()) + message(3, update))
        assertEquals(mapOf("t2" to mapOf("A" to listOf(at(14, 24)))), parsePredictions(feed))
    }

    @Test
    fun anUberIsOrderedTenMinutesBefore() {
        val drive = JourneyStep(STEP_DRIVE, "Leave · 12 min drive", "to Tillett Hall", starts = at(14, 20),
            ends = at(14, 32), toName = "Tillett Hall")
        val journey = Journey(listOf(drive, JourneyStep(STEP_ARRIVE, "Arrive · Tillett Hall", starts = at(14, 32),
            ends = at(14, 32))), at(14, 20), at(14, 32), 12)
        val uber = asUber(journey, "Tillett Hall")
        assertEquals(listOf(STEP_ORDER, STEP_DRIVE, STEP_ARRIVE), uber.steps.map { it.kind })
        assertEquals(at(14, 5), uber.steps[0].starts)
        assertEquals("Uber · 17 min", uber.steps[1].title)
        assertEquals(at(14, 15), uber.leave)
        assertEquals(TRAVEL_UBER, uber.mode)
    }
}

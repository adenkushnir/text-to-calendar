// Tests for journeys: reading Google's route into steps with times (a bus with walks, a train with a transfer, a
// drive), the request for a time and for trains or buses only, and a picked train's live state on its ride
package io.github.adenkushnir.datebook.journeys
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks journeys from Google's reply to the steps shown
class JourneysTest {
    private val zone = ZoneId.of("America/New_York")
    private val bus = """{"routes": [{"duration": "2280s", "legs": [{"steps": [
        {"travelMode": "WALK", "staticDuration": "200s"}, {"travelMode": "WALK", "staticDuration": "160s"},
        {"travelMode": "TRANSIT", "staticDuration": "1440s", "transitDetails": {
            "stopDetails": {"departureStop": {"name": "George St at Albany St"},
                "arrivalStop": {"name": "Menlo Park Mall"},
                "departureTime": "2037-11-24T19:20:00Z", "arrivalTime": "2037-11-24T19:44:00Z"},
            "headsign": "Woodbridge Center", "stopCount": 9,
            "transitLine": {"name": "810", "nameShort": "810", "color": "#5f6f85", "vehicle": {"type": "BUS"}}}},
        {"travelMode": "WALK", "staticDuration": "180s"}]}]}]}"""
    private val rail = """{"routes": [{"duration": "4920s", "legs": [{"steps": [
        {"travelMode": "WALK", "staticDuration": "720s"},
        {"travelMode": "TRANSIT", "transitDetails": {"tripShortText": "3871",
            "stopDetails": {"departureStop": {"name": "New Brunswick"}, "arrivalStop": {"name": "Newark Penn Station"},
                "departureTime": "2037-11-27T22:37:00Z", "arrivalTime": "2037-11-27T23:05:00Z"},
            "headsign": "New York Penn Station", "stopCount": 8,
            "transitLine": {"name": "Northeast Corridor Line", "nameShort": "NEC",
                "vehicle": {"type": "COMMUTER_TRAIN"}}}},
        {"travelMode": "WALK", "staticDuration": "480s"},
        {"travelMode": "TRANSIT", "transitDetails": {
            "stopDetails": {"departureStop": {"name": "Newark Penn Station"}, "arrivalStop": {"name": "Exchange Place"},
                "departureTime": "2037-11-27T23:13:00Z", "arrivalTime": "2037-11-27T23:35:00Z"},
            "headsign": "World Trade Center", "stopCount": 4,
            "transitLine": {"name": "PATH", "nameShort": "NWK-WTC", "color": "#d0463c", "vehicle": {"type": "SUBWAY"}}}},
        {"travelMode": "WALK", "staticDuration": "240s"}]}]}]}"""
    private val drive = """{"routes": [{"duration": "1380s", "legs": [{"steps": [
        {"travelMode": "DRIVE", "staticDuration": "900s"}, {"travelMode": "DRIVE", "staticDuration": "480s"}]}]}]}"""

    // A clock time on a test day in New York
    private fun at(text: String): Long {
        return Instant.parse(text).toEpochMilli()
    }

    // A bus trip: the walks join, the walk ends when the bus leaves, and the ride keeps its line, stops and times
    @Test
    fun busTrip() {
        val journey = parseJourney(bus, "Menlo Park Mall", TRAVEL_TRANSIT, null, null, zone)!!
        assertEquals(listOf(STEP_WALK, STEP_BUS, STEP_WALK, STEP_ARRIVE), journey.steps.map { it.kind })
        assertEquals("Leave · 6 min walk", journey.steps[0].title)
        assertEquals("to George St at Albany St", journey.steps[0].under)
        assertEquals(at("2037-11-24T19:14:00Z"), journey.steps[0].starts)
        assertEquals("Bus 810 toward Woodbridge Center", journey.steps[1].title)
        assertEquals("24 min · 9 stops · from George St at Albany St · off at Menlo Park Mall",
            journey.steps[1].under)
        assertEquals("810", journey.steps[1].line)
        assertEquals(at("2037-11-24T19:47:00Z"), journey.steps[2].ends)
        assertEquals("Arrive · Menlo Park Mall", journey.steps[3].title)
        assertEquals(33, journey.minutes)
    }

    // A train then the PATH: the walk between is the transfer, starting when the train gets in
    @Test
    fun transferTrip() {
        val journey = parseJourney(rail, "Exchange Place", TRAVEL_TRANSIT, null, null, zone)!!
        assertEquals(listOf(STEP_WALK, STEP_TRAIN, STEP_WALK, STEP_SUBWAY, STEP_WALK, STEP_ARRIVE),
            journey.steps.map { it.kind })
        assertEquals("Train 3871 toward New York Penn Station", journey.steps[1].title)
        assertEquals("3871", journey.steps[1].trainId)
        assertEquals(at("2037-11-27T23:05:00Z"), journey.steps[2].starts)
        assertEquals("8 min walk", journey.steps[2].title)
        assertEquals("to Newark Penn Station", journey.steps[2].under)
        assertEquals("PATH toward World Trade Center", journey.steps[3].title)
        assertEquals(at("2037-11-27T22:25:00Z"), journey.leave)
        assertEquals(at("2037-11-27T23:39:00Z"), journey.arrive)
    }

    // A drive is one step, timed back from when you mean to be there; no route gives nothing
    @Test
    fun driveTrip() {
        val journey = parseJourney(drive, "Hilltop Field", TRAVEL_DRIVE, null, at("2037-11-24T23:00:00Z"), zone)!!
        assertEquals(listOf(STEP_DRIVE, STEP_ARRIVE), journey.steps.map { it.kind })
        assertEquals("Leave · 23 min drive", journey.steps[0].title)
        assertEquals(at("2037-11-24T22:37:00Z"), journey.leave)
        assertNull(parseJourney("{}", "Nowhere", TRAVEL_DRIVE, null, null, zone))
    }

    // Transit asks to arrive by a time or leave at one, and can be trains or buses only
    @Test
    fun requests() {
        val by = journeyBody(null, "1 College Ave", "Exchange Place", TRAVEL_TRANSIT, null, at("2037-11-27T23:59:00Z"),
            listOf("TRAIN"))!!
        assertEquals("2037-11-27T23:59:00Z", by["arrivalTime"])
        assertEquals(mapOf("allowedTravelModes" to listOf("TRAIN")), by["transitPreferences"])
        val leaving = journeyBody(null, "1 College Ave", "Menlo Park Mall", TRAVEL_TRANSIT, at("2037-11-24T19:00:00Z"),
            null, emptyList())!!
        assertEquals("2037-11-24T19:00:00Z", leaving["departureTime"])
        assertTrue(!leaving.containsKey("arrivalTime") && !leaving.containsKey("transitPreferences"))
    }

    // The picked train's live state goes on its ride only
    @Test
    fun liveTrain() {
        val journey = withLiveTrain(parseJourney(rail, "Exchange Place", TRAVEL_TRANSIT, null, null, zone)!!,
            "3871", "1",
            3, false)
        assertTrue(journey.steps[1].under.startsWith("3 min late · Track 1 · "))
        assertTrue(!journey.steps[3].under.contains("late"))
    }

    // Each step knows where it starts and ends, for Maps: the walk from home to the bus stop, the transfer walk
    @Test
    fun stepPlaces() {
        val bus = parseJourney(bus, "Menlo Park Mall", TRAVEL_TRANSIT, null, null, zone)!!
        assertEquals("", bus.steps[0].fromName)
        assertEquals("George St at Albany St", bus.steps[0].toName)
        assertEquals("Menlo Park Mall", bus.steps[2].fromName)
        val transfer = parseJourney(rail, "Exchange Place", TRAVEL_TRANSIT, null, null, zone)!!
        assertEquals("Newark Penn Station", transfer.steps[2].fromName)
        assertEquals("New Brunswick", transfer.steps[1].fromName)
    }

    // A flight's trip ends with boarding (35 min before, with the gate) and the flight leaving
    @Test
    fun flightSteps() {
        val departs = at("2037-11-25T21:10:00Z")
        val trip = withFlightSteps(parseJourney(drive, "Newark Airport", TRAVEL_DRIVE, null, departs - 65 * 60_000L,
            zone)!!, "UA2433", departs, 35, "Terminal C, gate C71", at("2037-11-25T22:27:00Z"), "Boston", zone)
        assertEquals(listOf(STEP_DRIVE, STEP_ARRIVE, STEP_BOARD, STEP_FLIGHT), trip.steps.map { it.kind })
        assertEquals("Boarding about 3:35 PM", trip.steps[2].title)
        assertEquals("Terminal C, gate C71 · 35 min before departure", trip.steps[2].under)
        assertEquals("UA2433 departs", trip.steps[3].title)
        assertEquals("Lands 5:27 PM in Boston", trip.steps[3].under)
    }

    // Walk and bike trips are planned as themselves (a bike ride reads as one), and unknown modes as a walk
    @Test
    fun walkAndBike() {
        val bike = parseJourney(drive.replace("DRIVE", "BICYCLE"), "Busch Campus", TRAVEL_BIKE, at("2037-11-24T19:00:00Z"),
            null, zone)!!
        assertEquals(STEP_BIKE, bike.steps[0].kind)
        assertEquals("Leave · 23 min bike ride", bike.steps[0].title)
        assertEquals(TRAVEL_BIKE, journeyMode(TRAVEL_BIKE))
        assertEquals(TRAVEL_WALK, journeyMode(TRAVEL_WALK))
        assertEquals(TRAVEL_WALK, journeyMode("HOVERBOARD"))
    }
}

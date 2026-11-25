// Tests for leave-by alerts: the leave and alert times, the words, which events count, the route request and reply,
// what's remembered per event, how early to be there, leaving by a train, and how flights read
package io.github.adenkushnir.datebook.leaveby
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.parseMinutes
import io.github.adenkushnir.datebook.places.routeBody
import io.github.adenkushnir.datebook.trains.Station
import io.github.adenkushnir.datebook.trains.TrainPick
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the leave-by sums, wording, choice of events, route requests and stored choices
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LeaveByTest {
    private val zone = ZoneId.of("America/New_York")
    private val sevenPm = Instant.parse("2037-11-14T00:00:00Z").toEpochMilli()  // 7:00 PM in New York

    // An 18 minute drive to a 7:00 PM dinner means leave by 6:37 PM, with the alert at 6:27 PM
    @Test
    fun times() {
        assertEquals(sevenPm - 23 * 60_000, leaveTime(sevenPm, 18))
        assertEquals(sevenPm - 33 * 60_000, alertTime(sevenPm, 18))
        assertEquals("Leave by 6:37 PM", leaveByTitle(sevenPm, 18, zone))
        assertEquals("Leave now", leaveByTitle(sevenPm, 18, zone, sevenPm - 15 * 60_000))
    }

    // A Deliberate rule to arrive 15 minutes early moves the leave time, and one for an earlier alert moves the alert
    @Test
    fun ruleTimes() {
        assertEquals(sevenPm - 38 * 60_000, leaveTime(sevenPm, 18, early = 15))
        assertEquals(sevenPm - 58 * 60_000, alertTime(sevenPm, 18, early = 15, earlier = 10))
        assertEquals("Leave by 6:22 PM", leaveByTitle(sevenPm, 18, zone, sevenPm - 60 * 60_000, early = 15))
    }

    // The trip reads naturally for each mode, and the alert offers the other two common modes
    @Test
    fun words() {
        assertEquals("18 min drive", tripText(18, TRAVEL_DRIVE))
        assertEquals("35 min by transit", tripText(35, TRAVEL_TRANSIT))
        assertEquals("12 min walk", tripText(12, TRAVEL_WALK))
        assertEquals("20 min bike ride", tripText(20, TRAVEL_BIKE))
        assertEquals(listOf(TRAVEL_TRANSIT, TRAVEL_WALK), otherModes(TRAVEL_DRIVE))
        assertEquals(listOf(TRAVEL_DRIVE, TRAVEL_TRANSIT), otherModes(TRAVEL_BIKE))
    }

    // Only timed events with a place, starting in the next 4 hours, get alerts, flights too
    @Test
    fun candidates() {
        val now = sevenPm - 2 * 3_600_000
        val dinner = CalendarEvent(1, 1, "Dinner", sevenPm, sevenPm + 3_600_000, false, "Luigi's")
        val noPlace = dinner.copy(eventId = 2, location = "")
        val allDay = dinner.copy(eventId = 3, allDay = true)
        val later = dinner.copy(eventId = 4, begin = sevenPm + 3 * 3_600_000)
        val started = dinner.copy(eventId = 5, begin = now - 60_000)
        val flight = dinner.copy(eventId = 6, title = "Flight UA1234 EWR → SFO")
        assertEquals(listOf(dinner, flight), leaveByCandidates(listOf(dinner, noPlace, allDay, later, started, flight),
            now))
    }

    // Driving asks for live traffic, transit for an arrival time; the trip starts at your location, else home
    @Test
    fun routeRequests() {
        val arrive = Instant.ofEpochMilli(sevenPm)
        val drive = routeBody(Pair(40.5, -74.4), "", "Luigi's", TRAVEL_DRIVE, arrive)!!
        assertEquals("TRAFFIC_AWARE", drive["routingPreference"])
        assertTrue((drive["origin"] as Map<*, *>).containsKey("location"))
        val transit = routeBody(null, "1 College Ave", "Luigi's", TRAVEL_TRANSIT, arrive)!!
        assertEquals("2037-11-14T00:00:00Z", transit["arrivalTime"])
        assertEquals(mapOf("address" to "1 College Ave"), transit["origin"])
        assertFalse(transit.containsKey("routingPreference"))
        assertNull(routeBody(null, "", "Luigi's", TRAVEL_WALK, arrive))
        val station = routeBody(null, "1 College Ave", "New Brunswick", TRAVEL_WALK, arrive, Pair(40.497, -74.445))!!
        assertEquals(mapOf("location" to mapOf("latLng" to mapOf("latitude" to 40.497, "longitude" to -74.445))),
            station["destination"])
    }

    // The reply's duration in seconds becomes whole minutes, rounded up; no route gives nothing
    @Test
    fun routeReplies() {
        assertEquals(18, parseMinutes("""{"routes": [{"duration": "1050s"}]}"""))
        assertEquals(1, parseMinutes("""{"routes": [{"duration": "3.5s"}]}"""))
        assertNull(parseMinutes("{}"))
    }

    // Choosing a mode forgets the old travel time; entries for events over a day old are dropped
    @Test
    fun store() {
        val store = LeaveByStore(ApplicationProvider.getApplicationContext())
        val key = eventKey("Dinner ", sevenPm)
        assertEquals("$sevenPm|dinner", key)
        store.setMinutes(key, 18, TRAVEL_DRIVE)
        assertEquals(18, store.minutes(key, TRAVEL_DRIVE))
        assertNull(store.minutes(key, TRAVEL_TRANSIT))
        store.setMode(key, TRAVEL_WALK)
        assertEquals(TRAVEL_WALK, store.mode(key))
        assertNull(store.minutes(key, TRAVEL_DRIVE))
        store.setAlerted(key)
        assertTrue(store.alerted(key))
        store.prune(sevenPm + 2 * 24 * 3_600_000)
        assertNull(store.mode(key))
        assertFalse(store.alerted(key))
    }

    // A plan's own early minutes, its train note and the trip to its station are kept, and can be put back
    @Test
    fun planSettings() {
        val store = LeaveByStore(ApplicationProvider.getApplicationContext())
        val key = eventKey("Flight B61234 EWR → BOS", sevenPm)
        assertNull(store.early(key))
        store.setEarly(key, 0)
        assertEquals(0, store.early(key))
        store.setEarly(key, null)
        assertNull(store.early(key))
        store.setMode(key, TRAVEL_TRANSIT)
        store.putMode(key, null)
        assertNull(store.mode(key))
        store.setTrainNote(key, NOTE_SWAP, "NJT 3863 was cancelled")
        assertEquals(Pair(NOTE_SWAP, "NJT 3863 was cancelled"), store.trainNote(key))
        store.setTrainNote(key, "", "")
        assertNull(store.trainNote(key))
        store.setStationTrip(key, "NB", TRAVEL_WALK, 12)
        assertEquals(Triple("NB", TRAVEL_WALK, 12), store.stationTrip(key))
    }

    // How early: this plan's choice wins, then a rule (even 0), then a flight's default (30 min before boarding, 75
    // abroad), else on time; a flight's lead adds boarding (35 min before departure, 45 abroad)
    @Test
    fun early() {
        val abroad = flight.copy(toCode = "LHR", toCountry = "GB")
        assertEquals(30, chooseEarly(30, 45, true, flight))
        assertEquals(0, chooseEarly(null, 0, true, flight))
        assertEquals(30, chooseEarly(null, null, true, flight))
        assertEquals(30, chooseEarly(null, null, true, null))
        assertEquals(75, chooseEarly(null, null, true, abroad))
        assertEquals(0, chooseEarly(null, null, false, null))
        assertEquals(65, leadMinutes(30, true, flight))
        assertEquals(120, leadMinutes(75, true, abroad))
        assertEquals(30, leadMinutes(30, false, null))
    }

    // By train: leave in time to walk to the station, plus 5 spare minutes, counting the train's delay; a walk over 20
    // minutes is timed as a drive
    @Test
    fun byTrain() {
        assertEquals(train.departs - 17 * 60_000, trainLeaveTime(train, 12))
        assertEquals(train.departs + 3 * 60_000 - 17 * 60_000, trainLeaveTime(train.copy(secondsLate = 180), 12))
        assertEquals(TRAVEL_WALK, stationMode(12, null))
        assertEquals(TRAVEL_DRIVE, stationMode(25, 9))
        assertEquals(TRAVEL_WALK, stationMode(25, null))
        assertEquals(TRAVEL_DRIVE, stationMode(null, 9))
    }

    // A flight by train puts the airport first, then the walk and the train
    @Test
    fun flightAlert() {
        val words = alertText("Flight B61234 EWR → BOS", "Newark Liberty International", byTrain,
            sevenPm - 3 * 3_600_000, zone)
        assertEquals("Leave by 5:30 PM for your flight", words.title)
        assertEquals("Be at Newark Airport by 6:30 PM · B61234 departs 7:00 PM", words.text)
        assertEquals(listOf("🚶 12 min walk to New Brunswick",
            "🚆 NJT 3863 at 5:47 PM · Track 2 · arrives Newark Airport 6:16 PM"), words.lines)
    }

    // A swapped train says what happened to the old one; with no train at all it's a drive and says why
    @Test
    fun trainTrouble() {
        val swapped = alertText("Flight B61234 EWR → BOS", "", byTrain.copy(trainNote = "NJT 3865 was cancelled"),
            sevenPm - 3 * 3_600_000, zone)
        assertEquals("NJT 3865 was cancelled · take NJT 3863 at 5:47 PM", swapped.text)
        assertEquals("✈️ Be at Newark Airport by 6:30 PM", swapped.lines.last())
        val none = byTrain.copy(train = null, station = null, noTrain = true, tripMode = TRAVEL_DRIVE,
            travel = "38 min drive", trainNote = "NJT 3863 was cancelled")
        val words = alertText("Flight B61234 EWR → BOS", "Newark Liberty International", none,
            sevenPm - 60 * 60_000, zone)
        assertEquals("Leave now for your flight", words.title)
        assertEquals("No train gets you to Newark Airport by 6:25 PM", words.text)
        assertEquals(listOf("🚗 38 min drive to Newark Liberty International", "NJT 3863 was cancelled"), words.lines)
    }

    // Other events keep their words, with the train under them
    @Test
    fun eventAlert() {
        val dinner = byTrain.copy(isFlight = false, flight = null, early = 0)
        val words = alertText("Dinner", "Luigi's", dinner, sevenPm - 3 * 3_600_000, zone)
        assertEquals("Leave by 5:30 PM", words.title)
        assertEquals("Dinner · 12 min walk to New Brunswick", words.text)
        assertEquals(listOf("🚆 NJT 3863 at 5:47 PM · Track 2 · arrives Newark Airport 6:16 PM"), words.lines)
    }

    private val flight = FlightInfo("B61234", "Expected", "EWR", "Newark", "Newark Liberty International",
        "America/New_York", "BOS", "Boston", Instant.ofEpochMilli(sevenPm), Instant.ofEpochMilli(sevenPm), null, null,
        "A", "", "US", "US")
    private val train = TrainPick("3863", "Northeast Corridor", "New Brunswick", "Newark Airport",
        sevenPm - 73 * 60_000, sevenPm - 44 * 60_000, "2", 0, "On time", "NB")
    private val byTrain = LeavePlan(sevenPm, sevenPm - 90 * 60_000, sevenPm - 100 * 60_000,
        "12 min walk to New Brunswick", TRAVEL_TRANSIT, TRAVEL_WALK, 12, 30, train,
        Station("NB", "New Brunswick", 40.497, -74.445), isFlight = true, flight = flight)
}

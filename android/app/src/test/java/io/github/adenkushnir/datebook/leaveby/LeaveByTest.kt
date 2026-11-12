// Tests for leave-by alerts: the leave and alert times, the words, which events count, the route request and reply,
// and what's remembered per event
package io.github.adenkushnir.datebook.leaveby
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.parseMinutes
import io.github.adenkushnir.datebook.places.routeBody
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

    // Only timed events with a place, starting in the next 4 hours, get alerts, and never flights
    @Test
    fun candidates() {
        val now = sevenPm - 2 * 3_600_000
        val dinner = CalendarEvent(1, 1, "Dinner", sevenPm, sevenPm + 3_600_000, false, "Luigi's")
        val noPlace = dinner.copy(eventId = 2, location = "")
        val allDay = dinner.copy(eventId = 3, allDay = true)
        val later = dinner.copy(eventId = 4, begin = sevenPm + 3 * 3_600_000)
        val started = dinner.copy(eventId = 5, begin = now - 60_000)
        val flight = dinner.copy(eventId = 6, title = "Flight UA1234 EWR → SFO")
        assertEquals(listOf(dinner), leaveByCandidates(listOf(dinner, noPlace, allDay, later, started, flight), now))
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
        store.setMinutes(key, 18)
        store.setMode(key, TRAVEL_WALK)
        assertEquals(TRAVEL_WALK, store.mode(key))
        assertNull(store.minutes(key))
        store.setAlerted(key)
        assertTrue(store.alerted(key))
        store.prune(sevenPm + 2 * 24 * 3_600_000)
        assertNull(store.mode(key))
        assertFalse(store.alerted(key))
    }
}

// Tests for live flights: reading AeroDataBox's reply, the status line, spotting changes, when checks happen, and
// what's remembered between checks
package io.github.adenkushnir.datebook.flights
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks flight data from reply to alert
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlightsTest {
    private val reply = """[{"number": "UA 1234", "status": "Expected", "codeshareStatus": "IsOperator",
        "isCargo": false, "lastUpdatedUtc": "2037-11-13 12:00Z",
        "departure": {"airport": {"iata": "EWR", "name": "Newark Liberty International", "municipalityName": "Newark",
            "timeZone": "America/New_York"}, "scheduledTime": {"utc": "2037-11-13 23:45Z",
            "local": "2037-11-13 18:45-05:00"}, "revisedTime": {"utc": "2037-11-14 00:10Z",
            "local": "2037-11-13 19:10-05:00"}, "terminal": "C", "gate": "C12", "quality": ["Basic", "Live"]},
        "arrival": {"airport": {"iata": "SFO", "name": "San Francisco International",
            "municipalityName": "San Francisco", "timeZone": "America/Los_Angeles"},
            "scheduledTime": {"utc": "2037-11-14 05:58Z", "local": "2037-11-13 21:58-08:00"}, "quality": ["Basic"]},
        "airline": {"name": "United", "iata": "UA", "icao": "UAL"}}]"""

    // The reply becomes the flight's route, scheduled and best-known times, terminal and gate
    @Test
    fun readsReply() {
        val info = parseFlight(reply, "2037-11-13")!!
        assertEquals("UA1234", info.number)
        assertEquals("EWR", info.fromCode)
        assertEquals("America/New_York", info.fromZone)
        assertEquals(Instant.parse("2037-11-13T23:45:00Z"), info.departScheduled)
        assertEquals(Instant.parse("2037-11-14T00:10:00Z"), info.departBest)
        assertEquals("C12", info.gate)
        assertEquals("Flight UA1234 EWR → SFO", flightTitle(info))
        assertNull(parseFlight("[]", "2037-11-13"))
        assertNull(flightTime("not a time"))
    }

    // The card's status line says how late it is, when it now departs, and where
    @Test
    fun statusLines() {
        val info = parseFlight(reply, "2037-11-13")!!
        assertEquals(25, minutesLate(info))
        assertEquals("Delayed 25 min · departs 7:10 PM · Terminal C, gate C12", statusLine(info))
        assertEquals("Cancelled", statusLine(info.copy(status = "Canceled")))
        assertEquals("On time · departs 6:45 PM", statusLine(info.copy(departBest = info.departScheduled, terminal = "",
            gate = "")))
    }

    // Alerts go out for a cancellation, a new departure time or a new gate, and not for small changes
    @Test
    fun changes() {
        val before = parseFlight(reply, "2037-11-13")!!.copy(departBest = Instant.parse("2037-11-13T23:45:00Z"))
        val later = before.copy(departBest = Instant.parse("2037-11-14T00:10:00Z"))
        assertEquals("UA1234 now departs 7:10 PM (25 min late)", flightChange(before, later))
        assertEquals("UA1234 is cancelled", flightChange(before, before.copy(status = "Canceled")))
        assertEquals("UA1234 now at gate C14", flightChange(before, before.copy(gate = "C14")))
        val twoMinutes = before.copy(departBest = Instant.parse("2037-11-13T23:47:00Z"))
        assertNull(flightChange(before, twoMinutes))
    }

    // Checks happen the day before, 3 h, 1 h and 30 min before departure, and at landing
    @Test
    fun checkSchedule() {
        val info = parseFlight(reply, "2037-11-13")!!
        val departs = Instant.parse("2037-11-13T23:45:00Z").toEpochMilli()
        val hour = 3_600_000L
        assertEquals(listOf(departs - 24 * hour, departs - 3 * hour, departs - hour, departs - hour / 2,
            Instant.parse("2037-11-14T05:58:00Z").toEpochMilli()), checkTimes(info))
    }

    // Flight plans are recognised by their title, so they get no leave-by alert
    @Test
    fun flightTitles() {
        assertTrue(isFlightTitle("Flight UA1234 EWR → SFO"))
        assertTrue(isFlightTitle("Flight B6123"))
        assertFalse(isFlightTitle("Flight lessons"))
    }

    // A flight's details and its done checks are remembered between runs
    @Test
    fun store() {
        val store = FlightStore(ApplicationProvider.getApplicationContext())
        val info = parseFlight(reply, "2037-11-13")!!
        store.save("2037-11-13T18:45", info)
        assertEquals(info, store.info("UA1234", "2037-11-13"))
        assertFalse(store.checked("UA1234", "2037-11-13", 5))
        store.setChecked("UA1234", "2037-11-13", 5)
        assertTrue(store.checked("UA1234", "2037-11-13", 5))
    }
}

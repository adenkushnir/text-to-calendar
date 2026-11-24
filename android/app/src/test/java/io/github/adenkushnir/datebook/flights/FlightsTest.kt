// Tests for live flights: reading AeroDataBox's reply, the status line, spotting changes, when checks happen, what's
// remembered between checks, how early to be at the airport, filling a plan in, and the wall's line
package io.github.adenkushnir.datebook.flights
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.pipeline.Event
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
            "countryCode": "US", "timeZone": "America/New_York"}, "scheduledTime": {"utc": "2037-11-13 23:45Z",
            "local": "2037-11-13 18:45-05:00"}, "revisedTime": {"utc": "2037-11-14 00:10Z",
            "local": "2037-11-13 19:10-05:00"}, "terminal": "C", "gate": "C12", "quality": ["Basic", "Live"]},
        "arrival": {"airport": {"iata": "SFO", "name": "San Francisco International",
            "municipalityName": "San Francisco", "countryCode": "US", "timeZone": "America/Los_Angeles"},
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
        assertEquals("US", info.fromCountry)
        assertEquals("US", info.toCountry)
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

    // Flight plans are recognised by their title, which also gives the flight number
    @Test
    fun flightTitles() {
        assertTrue(isFlightTitle("Flight UA1234 EWR → SFO"))
        assertTrue(isFlightTitle("Flight B6123"))
        assertFalse(isFlightTitle("Flight lessons"))
        assertEquals("B61234", flightOfTitle("Flight B61234 EWR → BOS"))
        assertNull(flightOfTitle("Flight lessons"))
    }

    // Be at the airport an hour early, or two when leaving the country (an unknown country counts as at home)
    @Test
    fun earlyDefaults() {
        val info = parseFlight(reply, "2037-11-13")!!
        assertFalse(isInternational(info))
        assertEquals(60, flightEarlyMinutes(info))
        assertEquals(120, flightEarlyMinutes(info.copy(toCountry = "GB")))
        assertEquals(60, flightEarlyMinutes(info.copy(toCountry = "")))
        assertEquals(60, flightEarlyMinutes(null))
        assertEquals("Newark Airport", airportName(info))
        assertEquals("Newark Liberty International", airportName(info.copy(fromCity = "")))
    }

    // A plan is filled in from live data in the departure airport's time; without data it stays as written
    @Test
    fun fillsPlan() {
        val info = parseFlight(reply, "2037-11-13")!!
        val written = Event("Flight UA1234", "2037-11-13", allDay = true, flight = "UA1234")
        val filled = withFlight(written, info)
        assertEquals("Flight UA1234 EWR → SFO", filled.title)
        assertEquals("2037-11-13T19:10", filled.start)
        assertEquals("2037-11-14T00:58", filled.end)  // landing, in the departure airport's time
        assertEquals("America/New_York", filled.timezone)
        assertEquals("Newark Liberty International", filled.location)
        assertFalse(filled.allDay)
        assertEquals(written, withFlight(written, null))
    }

    // The wall gets the status line, orange when it's late or cancelled
    @Test
    fun wallLine() {
        val info = parseFlight(reply, "2037-11-13")!!
        assertEquals(mapOf("text" to "Delayed 25 min · departs 7:10 PM · Terminal C, gate C12", "late" to true),
            wallFlight(info))
        assertEquals(false, wallFlight(info.copy(departBest = info.departScheduled))["late"])
        assertEquals(true, wallFlight(info.copy(status = "Canceled"))["late"])
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

    // One number flying two legs that day: both are read, and the leg meant is picked by where it lands
    @Test
    fun twoLegs() {
        val legs = """[{"number": "B6 1234", "status": "Expected",
            "departure": {"airport": {"iata": "FLL", "municipalityName": "Fort Lauderdale", "countryCode": "US",
                "timeZone": "America/New_York"}, "scheduledTime": {"utc": "2037-11-25 11:00Z",
                "local": "2037-11-25 06:00-05:00"}},
            "arrival": {"airport": {"iata": "EWR", "municipalityName": "Newark", "countryCode": "US"},
                "scheduledTime": {"utc": "2037-11-25 14:00Z"}}},
            {"number": "B6 1234", "status": "Expected",
            "departure": {"airport": {"iata": "EWR", "municipalityName": "Newark", "countryCode": "US",
                "timeZone": "America/New_York"}, "scheduledTime": {"utc": "2037-11-25 22:40Z",
                "local": "2037-11-25 17:40-05:00"}},
            "arrival": {"airport": {"iata": "BOS", "municipalityName": "Boston", "countryCode": "US"},
                "scheduledTime": {"utc": "2037-11-25 23:55Z"}}}]"""
        assertEquals(listOf("EWR", "BOS"), parseFlights(legs, "2037-11-25").map { it.toCode })
        assertEquals("BOS", parseFlight(legs, "2037-11-25", "BOS")!!.toCode)
        assertEquals("BOS", parseFlight(legs, "2037-11-25", "Boston")!!.toCode)
        assertEquals("BOS", parseFlight(legs, "2037-11-25", "EWR-BOS")!!.toCode)
        assertEquals("EWR", parseFlight(legs, "2037-11-25")!!.toCode)
        assertEquals("EWR", parseFlight(legs, "2037-11-25", "Chicago")!!.toCode)
    }

    // The leg a plan is stays remembered, so its checks look up that leg
    @Test
    fun remembersLeg() {
        val store = FlightStore(ApplicationProvider.getApplicationContext())
        store.save("2037-11-13", parseFlight(reply, "2037-11-13")!!)
        assertEquals("SFO", store.leg("UA1234", "2037-11-13"))
        store.setLeg("B61234", "2037-11-25", "BOS")
        assertEquals("BOS", store.leg("B61234", "2037-11-25"))
    }
}

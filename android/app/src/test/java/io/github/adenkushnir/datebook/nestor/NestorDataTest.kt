// Tests for what Datebook sends Nestor: which alerts are spoken and how, the snapshot's JSON, and home addresses
package io.github.adenkushnir.datebook.nestor
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.fencing.FencingTile
import io.github.adenkushnir.datebook.fencing.Tournament
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.trains.TrainPick
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the alerts and the snapshot with a made-up Friday, Nov 20 2037, in New York (on Robolectric, since track
// links use Android's Uri)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NestorDataTest {
    private val zone = ZoneId.of("America/New_York")
    private val sixPm = ZonedDateTime.of(2037, 11, 20, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

    // A follow-up with the fields the snapshot reads
    private fun item(id: Long, kind: String, title: String, date: String = "", time: String = "",
        nextNudge: Long = 0, body: String = ""): FollowUp {
        return FollowUp(id = id, kind = kind, title = title, date = date, amount = "", sender = "you", app = "",
            conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = nextNudge, nudges = 0, time = time,
            body = body)
    }

    // Only leave-by, travel changes, follow-ups and evening heads-ups are spoken; their words, keys and expiry
    @Test
    fun alerts() {
        assertTrue(isSpoken("leave_by"))
        assertTrue(isSpoken("evening"))
        assertFalse(isSpoken("summary"))
        assertFalse(isSpoken("suggestions"))
        assertFalse(isSpoken(null))
        assertEquals("Leave by 6:37 PM. Dinner · 20 min drive", alertText("Leave by 6:37 PM", "Dinner · 20 min drive"))
        assertEquals("What to bring tomorrow. Soccer at 6:00 PM: cleats. Lab: goggles",
            alertText("What to bring tomorrow", "Soccer at 6:00 PM: cleats", listOf("Soccer at 6:00 PM: cleats",
                "Lab: goggles")))
        assertEquals("2037-11-20T18:20:00", alertExpires("leave_by", sixPm, zone))
        assertEquals("2037-11-21T06:00:00", alertExpires("follow_ups", sixPm, zone))
        assertEquals(alertKey("leave_by", 5, "a"), alertKey("leave_by", 5, "a"))
        assertFalse(alertKey("leave_by", 5, "a") == alertKey("leave_by", 5, "b"))
    }

    // Who an event came from is read from Datebook's notes line, and only from it
    @Test
    fun fromNotes() {
        assertEquals("Jake", fromOfNotes("Jake · Soccer Team (GroupMe)\nBring: cleats"))
        assertEquals("Sam", fromOfNotes("Sam (Messages)"))
        assertEquals("Canvas", fromOfNotes("Canvas · PHYS-101"))
        assertEquals("", fromOfNotes("You → Sam (Messages)"))
        assertEquals("", fromOfNotes("Bring the projector"))
    }

    // The snapshot carries each part in the shape Nestor's README shows
    @Test
    fun snapshot() {
        val dinner = Suggestion(id = 12, sender = "Jake", app = "com.google.android.apps.messaging", title = "Dinner",
            start = "2037-11-20T19:00", end = "", allDay = false, location = "Luigi's", repeat = "", timezone = "",
            phoneTimezone = zone.id, createdAt = 0)
        val box = dinner.copy(id = 6, title = "Cleats", start = "2037-11-19", allDay = true, packageCarrier = "ups",
            trackingNumber = "1Z999AA10123456784")
        val soccer = CalendarEvent(88, 1, "Soccer", sixPm + 3_600_000, sixPm + 9_000_000, false, "Field 3",
            notes = "Jake · Soccer Team (GroupMe)\nBring: cleats, \$20\nConfirmation: AB12")
        val train = TrainPick("3847", "NEC", "New Brunswick", "New York Penn", sixPm - 600_000, sixPm + 2_400_000, "3",
            0, "")
        val nac = Tournament("Junior Men's Foil", "November NAC", "Columbus", LocalDate.of(2037, 11, 20),
            LocalDate.of(2037, 11, 22), startTime = "08:00", todayDate = "2037-11-20", todayNote = "Table of 16")
        val over = Tournament("Men's Foil World Cup", "", "Turin", LocalDate.of(2037, 11, 17), LocalDate.of(2037, 11, 18))
        val parts = SnapshotParts(
            waiting = listOf(dinner),
            packages = listOf(box),
            bills = listOf(item(4, KIND_DUE, "Electric bill", "2037-11-20")),
            assignments = listOf(item(5, KIND_ASSIGNMENT, "CS essay", "2037-11-21", "23:59")),
            notes = listOf(item(9, "note", "Gate code", body = "Gate code 4412")),
            reminders = listOf(item(7, KIND_REMINDER, "Call the dentist", nextNudge = sixPm)),
            trip = mapOf("from" to "NB", "to" to "NY", "from_name" to "New Brunswick", "to_name" to "New York Penn"),
            events = listOf(SnapshotEvent(soccer, "Personal", PlannedLeave(sixPm + 1_500_000, "20 min drive"), train)),
            fencing = listOf(FencingTile(nac, LocalDate.of(2037, 11, 20), true),
                FencingTile(over, LocalDate.of(2037, 11, 18), false, finished = true)),
        )
        val json = snapshotOf(parts, zone)
        val waiting = (json["waiting"] as List<*>).single() as Map<*, *>
        assertEquals("Jake", waiting["from"])
        assertEquals("2037-11-20T19:00", waiting["start"])
        val lists = json["lists"] as Map<*, *>
        val pack = (lists["packages"] as List<*>).single() as Map<*, *>
        assertEquals("UPS", pack["carrier"])
        assertTrue(pack["track_url"].toString().startsWith("https://www.ups.com/"))
        assertEquals("2037-11-19", pack["expected"])
        assertEquals("2037-11-21T23:59", ((lists["assignments"] as List<*>).single() as Map<*, *>)["due"])
        assertEquals("Gate code 4412", ((json["notes"] as List<*>).single() as Map<*, *>)["text"])
        assertEquals("2037-11-20T18:00", ((json["reminders"] as List<*>).single() as Map<*, *>)["at"])
        assertEquals("NB", (json["trip"] as Map<*, *>)["from"])
        val event = (json["events"] as List<*>).single() as Map<*, *>
        assertEquals("88-${(sixPm + 3_600_000) / 1000}", event["id"])
        assertEquals("2037-11-20T19:00", event["start"])
        assertEquals("Jake", event["from"])
        assertEquals("cleats, \$20", event["bring"])
        assertEquals("AB12", event["confirmation"])
        assertEquals("2037-11-20T18:25", event["leave_by"])
        assertEquals("20 min drive", event["travel"])
        assertEquals("17:50", (event["train"] as Map<*, *>)["departs"])
        assertEquals("New Brunswick", (event["train"] as Map<*, *>)["from"])
        val foil = (json["fencing"] as List<*>).single() as Map<*, *>
        assertEquals("Junior Men's Foil", foil["name"])
        assertEquals("November NAC · Columbus", foil["where"])
        assertEquals("2037-11-20T08:00", foil["start"])
        assertEquals("2037-11-22", foil["end"])
        assertEquals(true, foil["live"])
        assertEquals("Table of 16", foil["stage"])
        assertEquals("2037-11-20T08:00", foil["live_from"])
        assertEquals("2037-11-20T16:00", foil["live_until"])
        assertFalse(snapshotOf(SnapshotParts(), zone).containsKey("trip"))
        assertFalse(event.containsKey("flight"))
    }

    // A flight carries its status line for the wall, orange when it's late
    @Test
    fun flightLine() {
        val flight = CalendarEvent(90, 1, "Flight B61234 EWR → BOS", sixPm, sixPm + 4_620_000, false,
            "Newark Liberty International")
        val info = FlightInfo("B61234", "Expected", "EWR", "Newark", "Newark Liberty International", zone.id, "BOS",
            "Boston", Instant.ofEpochMilli(sixPm), Instant.ofEpochMilli(sixPm + 2_400_000), null, null, "A", "")
        val json = snapshotOf(SnapshotParts(events = listOf(SnapshotEvent(flight, "Personal", null, flight = info))),
            zone)
        val event = (json["events"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("text" to "Delayed 40 min · departs 6:40 PM · Terminal A", "late" to true),
            event["flight"])
    }

    // Only home-network addresses are used
    @Test
    fun homeAddresses() {
        assertTrue(isHomeAddress(InetAddress.getByName("192.168.1.20")))
        assertTrue(isHomeAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(isHomeAddress(InetAddress.getByName("fd12::1")))
        assertFalse(isHomeAddress(InetAddress.getByName("8.8.8.8")))
    }
}

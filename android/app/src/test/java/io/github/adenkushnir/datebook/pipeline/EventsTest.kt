// Tests for event times, repeats, time zones and summaries, with the same cases as the Python tests
package io.github.adenkushnir.datebook.pipeline
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the Kotlin port of events.py against the Python version's expected results
class EventsTest {
    private val newYork = "America/New_York"
    private val dinner = Event("Dinner at Joe's", "2026-11-13T19:00", location = "Joe's", phoneTimezone = newYork)
    private val dentist = Event("Dentist", "2026-11-10T15:30", end = "2026-11-10T16:30", phoneTimezone = newYork)
    private val party = Event("Party", "2026-11-14", allDay = true, phoneTimezone = newYork)
    private val hour = 60L * 60 * 1000

    // Build the milliseconds for a clock time in a zone, to compare against
    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int, minute: Int, zone: String): Long {
        return ZonedDateTime.of(year, month, day, hourOfDay, minute, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()
    }

    // Times are read in the zone they are written in
    @Test
    fun toMillisUsesTheZone() {
        assertEquals(millis(2026, 11, 13, 19, 0, newYork), toMillis("2026-11-13T19:00", ZoneId.of(newYork)))
    }

    // A given end time is used as is
    @Test
    fun eventTimesWithEnd() {
        val (begin, end) = eventTimes(dentist)
        assertEquals(hour, end - begin)
    }

    // With no end time, a timed event lasts one hour and an all-day event one day
    @Test
    fun eventTimesDefaults() {
        val (dinnerBegin, dinnerEnd) = eventTimes(dinner)
        assertEquals(hour, dinnerEnd - dinnerBegin)
        val (partyBegin, partyEnd) = eventTimes(party)
        assertEquals(24 * hour, partyEnd - partyBegin)
    }

    // An end before the start is ignored
    @Test
    fun eventTimesEndBeforeStart() {
        val (begin, end) = eventTimes(dentist.copy(end = "2026-11-10T09:00"))
        assertEquals(hour, end - begin)
    }

    // The summary shows title, day, time and location
    @Test
    fun summaries() {
        assertEquals("Dinner at Joe's · Fri Nov 13, 7:00 PM · Joe's", summary(dinner))
        assertEquals("Dentist · Tue Nov 10, 3:30 PM", summary(dentist))
        assertEquals("Party · Sat Nov 14", summary(party))
    }

    // A valid repeat rule shows in the summary
    @Test
    fun repeatInSummary() {
        val standup = Event("Standup", "2026-11-16T09:00", repeat = "FREQ=WEEKLY;BYDAY=MO", phoneTimezone = newYork)
        assertEquals("Standup · Mon Nov 16, 9:00 AM · repeats weekly", summary(standup))
    }

    // Repeat rules are cleaned up, and anything that isn't a rule is dropped
    @Test
    fun repeatRuleCleanup() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1", repeatRule(dinner.copy(repeat = "rrule:freq=monthly;bymonthday=1")))
        assertEquals("", repeatRule(dinner.copy(repeat = "every monday")))
        assertEquals("", repeatRule(dinner))
    }

    // Repeat labels read naturally
    @Test
    fun repeatLabels() {
        assertEquals("repeats daily", repeatLabel("FREQ=DAILY"))
        assertEquals("repeats monthly", repeatLabel("FREQ=MONTHLY;BYMONTHDAY=1"))
        assertEquals("repeats every 2 weeks", repeatLabel("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR"))
    }

    // One-off events in the past count as started, future ones don't, and an all-day event counts for its whole day
    @Test
    fun startedEvents() {
        val dinnerStart = toMillis(dinner.start, ZoneId.of(newYork))
        assertFalse(hasStarted(dinner, dinnerStart - 1))
        assertTrue(hasStarted(dinner, dinnerStart))
        val partyStart = toMillis(party.start, ZoneId.of(newYork))
        assertFalse(hasStarted(party, partyStart + 12 * hour))
        assertTrue(hasStarted(party, partyStart + 24 * hour))
    }

    // Repeating events and unreadable starts are never treated as started
    @Test
    fun repeatingOrUnreadableNotStarted() {
        val weekly = dinner.copy(repeat = "FREQ=WEEKLY;BYDAY=FR")
        assertFalse(hasStarted(weekly, toMillis(dinner.start, ZoneId.of(newYork)) + 100 * 24 * hour))
        assertFalse(hasStarted(dinner.copy(start = "someday"), 0))
    }

    // A zone named in the text is converted to the phone's time, with the original shown in brackets
    @Test
    fun zoneInText() {
        val call = Event("Call", "2026-11-10T15:00", timezone = "America/Los_Angeles", phoneTimezone = newYork)
        assertEquals(millis(2026, 11, 10, 23, 0, "UTC"), eventTimes(call).first)
        assertEquals("Call · Tue Nov 10, 6:00 PM (3:00 PM PST)", summary(call))
    }

    // When travelling, a plain time means the phone's current zone, with no brackets
    @Test
    fun travellingUsesPhoneZone() {
        val london = dinner.copy(phoneTimezone = "Europe/London")
        assertEquals(millis(2026, 11, 13, 19, 0, "UTC"), eventTimes(london).first)
        assertEquals("Dinner at Joe's · Fri Nov 13, 7:00 PM · Joe's", summary(london))
    }

    // A flight landing in another zone shows in the phone's time, even when the date changes
    @Test
    fun flightArrivalZone() {
        val flight = Event("Pick up Mom", "2026-12-18T06:40", location = "LAX", timezone = "America/Los_Angeles",
            phoneTimezone = newYork)
        assertEquals("Pick up Mom · Fri Dec 18, 9:40 AM (6:40 AM PST) · LAX", summary(flight))
        val late = flight.copy(start = "2026-12-18T22:30")
        assertTrue(summary(late).startsWith("Pick up Mom · Sat Dec 19, 1:30 AM (10:30 PM PST)"))
    }

    // Unknown zone names fall back to the phone's zone, and all-day events ignore the text's zone
    @Test
    fun zoneFallbacks() {
        assertNull(zoneOf("Mars/Base"))
        assertNull(zoneOf(""))
        val odd = dinner.copy(timezone = "Mars/Base")
        assertEquals(ZoneId.of(newYork), eventZone(odd))
        val tokyoParty = party.copy(timezone = "Asia/Tokyo")
        assertEquals(ZoneId.of(newYork), eventZone(tokyoParty))
    }

    // A birthday is a yearly all-day event, and the summary says so
    @Test
    fun birthday() {
        val birthday = Event("Sam's birthday", "2027-03-03", allDay = true, repeat = "FREQ=YEARLY",
            phoneTimezone = newYork)
        assertEquals("Sam's birthday · Wed Mar 3 · repeats yearly", summary(birthday))
        assertFalse(hasStarted(birthday, toMillis("2030-01-01", ZoneId.of(newYork))))
    }
}

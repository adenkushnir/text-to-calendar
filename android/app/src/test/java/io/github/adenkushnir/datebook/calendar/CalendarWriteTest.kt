// Tests for writing to the calendar: what a plan becomes as an event (times, all-day days, zone, notes, repeat) and
// the values stored for it
package io.github.adenkushnir.datebook.calendar
import android.provider.CalendarContract
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.DELIBERATE_APP
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks a plan's event fields and stored values
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarWriteTest {
    private val flight = Suggestion(sender = "you", app = DELIBERATE_APP, title = "Flight B61234 EWR → BOS",
        start = "2037-11-25T17:40", end = "2037-11-25T18:55", allDay = false, location = "Newark Liberty International",
        repeat = "", timezone = "America/New_York", phoneTimezone = "America/New_York", createdAt = 0,
        flight = "B61234")

    // A timed plan keeps its times in its own zone, its place, and notes saying where it came from with a Track link
    @Test
    fun timedPlan() {
        val fields = planFields(flight)
        assertEquals("Flight B61234 EWR → BOS", fields.title)
        assertEquals(Instant.parse("2037-11-25T22:40:00Z").toEpochMilli(), fields.begin)
        assertEquals(Instant.parse("2037-11-25T23:55:00Z").toEpochMilli(), fields.end)
        assertFalse(fields.allDay)
        assertEquals("America/New_York", fields.timezone)
        assertTrue(fields.notes.startsWith("Made with Deliberate"))
        assertTrue(fields.notes.contains("Track: "))
    }

    // An all-day plan runs midnight to midnight UTC, as calendars keep them
    @Test
    fun allDayPlan() {
        val fields = planFields(flight.copy(title = "Boston trip", start = "2037-11-25", end = "", allDay = true,
            flight = ""))
        assertEquals(Instant.parse("2037-11-25T00:00:00Z").toEpochMilli(), fields.begin)
        assertEquals(Instant.parse("2037-11-26T00:00:00Z").toEpochMilli(), fields.end)
        assertEquals("UTC", fields.timezone)
    }

    // A one-off event stores its end; a repeating one stores its rule and length instead
    @Test
    fun storedValues() {
        val once = eventValues(planFields(flight))
        assertEquals(planFields(flight).end, once.getAsLong(CalendarContract.Events.DTEND))
        assertNull(once.getAsString(CalendarContract.Events.RRULE))
        val weekly = eventValues(planFields(flight.copy(title = "Practice", repeat = "FREQ=WEEKLY", flight = "")))
        assertEquals("FREQ=WEEKLY", weekly.getAsString(CalendarContract.Events.RRULE))
        assertEquals("P4500S", weekly.getAsString(CalendarContract.Events.DURATION))
        assertNull(weekly.getAsLong(CalendarContract.Events.DTEND))
    }
}

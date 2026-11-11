// Tests for working out Next up and Coming up from added suggestions
package io.github.adenkushnir.datebook
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

// Checks which added plans count as ahead, their order, and their countdown words
class UpcomingTest {
    private val newYork = "America/New_York"
    private val now = ZonedDateTime.of(2037, 11, 10, 14, 0, 0, 0, ZoneId.of(newYork)).toInstant().toEpochMilli()

    // Build an added suggestion with a title and start
    private fun added(title: String, start: String, allDay: Boolean = false): Suggestion {
        return Suggestion(sender = "Sam", app = "sms", title = title, start = start, end = "", allDay = allDay,
            location = "", repeat = "", timezone = "", phoneTimezone = newYork, status = Suggestion.STATUS_ADDED,
            createdAt = 0)
    }

    // Plans that already started are dropped, and the rest are soonest first, all-day ones counted from midnight
    @Test
    fun aheadSoonestFirst() {
        val plans = listOf(added("Movie", "2037-11-14T22:00"), added("Lunch", "2037-11-10T12:00"),
            added("Dinner", "2037-11-13T19:00"), added("Party", "2037-11-10", allDay = true))
        assertEquals(listOf("Party", "Dinner", "Movie"), upcoming(plans, now).map { it.title })
    }

    // The countdown counts calendar days in the phone's zone
    @Test
    fun countdownWords() {
        assertEquals("today", countdownFor(added("Late call", "2037-11-10T23:30"), now))
        assertEquals("tomorrow", countdownFor(added("Dentist", "2037-11-11T08:00"), now))
        assertEquals("in 3 days", countdownFor(added("Dinner", "2037-11-13T19:00"), now))
    }
}

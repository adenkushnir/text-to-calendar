// Tests for comparing a plan with the calendar: clashes, plans already added, similar titles and the warning text
package io.github.adenkushnir.datebook.calendar
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.toMillis
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks which calendar events clash with or match a suggested plan
class CalendarCheckTest {
    private val newYork = "America/New_York"
    private val zone = ZoneId.of(newYork)
    private val dinner = Event("Dinner with Sam", "2037-11-13T19:00", phoneTimezone = newYork)
    private val practice = timed("Practice", "2037-11-13T18:00", "2037-11-13T20:00")
    private val holiday = CalendarEvent(9, 1, "Day off", midnightUtc("2037-11-13"), midnightUtc("2037-11-14"), true)

    // A timed calendar event from start to end in New York time
    private fun timed(title: String, start: String, end: String): CalendarEvent {
        return CalendarEvent(1, 1, title, toMillis(start, zone), toMillis(end, zone), false)
    }

    // Midnight UTC on a date, where Android keeps all-day events
    private fun midnightUtc(date: String): Long {
        return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    // Overlapping timed events clash; all-day events and events that end before the plan don't
    @Test
    fun clashes() {
        val earlier = timed("Lunch", "2037-11-13T12:00", "2037-11-13T13:00")
        assertEquals(listOf(practice), clashesFor(dinner, listOf(practice, holiday, earlier)))
        val birthday = Event("Sam's birthday", "2037-11-13", allDay = true, phoneTimezone = newYork)
        assertTrue(clashesFor(birthday, listOf(practice)).isEmpty())
    }

    // The warning names the first clash with its times, and counts the rest
    @Test
    fun warningText() {
        assertEquals("You already have Practice 6:00 PM–8:00 PM", clashText(listOf(practice), newYork))
        assertEquals("You already have Practice 6:00 PM–8:00 PM and 1 more",
            clashText(listOf(practice, practice), newYork))
        assertEquals("", clashText(emptyList(), newYork))
    }

    // A plan at the same time with a similar title is already on the calendar
    @Test
    fun alreadyAdded() {
        val onCalendar = timed("Dinner at Luigi's", "2037-11-13T19:00", "2037-11-13T21:00")
        assertTrue(alreadyOnCalendar(dinner, listOf(onCalendar)))
        assertFalse(alreadyOnCalendar(dinner, listOf(practice)))
        val later = timed("Dinner", "2037-11-13T20:00", "2037-11-13T21:00")
        assertFalse(alreadyOnCalendar(dinner, listOf(later)))
    }

    // An all-day plan matches an all-day event on the same date
    @Test
    fun birthdayAlreadyAdded() {
        val birthday = Event("Sam's birthday", "2037-11-13", allDay = true, phoneTimezone = newYork)
        val onCalendar = CalendarEvent(5, 2, "Sam Lee's Birthday", midnightUtc("2037-11-13"),
            midnightUtc("2037-11-14"), true)
        assertTrue(alreadyOnCalendar(birthday, listOf(onCalendar)))
        assertFalse(alreadyOnCalendar(birthday.copy(start = "2037-11-14"), listOf(onCalendar)))
    }

    // Titles are similar when they share a real word, ignoring case, "'s" and small words
    @Test
    fun titles() {
        assertTrue(similarTitles("Soccer scrimmage", "Scrimmage at the park"))
        assertTrue(similarTitles("Jake's birthday", "JAKE"))
        assertFalse(similarTitles("Dinner with Sam", "Practice"))
        assertFalse(similarTitles("Mom's birthday", "Sam's birthday"))
    }
}

// Tests for Home's view of the calendar: what's Next up or on now, the next 3 days with events, all-day events,
// matching Datebook plans to their calendar events, the fallback without calendar access, and an event's card
package io.github.adenkushnir.datebook.calendar
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the agenda from a fixed Tuesday afternoon, Nov 10 2037 at 2:00 PM in New York
class CalendarHomeTest {
    private val newYork = ZoneId.of("America/New_York")
    private val now = millis(LocalDateTime.of(2037, 11, 10, 14, 0))
    private val hour = 60L * 60 * 1000

    // Milliseconds for a time in New York
    private fun millis(time: LocalDateTime): Long {
        return time.atZone(newYork).toInstant().toEpochMilli()
    }

    // A timed calendar event starting at a time and lasting some hours
    private fun timed(title: String, start: LocalDateTime, hours: Long = 1, id: Long = 1): CalendarEvent {
        val begin = millis(start)
        return CalendarEvent(id, 1, title, begin, begin + hours * hour, false, "Joe's", 0xFF3366CC.toInt())
    }

    // An all-day calendar event on a date, kept at midnight UTC like the calendar does
    private fun allDay(title: String, year: Int, month: Int, day: Int): CalendarEvent {
        val begin = LocalDateTime.of(year, month, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return CalendarEvent(2, 1, title, begin, begin + 24 * hour, true)
    }

    // A plan added through Datebook
    private fun added(title: String, start: String): Suggestion {
        return Suggestion(id = 9, sender = "Sam", app = "sms", title = title, start = start, end = "", allDay = false,
            location = "Joe's", repeat = "", timezone = "", phoneTimezone = "America/New_York",
            status = Suggestion.STATUS_ADDED, createdAt = 0, flight = "", placeLat = 40.7)
    }

    // While class is on, Next up is "now" with dinner after it; Coming up lists the next 3 days with events, Next up's
    // included, all-day events first; finished events and a 4th day are left out
    @Test
    fun nowThenAndTheWeek() {
        val events = fromCalendar(listOf(
            timed("Breakfast", LocalDateTime.of(2037, 11, 10, 9, 0)),
            timed("Class", LocalDateTime.of(2037, 11, 10, 13, 0), hours = 2),
            timed("Dinner at Joe's", LocalDateTime.of(2037, 11, 10, 19, 0)),
            timed("Late show", LocalDateTime.of(2037, 11, 10, 22, 0)),
            allDay("Mom's birthday", 2037, 11, 11),
            timed("Movie", LocalDateTime.of(2037, 11, 12, 22, 0)),
            timed("Far away", LocalDateTime.of(2037, 11, 13, 12, 0)),
        ), emptyList())
        val agenda = agendaOf(events, now, newYork)
        assertEquals("Class", agenda.nextUp!!.event.title)
        assertTrue(agenda.nextUp!!.happening)
        assertEquals("Dinner at Joe's", agenda.nextUp!!.then!!.title)
        assertEquals("3:00 PM", endText(agenda.nextUp!!.event, newYork))
        assertEquals(listOf("Today", "Tomorrow", "Thu Nov 12"), agenda.days.map { it.label })
        assertEquals(listOf("Class", "Dinner at Joe's", "Late show"), agenda.days[0].events.map { it.title })
        assertEquals("All day", timeText(agenda.days[1].events.single(), newYork))
        assertEquals("10:00 PM", timeText(agenda.days[2].events.single(), newYork))
    }

    // With nothing on now, Next up is the next timed event with no "then"; all-day events never take Next up
    @Test
    fun nextAndAllDay() {
        val later = agendaOf(fromCalendar(listOf(timed("Dinner", LocalDateTime.of(2037, 11, 10, 19, 0)),
            allDay("Holiday", 2037, 11, 10)), emptyList()), now, newYork)
        assertFalse(later.nextUp!!.happening)
        assertNull(later.nextUp!!.then)
        assertEquals("Dinner", later.nextUp!!.event.title)
        assertEquals("Today", later.days.single().label)
        val onlyAllDay = agendaOf(fromCalendar(listOf(allDay("Holiday", 2037, 11, 11)), emptyList()), now, newYork)
        assertNull(onlyAllDay.nextUp)
        assertEquals("Tomorrow", onlyAllDay.days.single().label)
        assertEquals("Fri Nov 13, 7:00 PM · Joe's", whenWhere(HomeEvent("Dinner",
            millis(LocalDateTime.of(2037, 11, 13, 19, 0)), 0, false, "Joe's"), newYork))
    }

    // Days with nothing on them are skipped, so Coming up reaches as far as the third day with something on it
    @Test
    fun emptyDaysSkipped() {
        val agenda = agendaOf(fromCalendar(listOf(
            timed("Dinner", LocalDateTime.of(2037, 11, 10, 19, 0)),
            timed("Movie", LocalDateTime.of(2037, 11, 15, 20, 0)),
            timed("Lunch", LocalDateTime.of(2037, 11, 16, 12, 0)),
            timed("Trip", LocalDateTime.of(2037, 11, 30, 8, 0)),
        ), emptyList()), now, newYork)
        assertEquals(listOf("Today", "Sun Nov 15", "Mon Nov 16"), agenda.days.map { it.label })
    }

    // A plan added through Datebook matches its calendar event even after it moved 30 minutes, so it shows once with
    // who it came from; a same-named event days later doesn't match
    @Test
    fun plansMatchTheirEvents() {
        val plan = added("Dinner at Joe's", "2037-11-10T19:00")
        val events = fromCalendar(listOf(timed("dinner at joe's", LocalDateTime.of(2037, 11, 10, 19, 30), id = 5),
            timed("Dinner at Joe's", LocalDateTime.of(2037, 11, 14, 19, 0), id = 6)), listOf(plan))
        assertEquals(plan, events[0].source)
        assertNull(events[1].source)
        assertEquals(5L, events[0].eventId)
    }

    // Without calendar access, Home lists the plans added through Datebook, linked to themselves
    @Test
    fun fallbackToPlans() {
        val events = fromPlans(listOf(added("Dinner at Joe's", "2037-11-10T19:00")))
        assertEquals(0L, events.single().eventId)
        assertEquals("Sam", events.single().source!!.sender)
        assertEquals("Dinner at Joe's", agendaOf(events, now, newYork).nextUp!!.event.title)
    }

    // An event's card uses the calendar's time and place, keeps the Datebook plan's extras, and is never waiting
    @Test
    fun eventCards() {
        val plan = added("Dinner at Joe's", "2037-11-10T19:00")
        val moved = fromCalendar(listOf(timed("Dinner at Joe's", LocalDateTime.of(2037, 11, 10, 19, 30), id = 5)),
            listOf(plan)).single()
        val card = planOf(moved, newYork)
        assertEquals("2037-11-10T19:30", card.start)
        assertEquals("Sam", card.sender)
        assertEquals(40.7, card.placeLat)
        assertEquals(Suggestion.STATUS_CALENDAR, card.status)
        assertEquals(5L, card.calendarEventId)
        val plain = planOf(fromCalendar(listOf(allDay("Holiday", 2037, 11, 11)), emptyList()).single(), newYork)
        assertEquals("", plain.sender)
        assertEquals("2037-11-11", plain.start)
        assertTrue(plain.allDay)
    }
}

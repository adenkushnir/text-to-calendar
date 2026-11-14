// Tests for the follow-up alerts: when a bill's next reminder comes, when "Still on?" is due, when the Friday week
// ahead is due, and what the week ahead says
package io.github.adenkushnir.datebook.followups
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the alerts around Friday Nov 13 2037 in New York
class FollowUpAlertsTest {
    private val newYork = ZoneId.of("America/New_York")
    private val bill = FollowUp(kind = KIND_DUE, title = "Phone bill", date = "2037-11-20", amount = "$45",
        sender = "Verizon", app = "sms", conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0,
        nudges = 0)

    // Milliseconds for a time in New York
    private fun millis(time: LocalDateTime): Long {
        return time.atZone(newYork).toInstant().toEpochMilli()
    }

    // A bill reminded 2 days early gets one more on its day; after that, and for loose plans, there are no more
    @Test
    fun billsGetTwoReminders() {
        val twoDaysBefore = millis(LocalDateTime.of(2037, 11, 18, 9, 0))
        assertEquals(millis(LocalDateTime.of(2037, 11, 20, 9, 0)), nextAfterReminder(bill, twoDaysBefore, newYork))
        assertEquals(0L, nextAfterReminder(bill, millis(LocalDateTime.of(2037, 11, 20, 9, 0)), newYork))
        assertEquals(0L, nextAfterReminder(bill.copy(kind = KIND_LOOSE), twoDaysBefore, newYork))
    }

    // "Still on?" comes from 6 PM the day before a waiting new plan, not earlier, not for changes or added plans
    @Test
    fun stillOnTiming() {
        val plan = Suggestion(sender = "Sam", app = "sms", title = "Dinner", start = "2037-11-14T19:00", end = "",
            allDay = false, location = "", repeat = "", timezone = "", phoneTimezone = "America/New_York",
            createdAt = 0)
        val evening = millis(LocalDateTime.of(2037, 11, 13, 18, 5))
        assertTrue(stillOnDue(plan, evening, newYork))
        assertFalse(stillOnDue(plan, millis(LocalDateTime.of(2037, 11, 13, 17, 0)), newYork))
        assertFalse(stillOnDue(plan.copy(change = "moved"), evening, newYork))
        assertFalse(stillOnDue(plan.copy(status = Suggestion.STATUS_ADDED), evening, newYork))
        assertFalse(stillOnDue(plan.copy(start = "2037-11-15T19:00"), evening, newYork))
    }

    // The week ahead is due on Friday from its time, once a day
    @Test
    fun weekAheadTiming() {
        val friday = millis(LocalDateTime.of(2037, 11, 13, 18, 10))
        assertTrue(weekAheadDue(friday, newYork, 18, 0, ""))
        assertFalse(weekAheadDue(friday, newYork, 18, 0, "2037-11-13"))
        assertFalse(weekAheadDue(millis(LocalDateTime.of(2037, 11, 13, 17, 50)), newYork, 18, 0, ""))
        assertFalse(weekAheadDue(millis(LocalDateTime.of(2037, 11, 12, 18, 10)), newYork, 18, 0, ""))
    }

    // The week ahead lists each day with events (overlaps marked), skips empty days, then the bills due that week
    @Test
    fun weekAheadText() {
        val hour = 3_600_000L
        val practice = millis(LocalDateTime.of(2037, 11, 14, 9, 0))
        val holiday = LocalDateTime.of(2037, 11, 17, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val events = listOf(
            CalendarEvent(1, 1, "Practice", practice, practice + 2 * hour, false),
            CalendarEvent(2, 1, "Brunch", practice + hour, practice + 2 * hour, false),
            CalendarEvent(3, 1, "Holiday", holiday, holiday + 24 * hour, true),
        )
        val lines = weekAheadLines(events, listOf(bill, bill.copy(title = "Rent", date = "2037-12-01")),
            LocalDate.of(2037, 11, 13), newYork)
        assertEquals(listOf("Sat Nov 14: 9:00 AM Practice ⚠, 10:00 AM Brunch ⚠", "Tue Nov 17: All day Holiday",
            "Due Fri Nov 20: Phone bill $45"), lines)
        assertEquals("Next week: 3 events", weekAheadTitle(3))
        assertEquals("Next week: nothing planned", weekAheadTitle(0))
    }

    // Claude gets the week's lines and the open loose plans, inside tags
    @Test
    fun briefPromptText() {
        val loose = bill.copy(kind = KIND_LOOSE, title = "Bowling with Jordan", sender = "Jordan")
        assertEquals("<week>\nSat Nov 14: 9:00 AM Practice\nLoose plans to pin down: Bowling with Jordan " +
            "(from Jordan)\n</week>", briefPrompt(listOf("Sat Nov 14: 9:00 AM Practice"), listOf(loose)))
        assertEquals("<week>\nNothing on the calendar.\n</week>", briefPrompt(emptyList(), emptyList()))
    }

    // The Week ahead screen lists all 7 days after today, empty ones too, all-day events first
    @Test
    fun sevenDays() {
        val hour = 3_600_000L
        val practice = millis(LocalDateTime.of(2037, 11, 14, 9, 0))
        val holiday = LocalDateTime.of(2037, 11, 14, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val events = listOf(HomeEvent("Practice", practice, practice + 2 * hour, false),
            HomeEvent("Holiday", holiday, holiday + 24 * hour, true))
        val days = weekDays(events, LocalDate.of(2037, 11, 13), newYork)
        assertEquals(7, days.size)
        assertEquals("Tomorrow", days[0].label)
        assertEquals(listOf("Holiday", "Practice"), days[0].events.map { it.title })
        assertTrue(days[1].events.isEmpty())
        assertEquals("Fri Nov 20", days[6].label)
    }
}

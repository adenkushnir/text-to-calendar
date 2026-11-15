// Tests for the Today card's rows: what's on today, their order, which are over, which is lit, and countdowns
package io.github.adenkushnir.datebook.today
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks Today on Tue Nov 10 2037 in New York
class TodayTest {
    private val newYork = ZoneId.of("America/New_York")
    private val base = FollowUp(kind = KIND_REMINDER, title = "", date = "", amount = "", sender = "", app = "",
        conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0, nudges = 0)

    // Milliseconds for a time in New York
    private fun millis(time: LocalDateTime): Long {
        return time.atZone(newYork).toInstant().toEpochMilli()
    }

    // A timed event on Nov 10 (or another day) from one hour to another
    private fun timed(title: String, day: Int, from: Int, to: Int, place: String = ""): HomeEvent {
        return HomeEvent(title, millis(LocalDateTime.of(2037, 11, day, from, 0)),
            millis(LocalDateTime.of(2037, 11, day, to, 0)), false, place)
    }

    // An all-day event, kept at midnight UTC like the calendar does
    private fun allDay(title: String, day: Int): HomeEvent {
        val start = LocalDateTime.of(2037, 11, day, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return HomeEvent(title, start, start + 24 * 3_600_000L, true)
    }

    // A day of events and follow-ups, with things from other days and a loose plan that don't belong
    private fun day(nowMillis: Long): TodayList {
        val events = listOf(
            allDay("Mom's birthday", 10),
            HomeEvent("Late show", millis(LocalDateTime.of(2037, 11, 9, 22, 0)),
                millis(LocalDateTime.of(2037, 11, 10, 1, 0)), false),
            timed("Lunch with Sam", 10, 12, 13, "Luigi's"),
            timed("Dinner at Joe's", 10, 19, 21, "Joe's"),
            timed("Practice", 11, 18, 20),
            allDay("Holiday", 12),
        )
        val followUps = listOf(
            base.copy(id = 1, title = "Stretch", nextNudge = millis(LocalDateTime.of(2037, 11, 10, 17, 0)),
                repeat = "FREQ=DAILY"),
            base.copy(id = 2, title = "Call the dentist", nextNudge = millis(LocalDateTime.of(2037, 11, 11, 9, 0))),
            base.copy(id = 3, kind = KIND_ASSIGNMENT, title = "History essay", date = "2037-11-10", time = "23:59",
                sender = "Canvas"),
            base.copy(id = 4, kind = KIND_ASSIGNMENT, title = "Lab report", date = "2037-11-11"),
            base.copy(id = 5, kind = KIND_DUE, title = "Phone bill", date = "2037-11-10", amount = "$45"),
            base.copy(id = 6, kind = KIND_LOOSE, title = "Get lunch", date = "2037-11-10"),
        )
        return todayOf(events, followUps, nowMillis, newYork)
    }

    // Rows without a time come first, then the rest by time; other days and loose plans are left out; each row says
    // what it is
    @Test
    fun rowsInOrder() {
        val today = day(millis(LocalDateTime.of(2037, 11, 10, 12, 30)))
        assertEquals(listOf("Mom's birthday", "Phone bill", "Late show", "Lunch with Sam", "Stretch",
            "Dinner at Joe's", "History essay"), today.items.map { it.title })
        assertEquals(millis(LocalDateTime.of(2037, 11, 10, 0, 0)), today.items[2].at)  // started yesterday
        assertEquals("$45 · due today", today.items[1].detail)
        assertEquals("Every day", today.items[4].detail)
        assertEquals("Due · from Canvas", today.items[6].detail)
        assertEquals(listOf(TODAY_EVENT, TODAY_BILL, TODAY_EVENT, TODAY_EVENT, TODAY_REMINDER, TODAY_EVENT,
            TODAY_ASSIGNMENT), today.items.map { it.kind })
    }

    // At lunch, lunch is lit and the late show is over; after it, dinner is lit; rows without a time are never over
    @Test
    fun litAndPast() {
        val atLunch = millis(LocalDateTime.of(2037, 11, 10, 12, 30))
        val lunchTime = day(atLunch)
        assertEquals("Lunch with Sam", lunchTime.lit!!.title)
        assertTrue(isPast(lunchTime.items[2], atLunch))
        assertFalse(isPast(lunchTime.items[3], atLunch))
        assertFalse(isPast(lunchTime.items[0], atLunch))
        val afternoon = day(millis(LocalDateTime.of(2037, 11, 10, 14, 0)))
        assertEquals("Dinner at Joe's", afternoon.lit!!.title)
        val lateNight = millis(LocalDateTime.of(2037, 11, 10, 23, 59, 30))
        assertNull(day(lateNight).lit)
        assertFalse(nothingLeft(day(lateNight), lateNight))  // the birthday lasts all day
    }

    // Nothing is left once every row is over, or when nothing was on
    @Test
    fun nothingLeftToday() {
        val night = millis(LocalDateTime.of(2037, 11, 10, 22, 0))
        val dinner = todayOf(listOf(timed("Dinner at Joe's", 10, 19, 21)), emptyList(), night, newYork)
        assertTrue(nothingLeft(dinner, night))
        assertTrue(nothingLeft(TodayList(), night))
    }

    // Countdowns read in minutes, hours, or both, rounded up
    @Test
    fun countdowns() {
        val now = millis(LocalDateTime.of(2037, 11, 10, 14, 0))
        assertEquals("in 25 min", startsIn(now + 25 * 60_000, now))
        assertEquals("in 2 h", startsIn(now + 120 * 60_000, now))
        assertEquals("in 2 h 10 min", startsIn(now + 130 * 60_000, now))
        assertEquals("in 1 min", startsIn(now + 30_000, now))
    }
}

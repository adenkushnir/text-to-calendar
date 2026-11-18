// Tests for reading Canvas's calendar feed and working out what changed, with a made-up course
package io.github.adenkushnir.datebook.canvas
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the feed with a made-up physics course, on Tue Nov 17 2037 at noon in New York
class CanvasFeedTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2037, 11, 17, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val feed = listOf(
        "BEGIN:VCALENDAR",
        "BEGIN:VEVENT",
        "DTSTART;TZID=UTC:20371121T045900",
        "DTEND;TZID=UTC:20371121T045900",
        "SUMMARY:Problem Set 3 [PHYS-101]",
        "UID:event-assignment-501",
        "URL:https://school.instructure.com/calendar#assignment_501",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;VALUE=DATE:20371124",
        "SUMMARY:Midterm reading\\, chapters 1-3 [PHYS-101]",
        "UID:event-assignment-502",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART:20371119T150000Z",
        "DTEND:20371119T163000Z",
        "SUMMARY:Lab section [PHYS-",
        " 101]",
        "LOCATION:Science Hall 2",
        "UID:event-calendar-event-77",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART:20371101T150000Z",
        "SUMMARY:Old lecture [PHYS-101]",
        "UID:event-calendar-event-70",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n")

    // A Canvas assignment saved by an earlier check
    private fun saved(uid: String, date: String, time: String, title: String = "Problem Set 3 · PHYS-101",
        status: String = FollowUp.OPEN): FollowUp {
        return FollowUp(kind = KIND_ASSIGNMENT, title = title, date = date, amount = "", sender = "Canvas",
            app = CANVAS_APP, conversation = "PHYS-101", status = status, createdAt = 0, nextNudge = 0, nudges = 0,
            time = time, sourceId = uid)
    }

    // Entries are read with their course split off, times in the phone's zone, folded lines joined and escapes undone
    @Test
    fun readsTheFeed() {
        val items = parseFeed(feed, zone)
        assertEquals(4, items.size)
        val problemSet = items[0]
        assertTrue(problemSet.isAssignment)
        assertEquals("Problem Set 3", problemSet.title)
        assertEquals("PHYS-101", problemSet.course)
        assertEquals(LocalDate.of(2037, 11, 20), problemSet.date)
        assertEquals("23:59", problemSet.time)
        assertEquals("Midterm reading, chapters 1-3", items[1].title)
        assertEquals("", items[1].time)
        val lab = items[2]
        assertFalse(lab.isAssignment)
        assertEquals("PHYS-101", lab.course)
        assertEquals("10:00", lab.time)
        assertEquals("Science Hall 2", lab.location)
        assertEquals("2037-11-19T10:00", planStart(lab))
        assertEquals("2037-11-19T11:30", planEnd(lab, zone))
    }

    // The first check adds every assignment still due and offers the class events still ahead
    @Test
    fun firstCheck() {
        val plan = planCheck(parseFeed(feed, zone), emptyList(), emptyList(), now, zone)
        assertEquals(listOf("event-assignment-501", "event-assignment-502"), plan.newAssignments.map { it.uid })
        assertEquals(listOf("event-calendar-event-77"), plan.newClassEvents.map { it.uid })
        assertTrue(plan.changed.isEmpty() && plan.removed.isEmpty())
    }

    // A moved due date is a change, an assignment gone from Canvas is removed, and finished ones never come back
    @Test
    fun laterCheck() {
        val items = parseFeed(feed, zone)
        val earlier = listOf(saved("event-assignment-501", "2037-11-19", "23:59"),
            saved("event-assignment-502", "2037-11-24", "", "Midterm reading, chapters 1-3 · PHYS-101",
                status = FollowUp.DONE),
            saved("event-assignment-499", "2037-11-30", "09:00", "Essay · PHYS-101"))
        val lab = Suggestion(sender = "PHYS-101", app = CANVAS_APP, title = "Lab section", start = "2037-11-19T09:00",
            end = "", allDay = false, location = "", repeat = "", timezone = "", phoneTimezone = zone.id, createdAt = 0,
            sourceId = "event-calendar-event-77")
        val plan = planCheck(items, earlier, listOf(lab), now, zone)
        assertTrue(plan.newAssignments.isEmpty())
        assertEquals("2037-11-19", plan.changed.single().first.date)
        assertEquals(LocalDate.of(2037, 11, 20), plan.changed.single().second.date)
        assertEquals("event-assignment-499", plan.removed.single().sourceId)
        assertTrue(plan.newClassEvents.isEmpty())
        assertEquals("2037-11-19T10:00", planStart(plan.movedClassEvents.single().second))
    }

    // Only Canvas feed links are taken, and webcal links are fetched over https
    @Test
    fun links() {
        assertTrue(looksLikeFeed("https://school.instructure.com/feeds/calendars/user_abc123.ics"))
        assertTrue(looksLikeFeed("webcal://school.instructure.com/feeds/calendars/user_abc123.ics"))
        assertFalse(looksLikeFeed("https://school.instructure.com/calendar"))
        assertEquals("https://school.instructure.com/feeds/calendars/user_abc123.ics",
            feedUrl("webcal://school.instructure.com/feeds/calendars/user_abc123.ics"))
        assertEquals(Pair("Quiz 2", "BIO 1"), splitCourse("Quiz 2 [BIO 1]"))
        assertEquals("Wed Nov 25, 11:59 PM", dueText("2037-11-25", "23:59"))
    }
}

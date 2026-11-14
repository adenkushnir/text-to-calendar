// Tests for the home dashboard's sections and buttons, run on the PC with Robolectric
package io.github.adenkushnir.datebook
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.agendaOf
import io.github.adenkushnir.datebook.calendar.fromCalendar
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks Next up, Waiting for you and Coming up, with and without events
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val newYork = "America/New_York"
    private val now = ZonedDateTime.of(2037, 11, 10, 14, 0, 0, 0, ZoneId.of(newYork)).toInstant().toEpochMilli()

    // Build a suggestion with a title, start and status
    private fun plan(id: Long, title: String, start: String, status: String): Suggestion {
        return Suggestion(id = id, sender = "Sam", app = "sms", title = title, start = start, end = "",
            allDay = false, location = "Joe's", repeat = "", timezone = "", phoneTimezone = newYork, status = status,
            createdAt = 0)
    }

    // With nothing yet, every section shows its empty message
    @Test
    fun emptyHome() {
        compose.setContent {
            DatebookTheme {
                HomeScreen(nowMillis = now)
            }
        }
        compose.onNodeWithText("Nothing coming up yet").assertExists()
        compose.onNodeWithText("WAITING FOR YOU (0)").assertExists()
        compose.onNodeWithText("Nothing on your calendar this month.").assertExists()
    }

    // A calendar event of the given length starting at a time in New York, on a blue calendar
    private fun event(id: Long, title: String, start: ZonedDateTime, hours: Long): CalendarEvent {
        val begin = start.toInstant().toEpochMilli()
        return CalendarEvent(id, 1, title, begin, begin + hours * 3_600_000, false, "Joe's", 0xFF3366CC.toInt())
    }

    // While class is on, Next up says when it ends and what's after; the next days are listed by day with who a
    // Datebook plan came from; tapping an event opens it; waiting suggestions still have Add and their clash warning
    @Test
    fun homeWithCalendar() {
        val zone = ZoneId.of(newYork)
        val dinner = plan(1, "Dinner at Joe's", "2037-11-10T19:00", Suggestion.STATUS_ADDED)
        val movie = plan(2, "Movie", "2037-11-11T22:00", Suggestion.STATUS_ADDED)
        val lunch = plan(3, "Lunch with Mom", "2037-11-15T12:00", Suggestion.STATUS_WAITING)
            .copy(clash = "You already have Practice 11:00 AM–1:00 PM")
        val events = fromCalendar(listOf(
            event(10, "Class", ZonedDateTime.of(2037, 11, 10, 13, 0, 0, 0, zone), 2),
            event(11, "Dinner at Joe's", ZonedDateTime.of(2037, 11, 10, 19, 0, 0, 0, zone), 1),
            event(12, "Movie", ZonedDateTime.of(2037, 11, 11, 22, 0, 0, 0, zone), 2),
        ), listOf(dinner, movie))
        val added = mutableListOf<Long>()
        val opened = mutableListOf<HomeEvent>()
        compose.setContent {
            DatebookTheme {
                HomeScreen(waiting = listOf(lunch), agenda = agendaOf(events, now, zone), nowMillis = now, zone = zone,
                    onAdd = { added.add(it) }, onOpenEvent = { opened.add(it) })
            }
        }
        compose.onNodeWithText("NOW · ENDS 3:00 PM").assertExists()
        compose.onAllNodesWithText("Class").assertCountEquals(2)  // in Next up and in Coming up
        compose.onNodeWithText("Then: Dinner at Joe's · 7:00 PM").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("10:00 PM · Joe's").assertExists()
        compose.onNodeWithText("WAITING FOR YOU (1)").assertExists()
        compose.onNodeWithText("You already have Practice 11:00 AM–1:00 PM").assertExists()
        compose.onAllNodesWithText("Add")[0].performClick()
        assertEquals(listOf(3L), added)
        compose.onNodeWithText("Movie").performScrollTo().performClick()
        assertEquals(listOf(12L), opened.map { it.eventId })
        assertEquals(movie, opened.single().source)
    }
}

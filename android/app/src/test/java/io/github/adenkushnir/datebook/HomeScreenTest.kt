// Tests for the home dashboard's sections and buttons, run on the PC with Robolectric
package io.github.adenkushnir.datebook
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.agendaOf
import io.github.adenkushnir.datebook.calendar.fromCalendar
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.today.todayOf
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.weather.DayWeather
import io.github.adenkushnir.datebook.weather.Forecast
import io.github.adenkushnir.datebook.weather.HourWeather
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks Today, Waiting for you, Coming up and the train board, with and without events
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
        compose.onNodeWithText("TODAY · TUE NOV 10").assertExists()
        compose.onNodeWithText("Nothing left today.").assertExists()
        compose.onNodeWithText("WAITING FOR YOU (0)").assertExists()
        compose.onNodeWithText("Nothing else on your calendar this month.").assertExists()
    }

    // Bills show with their amount, day and Paid; loose plans with who they came from; tapping acts on them
    @Test
    fun followUpSections() {
        val bill = FollowUp(id = 7, kind = KIND_DUE, title = "Phone bill", date = "2037-11-20", amount = "$45",
            sender = "Verizon", app = "sms", conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0,
            nudges = 0)
        val lunch = bill.copy(id = 8, kind = KIND_LOOSE, title = "Lunch with Sam", date = "", amount = "",
            sender = "Sam")
        val acted = mutableListOf<String>()
        compose.setContent {
            DatebookTheme {
                HomeScreen(nowMillis = now, bills = listOf(bill), loosePlans = listOf(lunch),
                    onPaid = { acted.add("paid $it") }, onOpenChat = { acted.add("chat ${it.sender}") })
            }
        }
        compose.onNodeWithText("BILLS DUE").assertExists()
        compose.onNodeWithText("$45 · due Fri Nov 20").assertExists()
        compose.onNodeWithText("From Sam").assertExists()
        compose.onNodeWithText("Paid").performClick()
        compose.onNodeWithText("Lunch with Sam").performScrollTo().performClick()
        assertEquals(listOf("paid 7", "chat Sam"), acted)
    }

    // A calendar event of the given length starting at a time in New York, on a blue calendar
    private fun event(id: Long, title: String, start: ZonedDateTime, hours: Long): CalendarEvent {
        val begin = start.toInstant().toEpochMilli()
        return CalendarEvent(id, 1, title, begin, begin + hours * 3_600_000, false, "Joe's", 0xFF3366CC.toInt())
    }

    // While class is on, Today lights it and says when it ends, with dinner after it; the next days are listed by day
    // with who a Datebook plan came from; tapping an event opens it; waiting suggestions still have Add and their clash
    // warning
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
                HomeScreen(waiting = listOf(lunch), agenda = agendaOf(events, now, zone),
                    today = todayOf(events, emptyList(), now, zone), nowMillis = now, zone = zone,
                    onAdd = { added.add(it) }, onOpenEvent = { opened.add(it) })
            }
        }
        compose.onNodeWithText("Now · ends 3:00 PM").assertExists()
        compose.onAllNodesWithText("Class").assertCountEquals(1)  // only in Today now
        compose.onNodeWithText("7:00 PM").assertExists()  // dinner's row in Today
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("10:00 PM · Joe's").assertExists()
        compose.onNodeWithText("WAITING FOR YOU (1)").assertExists()
        compose.onNodeWithText("You already have Practice 11:00 AM–1:00 PM").assertExists()
        compose.onAllNodesWithText("Add")[0].performScrollTo().performClick()  // below Today now
        assertEquals(listOf(3L), added)
        compose.onNodeWithText("Movie").performScrollTo().performClick()
        assertEquals(listOf(12L), opened.map { it.eventId })
        assertEquals(movie, opened.single().source)
    }

    // Today shows the weather, a reminder, a bill with Paid and the countdown to dinner with its weather; the train
    // board at the bottom shows the train that left, the next one and the Sample tag, and flips
    @Test
    fun todayAndTrains() {
        val zone = ZoneId.of(newYork)
        val dinner = event(20, "Dinner at Joe's", ZonedDateTime.of(2037, 11, 10, 19, 0, 0, 0, zone), 2)
        val events = fromCalendar(listOf(dinner), emptyList())
        val stretch = FollowUp(id = 5, kind = KIND_REMINDER, title = "Stretch", date = "", amount = "", sender = "you",
            app = "datebook.typed", conversation = "", status = FollowUp.OPEN, createdAt = 0,
            nextNudge = ZonedDateTime.of(2037, 11, 10, 17, 0, 0, 0, zone).toInstant().toEpochMilli(), nudges = 0)
        val bill = stretch.copy(id = 6, kind = KIND_DUE, title = "Phone bill", date = "2037-11-10", amount = "$45",
            nextNudge = 0)
        val forecast = Forecast(HourWeather(now, 49, 0, 3, true), listOf(HourWeather(dinner.begin, 47, 70, 61, false)),
            listOf(DayWeather(LocalDate.of(2037, 11, 10), 52, 41, 70)))
        val board = TrainBoard("New Brunswick", "New York Penn Station", listOf("2:22 PM · Track 2 · On time"),
            last = "1:51 PM · Track 1 · Departed")
        val acted = mutableListOf<String>()
        compose.setContent {
            DatebookTheme {
                HomeScreen(today = todayOf(events, listOf(stretch, bill), now, zone), forecast = forecast,
                    nowMillis = now, zone = zone, trains = board, onPaid = { acted.add("paid $it") },
                    onFlipTrains = { acted.add("flip") })
            }
        }
        compose.onNodeWithText("49°").assertExists()
        compose.onNodeWithText("High 52° · Low 41° · Rain 70% after 7 PM").assertExists()
        compose.onNodeWithText("Every day").assertDoesNotExist()
        compose.onNodeWithText("Reminder").assertExists()
        compose.onNodeWithText("$45 · due today").assertExists()
        compose.onNodeWithText("in 5 h").assertExists()
        compose.onNodeWithText("47° · 70%").assertExists()
        compose.onNodeWithText("Paid").performClick()
        compose.onNodeWithText("1:51 PM · Track 1 · Departed").performScrollTo().assertExists()
        compose.onNodeWithText("2:22 PM · Track 2 · On time").assertExists()
        compose.onNodeWithContentDescription("Flip the trip").performScrollTo().performClick()
        assertEquals(listOf("paid 6", "flip"), acted)
    }
}

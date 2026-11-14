// Tests for the Lists tab: grouping bookings into trips, trip dates, how far away a day is, arrival days, the lists'
// rows and buttons, and the tab bar
package io.github.adenkushnir.datebook.lists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.TabBar
import io.github.adenkushnir.datebook.ui.TabItem
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the Lists tab from its data to its buttons, on Tue Nov 10 2037
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListsTest {
    @get:Rule
    val compose = createComposeRule()
    private val today = LocalDate.of(2037, 11, 10)

    // A plan with a title, start, end and trip
    private fun plan(id: Long, title: String, start: String, end: String = "", trip: String = ""): Suggestion {
        return Suggestion(id = id, sender = "you", app = "sms", title = title, start = start, end = end,
            allDay = false, location = "", repeat = "", timezone = "", phoneTimezone = "America/New_York",
            createdAt = 0, trip = trip)
    }

    // A flight, a hotel stay and a rental car to Chicago are one trip; a Boston trip is another; a past trip is left
    // out
    @Test
    fun tripsGrouped() {
        val trips = tripsOf(listOf(
            plan(1, "Flight UA1234", "2037-12-18T18:45", trip = "Chicago"),
            plan(2, "Hilton check-in", "2037-12-18T21:00", "2037-12-21T11:00", trip = "Chicago"),
            plan(3, "Hertz pickup", "2037-12-19T09:00", trip = "chicago"),
            plan(4, "Flight DL20", "2037-12-27T08:00", trip = "Boston"),
            plan(5, "Flight AA1", "2037-11-01T08:00", trip = "Miami"),
        ), today)
        assertEquals(listOf("Chicago", "Boston"), trips.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), trips[0].plans.map { it.id })
        assertEquals("Dec 18–21", tripDates(trips[0]))
        assertEquals("Dec 27", tripDates(trips[1]))
    }

    // Days away, a trip over two months, and when a package arrives
    @Test
    fun wording() {
        assertEquals("Overdue", daysAway(today.minusDays(1), today))
        assertEquals("Today", daysAway(today, today))
        assertEquals("Tomorrow", daysAway(today.plusDays(1), today))
        assertEquals("in 5 days", daysAway(today.plusDays(5), today))
        val newYear = Trip("Paris", LocalDate.of(2037, 12, 30), LocalDate.of(2038, 1, 2), emptyList())
        assertEquals("Dec 30 – Jan 2", tripDates(newYear))
        assertEquals("Arrives Mon Nov 16", arrivalText(plan(6, "UPS delivery", "2037-11-16")))
        assertEquals("Arriving soon", arrivalText(plan(7, "Amazon delivery", "")))
    }

    // Packages show Track and Got it; switching to Due soon shows assignments with Done
    @Test
    fun listsScreen() {
        val ups = plan(8, "UPS delivery", "2037-11-16").copy(packageCarrier = "ups",
            trackingNumber = "1Z999AA10123456784")
        val essay = FollowUp(id = 9, kind = KIND_ASSIGNMENT, title = "History essay", date = "2037-11-11",
            amount = "", sender = "Canvas", app = "gmail", conversation = "", status = FollowUp.OPEN, createdAt = 0,
            nextNudge = 0, nudges = 0, time = "23:59")
        val done = mutableListOf<String>()
        var shown = LIST_PACKAGES
        val content = ListsContent(listOf(ups), listOf(essay), emptyList(), emptyList(), today)
        val actions = ListsActions(onGotIt = { done.add("got $it") }, onDone = { done.add("done $it") })
        compose.setContent {
            DatebookTheme {
                ListsScreen(shown, { shown = it }, content, actions)
            }
        }
        compose.onNodeWithText("Arrives Mon Nov 16").assertExists()
        compose.onNodeWithText("Track").assertExists()
        compose.onNodeWithText("Got it").performClick()
        assertEquals(listOf("got 8"), done)
        compose.onNodeWithText("Due soon").performClick()
        assertEquals(LIST_DUE, shown)
    }

    // The Due soon list says when each assignment is due
    @Test
    fun dueSoon() {
        val essay = FollowUp(id = 9, kind = KIND_ASSIGNMENT, title = "History essay", date = "2037-11-11",
            amount = "", sender = "Canvas", app = "gmail", conversation = "", status = FollowUp.OPEN, createdAt = 0,
            nextNudge = 0, nudges = 0, time = "23:59")
        val done = mutableListOf<Long>()
        compose.setContent {
            DatebookTheme {
                ListsScreen(LIST_DUE, {}, ListsContent(emptyList(), listOf(essay), emptyList(), emptyList(), today),
                    ListsActions(onDone = { done.add(it) }))
            }
        }
        compose.onNodeWithText("Due tomorrow 11:59 PM").assertExists()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(9L), done)
    }

    // The tab bar opens the tapped tab
    @Test
    fun tabBar() {
        val opened = mutableListOf<String>()
        compose.setContent {
            DatebookTheme {
                val tabs = listOf(TabItem("home", DatebookIcons.Home, "Home"),
                    TabItem("lists", DatebookIcons.List, "Lists"))
                TabBar(tabs, "home") { opened.add(it) }
            }
        }
        compose.onNodeWithText("Lists").performClick()
        assertEquals(listOf("lists"), opened)
    }
}

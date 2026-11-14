// Tests for the home dashboard's sections and buttons, run on the PC with Robolectric
package io.github.adenkushnir.datebook
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

// Checks Next up, Waiting for you and Coming up, with and without plans
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
        compose.onNodeWithText("Other plans you add will be listed here.").assertExists()
    }

    // The soonest added plan is Next up with its countdown, later ones are under Coming up, and waiting ones have Add
    // (and their clash warning)
    @Test
    fun homeWithPlans() {
        val dinner = plan(1, "Dinner at Joe's", "2037-11-13T19:00", Suggestion.STATUS_ADDED)
        val movie = plan(2, "Movie", "2037-11-14T22:00", Suggestion.STATUS_ADDED)
        val lunch = plan(3, "Lunch with Mom", "2037-11-15T12:00", Suggestion.STATUS_WAITING)
            .copy(clash = "You already have Practice 11:00 AM–1:00 PM")
        val added = mutableListOf<Long>()
        compose.setContent {
            DatebookTheme {
                HomeScreen(waiting = listOf(lunch), ahead = listOf(dinner, movie), nowMillis = now,
                    onAdd = { added.add(it) })
            }
        }
        compose.onNodeWithText("NEXT UP · IN 3 DAYS").assertExists()
        compose.onNodeWithText("Dinner at Joe's").assertExists()
        compose.onNodeWithText("Movie").assertExists()
        compose.onNodeWithText("WAITING FOR YOU (1)").assertExists()
        compose.onNodeWithText("You already have Practice 11:00 AM–1:00 PM").assertExists()
        compose.onAllNodesWithText("ADD")[0].performClick()
        assertEquals(listOf(3L), added)
    }
}

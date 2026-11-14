// Tests for the Week ahead screen: the paragraph, free days, bills and loose plans
package io.github.adenkushnir.datebook.followups
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.adenkushnir.datebook.calendar.DayGroup
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks what the Week ahead screen shows and what tapping does
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeekAheadScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val newYork = ZoneId.of("America/New_York")

    // The paragraph on top, a busy day with an overlap, a free day, the bills, and loose plans that open their chat
    @Test
    fun weekScreen() {
        val start = LocalDateTime.of(2037, 11, 14, 9, 0).atZone(newYork).toInstant().toEpochMilli()
        val hour = 3_600_000L
        val busy = DayGroup("Tomorrow", listOf(HomeEvent("Practice", start, start + 2 * hour, false, "Gym"),
            HomeEvent("Brunch", start + hour, start + 2 * hour, false)))
        val bill = FollowUp(kind = KIND_DUE, title = "Library book", date = "2037-11-17", amount = "",
            sender = "Library", app = "sms", conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0,
            nudges = 0)
        val loose = bill.copy(kind = KIND_LOOSE, title = "Bowling with Jordan", sender = "Jordan", date = "")
        val opened = mutableListOf<String>()
        val days = listOf(busy, DayGroup("Sun Nov 15", emptyList()))
        compose.setContent {
            DatebookTheme {
                WeekAheadScreen("A busy Saturday, then a quiet week.", days, listOf(bill), listOf(loose), newYork,
                    onBack = {}, onOpenChat = { opened.add(it.sender) })
            }
        }
        compose.onNodeWithText("A busy Saturday, then a quiet week.").assertExists()
        compose.onNodeWithText("9:00 AM · Gym").assertExists()
        compose.onNodeWithText("Overlaps Brunch").assertExists()
        compose.onNodeWithText("Free").performScrollTo().assertExists()
        compose.onNodeWithText("Due Tue Nov 17").performScrollTo().assertExists()
        compose.onNodeWithText("Bowling with Jordan").performScrollTo().performClick()
        assertEquals(listOf("Jordan"), opened)
    }
}

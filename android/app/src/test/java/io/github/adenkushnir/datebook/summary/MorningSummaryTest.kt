// Tests for the morning summary's wording, which events count as today, when it's booked, and its settings
package io.github.adenkushnir.datebook.summary
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.pipeline.toMillis
import io.github.adenkushnir.datebook.settings.NotificationsPage
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the summary's text, its day, its timing and the Settings controls for it
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MorningSummaryTest {
    @get:Rule
    val compose = createComposeRule()
    private val zone = ZoneId.of("America/New_York")
    private val arrival = timed("Fencing team arrival", "2037-11-13T09:15", "")
    private val dinner = timed("Dinner", "2037-11-13T19:00", "Luigi's")
    private val dayOff = CalendarEvent(3, 1, "Day off", Instant.parse("2037-11-13T00:00:00Z").toEpochMilli(),
        Instant.parse("2037-11-14T00:00:00Z").toEpochMilli(), true)

    // A one-hour event at a New York time
    private fun timed(title: String, start: String, place: String): CalendarEvent {
        val begin = toMillis(start, zone)
        return CalendarEvent(1, 1, title, begin, begin + 3_600_000, false, place)
    }

    // The title counts events, the lines give each time, title and place, and the preview fits on one line
    @Test
    fun wording() {
        assertEquals("Today: 1 event", summaryTitle(1))
        assertEquals("Today: 3 events", summaryTitle(3))
        assertEquals(listOf("9:15 AM  Fencing team arrival", "7:00 PM  Dinner · Luigi's", "All day  Day off"),
            summaryLines(listOf(arrival, dinner, dayOff), zone))
        assertEquals("Fencing team arrival 9:15 AM, Dinner 7:00 PM", summaryPreview(listOf(arrival, dinner), zone))
        assertEquals("", waitingLine(0))
        assertEquals("1 plan waiting for you", waitingLine(1))
        assertEquals("2 plans waiting for you", waitingLine(2))
        assertEquals("8:00 AM", timeLabel(8, 0))
    }

    // Today's events are the timed ones that overlap today and all-day ones dated today, not the day before's
    @Test
    fun todayOnly() {
        val day = LocalDate.parse("2037-11-13")
        val yesterday = CalendarEvent(4, 1, "Holiday", Instant.parse("2037-11-12T00:00:00Z").toEpochMilli(),
            Instant.parse("2037-11-13T00:00:00Z").toEpochMilli(), true)
        val tomorrow = timed("Brunch", "2037-11-14T10:00", "")
        assertEquals(listOf(arrival, dayOff), eventsOnDay(listOf(arrival, yesterday, dayOff, tomorrow), day, zone))
    }

    // The next summary is later today if its time hasn't come yet, otherwise tomorrow
    @Test
    fun nextTime() {
        val early = ZonedDateTime.of(2037, 11, 13, 7, 30, 0, 0, zone)
        assertEquals(Duration.ofMinutes(30), delayUntilNext(early, 8, 0))
        val late = ZonedDateTime.of(2037, 11, 13, 9, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(23), delayUntilNext(late, 8, 0))
        val exact = ZonedDateTime.of(2037, 11, 13, 8, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(24), delayUntilNext(exact, 8, 0))
    }

    // The summary starts on at 8:00 AM and remembers changes
    @Test
    fun settingsRemembered() {
        val settings = SummarySettings(ApplicationProvider.getApplicationContext())
        assertTrue(settings.isOn())
        assertEquals(8, settings.hour())
        settings.set(false, 7, 30)
        assertFalse(settings.isOn())
        assertEquals(30, settings.minute())
    }

    // The Notifications page shows the summary time and its switch saves the choice
    @Test
    fun notificationsPage() {
        val saved = mutableListOf<Triple<Boolean, Int, Int>>()
        compose.setContent {
            DatebookTheme {
                NotificationsPage(access = true, canNotify = true, openAccess = {}, openNotifications = {},
                    summaryOn = true, summaryHour = 8, summaryMinute = 0,
                    setSummary = { on, hour, minute -> saved.add(Triple(on, hour, minute)) }, onBack = {})
            }
        }
        compose.onNodeWithText("8:00 AM").assertExists()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertEquals(listOf(Triple(false, 8, 0)), saved)
    }
}

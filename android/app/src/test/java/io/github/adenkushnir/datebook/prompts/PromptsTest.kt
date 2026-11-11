// Tests for the "Add to calendar?" prompt and the Google Calendar intent behind its Add button
package io.github.adenkushnir.datebook.prompts
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.capture.GROUPME
import io.github.adenkushnir.datebook.capture.SAMSUNG_MESSAGES
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.eventTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Checks the calendar intent's details and the prompt's text and buttons
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptsTest {
    private val newYork = "America/New_York"
    private val dinner = Suggestion(id = 7, sender = "Sam", app = SAMSUNG_MESSAGES, title = "Dinner at Joe's",
        start = "2037-11-13T19:00", end = "", allDay = false, location = "Joe's", repeat = "", timezone = "",
        phoneTimezone = newYork, createdAt = 0)
    private val practice = dinner.copy(id = 8, sender = "Jake", app = GROUPME, conversation = "Soccer Team",
        title = "Practice", start = "2037-11-14T09:00", location = "", repeat = "FREQ=WEEKLY;BYDAY=SA")

    // The intent opens the calendar's new-event screen with title, times, place and notes
    @Test
    fun calendarIntentDetails() {
        val intent = calendarInsertIntent(dinner)
        val (begin, end) = eventTimes(dinner.toEvent())
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Dinner at Joe's", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(begin, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(end, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true))
        assertEquals("Joe's", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("From Sam (Messages (Samsung)), found by Datebook",
            intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(newYork, intent.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }

    // A group event gets the group in its notes and keeps its repeat rule; no place means no place extra
    @Test
    fun groupEventDetails() {
        val intent = calendarInsertIntent(practice)
        assertEquals("From Jake in Soccer Team (GroupMe), found by Datebook",
            intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals("FREQ=WEEKLY;BYDAY=SA", intent.getStringExtra(CalendarContract.Events.RRULE))
        assertFalse(intent.hasExtra(CalendarContract.Events.EVENT_LOCATION))
    }

    // An all-day event says so and gets no time zone
    @Test
    fun allDayEvent() {
        val birthday = dinner.copy(title = "Sam's birthday", start = "2037-03-03", allDay = true, location = "")
        val intent = calendarInsertIntent(birthday)
        assertTrue(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertFalse(intent.hasExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }

    // The prompt shows the title, who it's from with the quote, when and where, and Add, Edit and Ignore
    @Test
    fun promptTextAndButtons() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = SuggestionNotifier(context)
        notifier.show(practice, "practice sat 9am")
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).getNotification(8)
        assertEquals("Practice", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
        val details = posted.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString()
        assertTrue(details.startsWith("From Jake in Soccer Team: \"practice sat 9am\""))
        assertTrue(details.contains("Sat Nov 14, 9:00 AM"))
        assertEquals(listOf("Add", "Edit", "Ignore"), posted.actions.map { it.title.toString() })
        notifier.cancel(8)
        assertEquals(null, shadowOf(manager).getNotification(8))
    }

    // Without notification permission nothing is posted (the home screen still lists the suggestion)
    @Test
    fun noPermissionNoPrompt() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SuggestionNotifier(context).show(dinner, "dinner fri 7pm")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}

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
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import io.github.adenkushnir.datebook.pipeline.SHARED_IMAGE_APP
import io.github.adenkushnir.datebook.pipeline.SHARED_TEXT_APP
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
        assertEquals("Sam (Messages)", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(newYork, intent.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }

    // A group event gets the group in its notes and keeps its repeat rule; no place means no place extra
    @Test
    fun groupEventDetails() {
        val intent = calendarInsertIntent(practice)
        assertEquals("Jake · Soccer Team (GroupMe)", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals("FREQ=WEEKLY;BYDAY=SA", intent.getStringExtra(CalendarContract.Events.RRULE))
        assertFalse(intent.hasExtra(CalendarContract.Events.EVENT_LOCATION))
    }

    // Shared plans say so in their notes and From line instead of naming a sender
    @Test
    fun sharedEventDetails() {
        val shared = dinner.copy(sender = "you", app = SHARED_TEXT_APP)
        assertEquals("Shared to Datebook", calendarNotes(shared))
        assertEquals("Image shared to Datebook", calendarNotes(shared.copy(app = SHARED_IMAGE_APP)))
        assertEquals("you (shared to Datebook)", fromLine(shared))
    }

    // An all-day event says so and gets no time zone
    @Test
    fun allDayEvent() {
        val birthday = dinner.copy(title = "Sam's birthday", start = "2037-03-03", allDay = true, location = "")
        val intent = calendarInsertIntent(birthday)
        assertTrue(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertFalse(intent.hasExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }

    // The prompt shows the title, who it's from with the quote, when and where, and Add and Ignore
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
        assertEquals(listOf("Add", "Ignore"), posted.actions.map { it.title.toString() })
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

    // Changes get a question or a cancelled title, and open the calendar event instead of a new one
    @Test
    fun changeTitlesAndIntent() {
        val moved = dinner.copy(title = "Dinner", start = "2037-11-13T20:00", change = CHANGE_MOVED,
            calendarEventId = 42)
        assertEquals("Move Dinner to Fri Nov 13, 8:00 PM?", promptTitle(moved))
        assertEquals("Dinner on Fri Nov 13 is cancelled", promptTitle(moved.copy(change = CHANGE_CANCELLED)))
        assertEquals("Dinner at Joe's", promptTitle(dinner))
        val intent = calendarEventIntent(moved)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("content://com.android.calendar/events/42", intent.data.toString())
        assertTrue(isChange(moved))
        assertFalse(isChange(dinner))
    }

    // Packages and flights put their Track link in the calendar notes; other plans keep the plain notes
    @Test
    fun trackLinkInNotes() {
        val delivery = dinner.copy(title = "UPS delivery", packageCarrier = "ups",
            trackingNumber = "1Z999AA10123456784")
        assertEquals("Sam (Messages)\nTrack: https://www.ups.com/track?tracknum=1Z999AA10123456784",
            notesWithTrackLink(delivery))
        assertEquals("Sam (Messages)", notesWithTrackLink(dinner))
    }
}

// Builds the intent that opens Google Calendar's new-event screen with a suggestion filled in (Datebook never
// writes events itself; you save them in Calendar)
package io.github.adenkushnir.datebook.prompts
import android.content.Intent
import android.provider.CalendarContract
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.eventZone
import io.github.adenkushnir.datebook.pipeline.repeatRule

// Who a suggestion came from, like "Sam" or "Jake in Soccer Team"
fun fromLine(suggestion: Suggestion): String {
    return if (suggestion.conversation.isNotBlank()) "${suggestion.sender} in ${suggestion.conversation}"
    else suggestion.sender  // one-to-one
}

// The event's notes in the calendar, like "From Jake in Soccer Team (GroupMe), found by Datebook"
fun calendarNotes(suggestion: Suggestion): String {
    val app = WATCHABLE_APPS.firstOrNull { it.packageName == suggestion.app }?.label  // the app's name, if known
    val via = if (app != null) " ($app)" else ""
    return "From ${fromLine(suggestion)}$via, found by Datebook"
}

// The intent for Google Calendar's new-event screen: title, start and end, all-day, place, repeat, zone and notes
fun calendarInsertIntent(suggestion: Suggestion): Intent {
    val event = suggestion.toEvent()
    val (begin, end) = eventTimes(event)
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title.ifBlank { "Event" })
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        .putExtra(CalendarContract.Events.DESCRIPTION, calendarNotes(suggestion))

    // Add the place only when there is one
    if (event.location.isNotBlank()) {
        intent.putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
    }

    // Add the repeat rule only when there is a valid one
    if (repeatRule(event).isNotEmpty()) {
        intent.putExtra(CalendarContract.Events.RRULE, repeatRule(event))
    }
    val zone = eventZone(event)

    // Tell the calendar which zone a timed event belongs to, when it is known
    if (!event.allDay && zone != null) {
        intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
    }
    return intent
}

// Builds the intent that opens your calendar app's new-event screen with a suggestion filled in (Datebook never
// writes events itself; you save them in Calendar)
package io.github.adenkushnir.datebook.prompts
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import io.github.adenkushnir.datebook.pipeline.SHARED_IMAGE_APP
import io.github.adenkushnir.datebook.pipeline.SHARED_TEXT_APP
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.eventZone
import io.github.adenkushnir.datebook.pipeline.isShared
import io.github.adenkushnir.datebook.pipeline.repeatRule
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.tracking.trackLinkFor

// Who a suggestion came from, like "Sam", "Jake in Soccer Team" or "you (shared to Datebook)"
fun fromLine(suggestion: Suggestion): String {
    // Pick the wording for where it came from
    return when {
        isShared(suggestion.app) -> "you (shared to Datebook)"
        suggestion.fromMe -> "you, to ${suggestion.conversation.ifBlank { suggestion.sender }}"
        suggestion.conversation.isNotBlank() -> "${suggestion.sender} in ${suggestion.conversation}"
        else -> suggestion.sender  // one-to-one
    }
}

// The event's notes in the calendar, like "Jake · Soccer Team (GroupMe)", "Sam (Messages)" or "Shared to Datebook"
fun calendarNotes(suggestion: Suggestion): String {
    // Shared things say so instead of naming a sender
    when (suggestion.app) {
        SHARED_TEXT_APP -> return "Shared to Datebook"
        SHARED_IMAGE_APP -> return "Image shared to Datebook"
    }
    val app = WATCHABLE_APPS.firstOrNull { it.packageName == suggestion.app }?.shortName  // the app's name, if known
    val group = if (suggestion.conversation.isNotBlank()) " · ${suggestion.conversation}" else ""  // group chats only
    val via = if (app != null) " ($app)" else ""

    // Texts you sent name who they went to
    if (suggestion.fromMe) {
        return "You → ${suggestion.conversation.ifBlank { suggestion.sender }}$via"
    }
    return "${suggestion.sender}$group$via"
}

// The title a suggestion is shown with: its own title for a new plan, "Move Dinner to Fri Nov 13, 8:00 PM?" for a move,
// or "Dinner on Fri Nov 13 is cancelled" for a cancellation
fun promptTitle(suggestion: Suggestion): String {
    val event = suggestion.toEvent().copy(location = "", repeat = "")
    val whenText = summary(event).removePrefix("${event.title} · ")  // the day, and the time unless all-day

    // Pick the wording for the kind of suggestion
    return when (suggestion.change) {
        CHANGE_MOVED -> "Move ${suggestion.title} to $whenText?"
        CHANGE_CANCELLED -> "${suggestion.title} on ${whenText.substringBefore(",")} is cancelled"
        else -> suggestion.title
    }
}

// The note for a waiting plan whose time changed in the chat, like "Changed in chat (was Fri Nov 13, 7:00 PM)", or
// null when it didn't change
fun changedNote(suggestion: Suggestion): String? {
    // Only plans that changed get a note
    if (suggestion.previousStart.isBlank()) {
        return null
    }
    val before = suggestion.toEvent().copy(start = suggestion.previousStart, location = "", repeat = "")
    return "Changed in chat (was ${summary(before).removePrefix("${before.title} · ")})"
}

// Return true if a suggestion changes or cancels an event already on the calendar
fun isChange(suggestion: Suggestion): Boolean {
    return suggestion.change.isNotBlank()
}

// The intent that opens a calendar event in your calendar app, where you can edit or delete it
fun calendarEventIntent(suggestion: Suggestion): Intent {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, suggestion.calendarEventId)
    return Intent(Intent.ACTION_VIEW, uri)
}

// The calendar notes plus the Track link on its own line for packages and flights, so it's in the event too
fun notesWithTrackLink(suggestion: Suggestion): String {
    val link = trackLinkFor(suggestion.flight, suggestion.packageCarrier, suggestion.trackingNumber)
    return if (link != null) "${calendarNotes(suggestion)}\nTrack: ${link.url}" else calendarNotes(suggestion)
}

// The intent for the calendar app's new-event screen: title, start and end, all-day, place, repeat, zone and notes
fun calendarInsertIntent(suggestion: Suggestion): Intent {
    val event = suggestion.toEvent()
    val (begin, end) = eventTimes(event)
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title.ifBlank { "Event" })
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        .putExtra(CalendarContract.Events.DESCRIPTION, notesWithTrackLink(suggestion))

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

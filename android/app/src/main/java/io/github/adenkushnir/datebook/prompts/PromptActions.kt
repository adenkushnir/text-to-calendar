// What Add and Ignore do, wherever they are tapped: the notification, the home screen or the suggestion card
package io.github.adenkushnir.datebook.prompts
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.calendar.canWriteCalendar
import io.github.adenkushnir.datebook.calendar.insertEvent
import io.github.adenkushnir.datebook.calendar.openEventIntent
import io.github.adenkushnir.datebook.calendar.planFields
import io.github.adenkushnir.datebook.calendar.readEvent
import io.github.adenkushnir.datebook.calendar.updateEvent
import io.github.adenkushnir.datebook.calendar.writeCalendarId
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Mark a suggestion added, remove its prompt, save it straight to your calendar and open it there (a move changes the
// event's time first; a cancellation opens the event to delete it); without calendar write access it opens the
// calendar app's new-event screen instead
suspend fun addSuggestion(context: Context, suggestionId: Long) {
    val eventId = addNow(context, suggestionId) ?: return  // already gone
    val suggestion = AppGraph.get(context).dao.suggestion(suggestionId) ?: return

    // Open the calendar, telling you if no calendar app can take the event
    try {
        val intent = when {
            eventId > 0 -> openEventIntent(eventId)
            isChange(suggestion) -> calendarEventIntent(suggestion)
            else -> calendarInsertIntent(suggestion)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: ActivityNotFoundException) {  // no calendar app installed
        Toast.makeText(context, "No calendar app found to add the event", Toast.LENGTH_LONG).show()
    }
}

// Mark a suggestion added, remove its prompt and write it to the calendar; returns the event's id, 0 when it couldn't
// be written (no access, a cancellation, a repeating event to move), or null when the suggestion is gone
suspend fun addNow(context: Context, suggestionId: Long): Long? {
    val graph = AppGraph.get(context)
    val suggestion = graph.dao.suggestion(suggestionId) ?: return null  // already gone
    graph.dao.setStatus(suggestionId, Suggestion.STATUS_ADDED)
    graph.notifier.cancel(suggestionId)
    val eventId = withContext(Dispatchers.IO) { saveToCalendar(context, suggestion) } ?: return 0

    // A new event is remembered on its plan, so the plan and the event stay matched
    if (!isChange(suggestion)) {
        graph.dao.updateSuggestion(suggestion.copy(status = Suggestion.STATUS_ADDED, calendarEventId = eventId))
    }
    return eventId
}

// Write a plan to the calendar: a new event, or a moved event's new time (not repeating ones, whose whole series
// would move); returns the event's id, or null when it isn't written
private fun saveToCalendar(context: Context, suggestion: Suggestion): Long? {
    // Only with access, and never for cancellations (you delete those yourself)
    if (!canWriteCalendar(context) || (isChange(suggestion) && suggestion.change != CHANGE_MOVED)) {
        return null
    }

    // A move: the event's new day and time, everything else kept
    if (isChange(suggestion)) {
        val old = readEvent(context, suggestion.calendarEventId)?.takeIf { it.repeat.isBlank() } ?: return null
        val new = planFields(suggestion)
        val moved = old.copy(begin = new.begin, end = new.end, allDay = new.allDay, timezone = new.timezone)
        return if (updateEvent(context, suggestion.calendarEventId, moved)) suggestion.calendarEventId else null
    }
    val calendarId = writeCalendarId(context, AppGraph.get(context).calendarSwitches.writeCalendar()) ?: return null
    return insertEvent(context, calendarId, suggestion)
}

// Mark a suggestion ignored and remove its prompt
suspend fun ignoreSuggestion(context: Context, suggestionId: Long) {
    val graph = AppGraph.get(context)
    graph.dao.setStatus(suggestionId, Suggestion.STATUS_IGNORED)
    graph.notifier.cancel(suggestionId)
}

// An invisible screen the notification's Add button opens, because Android only lets notifications open the
// calendar through a screen of Datebook's own; it does the work and closes straight away
class PromptActionActivity : Activity() {
    // Add the suggestion named in the intent, then close
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suggestionId = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1)
        CoroutineScope(Dispatchers.Main).launch {
            addSuggestion(this@PromptActionActivity, suggestionId)
            finish()
        }
    }
}

// Handles Not now on a quiet "Plan on screen" notification: it goes away and the plan keeps waiting on Home
class NotNowReceiver : BroadcastReceiver() {
    // Remove the notification only
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.get(context).notifier.cancel(intent.getLongExtra(EXTRA_SUGGESTION_ID, -1))
    }
}

// Handles the notification's Ignore button in the background
class IgnoreReceiver : BroadcastReceiver() {
    // Ignore the suggestion named in the intent
    override fun onReceive(context: Context, intent: Intent) {
        val suggestionId = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if it fails
            try {
                ignoreSuggestion(context.applicationContext, suggestionId)
            } finally {
                pending.finish()
            }
        }
    }
}

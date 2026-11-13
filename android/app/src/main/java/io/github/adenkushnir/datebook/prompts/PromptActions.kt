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
import io.github.adenkushnir.datebook.data.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Mark a suggestion added, remove its prompt, and open your calendar app with it filled in (or, for a change, open the
// event it changes)
suspend fun addSuggestion(context: Context, suggestionId: Long) {
    val graph = AppGraph.get(context)
    val suggestion = graph.dao.suggestion(suggestionId) ?: return  // already gone
    graph.dao.setStatus(suggestionId, Suggestion.STATUS_ADDED)
    graph.notifier.cancel(suggestionId)

    // Open the calendar, telling you if no calendar app can take the event
    try {
        val intent = if (isChange(suggestion)) calendarEventIntent(suggestion) else calendarInsertIntent(suggestion)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: ActivityNotFoundException) {  // no calendar app installed
        Toast.makeText(context, "No calendar app found to add the event", Toast.LENGTH_LONG).show()
    }
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

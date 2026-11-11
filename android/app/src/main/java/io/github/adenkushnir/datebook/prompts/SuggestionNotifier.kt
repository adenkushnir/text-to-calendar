// Shows the "Add to calendar?" notification for each suggestion: the detailed card from the mockup, with Add,
// Edit and Ignore; the quoted text is only shown here, never saved
package io.github.adenkushnir.datebook.prompts
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.summary
const val SUGGESTIONS_CHANNEL = "suggestions"
const val EXTRA_SUGGESTION_ID = "io.github.adenkushnir.datebook.SUGGESTION_ID"
const val ACTION_ADD = "io.github.adenkushnir.datebook.ADD"
const val ACTION_IGNORE = "io.github.adenkushnir.datebook.IGNORE"

// Posts and removes suggestion notifications
class SuggestionNotifier(private val context: Context) {
    // Create the "Plan suggestions" channel once (Android keeps it after that)
    init {
        val channel = NotificationChannel(SUGGESTIONS_CHANNEL, "Plan suggestions", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "\"Add to calendar?\" prompts for plans found in your texts"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Show the prompt for a suggestion, quoting the text it came from
    fun show(suggestion: Suggestion, quote: String) {
        // Without notification permission there is nothing to show (the home screen still lists it)
        if (!canNotify(context)) {
            return
        }
        val id = suggestion.id.toInt()
        val whenAndWhere = summary(suggestion.toEvent()).removePrefix("${suggestion.title} · ")
        val details = "From ${fromLine(suggestion)}: \"$quote\"\n$whenAndWhere"
        val notification = NotificationCompat.Builder(context, SUGGESTIONS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_datebook)
            .setColor(ContextCompat.getColor(context, R.color.brand_teal))
            .setSubText("Add to calendar?")
            .setContentTitle(suggestion.title)
            .setContentText(whenAndWhere)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openCard(suggestion.id))
            .addAction(0, "Add", addAction(suggestion.id))
            .addAction(0, "Edit", openCard(suggestion.id))
            .addAction(0, "Ignore", ignoreAction(suggestion.id))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    // Remove a suggestion's prompt
    fun cancel(suggestionId: Long) {
        NotificationManagerCompat.from(context).cancel(suggestionId.toInt())
    }

    // Tapping the prompt or Edit opens Datebook's suggestion card
    private fun openCard(suggestionId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_SUGGESTION_ID, suggestionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode(suggestionId, 0), intent, FLAGS)
    }

    // Add opens a small invisible screen that marks the suggestion added and opens Google Calendar
    private fun addAction(suggestionId: Long): PendingIntent {
        val intent = Intent(context, PromptActionActivity::class.java).setAction(ACTION_ADD)
            .putExtra(EXTRA_SUGGESTION_ID, suggestionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode(suggestionId, 1), intent, FLAGS)
    }

    // Ignore is handled in the background without opening anything
    private fun ignoreAction(suggestionId: Long): PendingIntent {
        val intent = Intent(context, IgnoreReceiver::class.java).setAction(ACTION_IGNORE)
            .putExtra(EXTRA_SUGGESTION_ID, suggestionId)
        return PendingIntent.getBroadcast(context, requestCode(suggestionId, 2), intent, FLAGS)
    }
}
private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

// A different request code for each suggestion and button, so their intents don't replace each other
private fun requestCode(suggestionId: Long, button: Int): Int {
    return (suggestionId * 3 + button).toInt()
}

// Return true if Datebook may show notifications (Android 13 and later ask for this permission)
fun canNotify(context: Context): Boolean {
    // Older Android versions allow it unless notifications were turned off
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

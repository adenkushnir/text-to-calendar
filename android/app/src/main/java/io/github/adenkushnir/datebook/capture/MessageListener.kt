// Android tells this service about each new notification; Datebook ignores every app that isn't switched on,
// then runs each message through the pipeline (Android only delivers conversation notifications, see the manifest)
package io.github.adenkushnir.datebook.capture
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.adenkushnir.datebook.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
private const val TAG = "Datebook"

// The notification listener Datebook asks you to allow in Android's notification access settings
class MessageListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // A notification was posted or updated
    override fun onNotificationPosted(notification: StatusBarNotification) {
        val graph = AppGraph.get(applicationContext)

        // First check: apps that aren't switched on are ignored without reading anything
        if (!graph.appSwitches.isOn(notification.packageName)) {
            return
        }

        val raw = rawNotification(notification)

        // Debug builds log which watched app posted and the notification's layout, never its text or names
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val layout = "${raw.messages.size} conversation messages, group: ${raw.isGroup}"
            Log.d(TAG, "[${notification.packageName}] notification received ($layout)")
        }

        // Group summaries ("3 new messages") repeat messages that have their own notifications
        if ((notification.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }
        scope.launch {
            handleNotification(applicationContext, raw)
        }
    }

    // Stop any work in progress when Android stops the listener
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// Copy the fields Datebook needs out of an Android notification
private fun rawNotification(posted: StatusBarNotification): RawNotification {
    val notification = posted.notification
    val extras = notification.extras
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val messages = style?.messages.orEmpty().map {
        RawMessage(it.person?.name?.toString(), it.text?.toString().orEmpty(), it.timestamp)  // each message
    }
    return RawNotification(
        app = posted.packageName,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        messages = messages,
        isGroup = style?.isGroupConversation ?: extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION),
        conversationTitle = style?.conversationTitle?.toString().orEmpty(),
        postedMillis = posted.postTime,
    )
}

// Run every message in a notification through the pipeline and log what happened (never the message text);
// shared by the listener and the debug "pretend a notification arrived" command
suspend fun handleNotification(context: Context, raw: RawNotification) {
    val graph = AppGraph.get(context)

    // Ignore apps that aren't switched on (the pretend command can name any app)
    if (!graph.appSwitches.isOn(raw.app)) {
        Log.i(TAG, "[${raw.app}] ignored: app not switched on")
        return
    }

    // Handle each message in the notification
    for (incoming in messagesFrom(raw)) {
        val outcome = graph.pipeline.receive(incoming) ?: continue  // no date or already handled: no trace kept
        val from = if (incoming.group.isNotBlank()) "${incoming.sender} in ${incoming.group}" else incoming.sender
        outcome.lines.forEach { Log.i(TAG, "[${incoming.app}] $from -> $it") }  // one log line per result
    }
}

// Return true if Datebook is allowed to read notifications
fun hasNotificationAccess(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

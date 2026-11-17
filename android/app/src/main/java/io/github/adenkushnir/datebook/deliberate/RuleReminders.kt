// Reminders before events from Deliberate's rules ("remind me the night before every exam"): booked by the 15-minute
// leave-by planner for the ones due soon, and posted on their own quiet channel
package io.github.adenkushnir.datebook.deliberate
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.prompts.canNotify
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
const val RULE_REMINDER_CHANNEL = "deliberate"
private const val EVENING_HOUR = 20
private const val MINUTE = 60_000L
private const val LOOK_AHEAD_MILLIS = 8 * 24 * 60 * MINUTE  // reminders up to a week ahead of an event
private const val BOOK_WITHIN_MILLIS = 20 * MINUTE  // the planner runs every 15 minutes
private const val WORK = "datebook-rule-reminder-"
private const val EXTRA_TITLE = "title"
private const val EXTRA_TEXT = "text"
private const val EXTRA_ID = "id"
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

// When a rule's reminder goes off for an event: the evening before at 8 PM, or so many minutes before it starts; null
// when the value can't be read
fun reminderAt(value: String, event: CalendarEvent, zone: ZoneId): Long? {
    // The evening before, on the event's own day
    if (value == REMIND_EVENING) {
        val day = Instant.ofEpochMilli(event.begin).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate()
        return day.minusDays(1).atTime(EVENING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }
    val minutes = value.toLongOrNull() ?: return null
    return event.begin - minutes * MINUTE
}

// What the reminder says: "Physics exam · Tue Nov 24, 9:00 AM" (the day alone for all-day events)
fun reminderLine(event: CalendarEvent, zone: ZoneId): String {
    val time = if (event.allDay) Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).format(DAY)
        else Instant.ofEpochMilli(event.begin).atZone(zone).format(DAY_TIME)
    return "${event.title.ifBlank { "Event" }} · $time" + if (event.location.isNotBlank()) " · ${event.location}" else ""
}

// Book the rule reminders due in the next 20 minutes (once each), for events in the coming week
fun planRuleReminders(context: Context, rules: List<Rule>, nowMillis: Long) {
    val reminding = rules.filter { it.isOn && it.kind == RULE_REMIND }

    // Nothing to do without reminder rules
    if (reminding.isEmpty()) {
        return
    }
    val graph = AppGraph.get(context)
    val prefs = context.getSharedPreferences("datebook-rule-reminders", Context.MODE_PRIVATE)
    val zone = ZoneId.systemDefault()
    val events = calendarEvents(context, nowMillis, nowMillis + LOOK_AHEAD_MILLIS, graph.calendarSwitches.switchedOff())

    // Check each event against each reminder rule
    for (event in events) {
        val effects = effectsFor(rules, event.title, event.location)

        // Quiet events get no reminders
        if (effects.quiet) {
            continue
        }

        // Book each reminder that's due soon and not booked yet
        for (value in effects.remindBefore) {
            val at = reminderAt(value, event, zone) ?: continue
            val key = "${event.eventId}|${event.begin}|$value"

            // Only reminders still ahead (or just missed) and within the next run
            if (at > nowMillis + BOOK_WITHIN_MILLIS || at < nowMillis - BOOK_WITHIN_MILLIS || prefs.contains(key)) {
                continue
            }
            val data = workDataOf(EXTRA_TITLE to "Coming up: ${event.title.ifBlank { "Event" }}",
                EXTRA_TEXT to reminderLine(event, zone), EXTRA_ID to key.hashCode())
            val request = OneTimeWorkRequestBuilder<RuleReminderWorker>().setInputData(data)
                .setInitialDelay((at - nowMillis).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK + key, ExistingWorkPolicy.REPLACE, request)
            prefs.edit().putLong(key, at).apply()
        }
    }
    val old = prefs.all.filter { (it.value as? Long ?: 0) < nowMillis - LOOK_AHEAD_MILLIS }.keys

    // Forget reminders long past
    if (old.isNotEmpty()) {
        val editor = prefs.edit()
        old.forEach { editor.remove(it) }
        editor.apply()
    }
}

// Posts one rule reminder
class RuleReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Show the reminder
    override suspend fun doWork(): Result {
        showRuleReminder(applicationContext, inputData.getString(EXTRA_TITLE).orEmpty(),
            inputData.getString(EXTRA_TEXT).orEmpty(), inputData.getInt(EXTRA_ID, 0))
        return Result.success()
    }
}

// Post a rule reminder that opens Datebook when tapped
private fun showRuleReminder(context: Context, title: String, text: String, key: Int) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(RULE_REMINDER_CHANNEL, "Deliberate reminders",
        NotificationManager.IMPORTANCE_DEFAULT)
    channel.description = "Reminders before events, from rules you set in Deliberate"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val id = 1_050_000_000 + (key and 0x0FFFFFFF) % 40_000_000
    val openHome = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, RULE_REMINDER_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, openHome, flags))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

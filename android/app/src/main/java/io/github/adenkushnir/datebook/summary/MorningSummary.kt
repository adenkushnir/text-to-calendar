// The morning summary: a silent notification at a chosen time (8:00 AM to start) listing the day's calendar events
// and how many plans are waiting, only on days with events; it stays until you swipe it away or tap it
package io.github.adenkushnir.datebook.summary
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
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.canNotify
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
const val SUMMARY_CHANNEL = "summary"
private const val SUMMARY_NOTIFICATION = 1_000_000_000  // far above any suggestion's id
private const val SUMMARY_WORK = "datebook-summary"
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// Remembers whether the summary is on and what time it arrives
class SummarySettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-summary", Context.MODE_PRIVATE)

    // Return true if the summary is on (it starts on)
    fun isOn(): Boolean {
        return prefs.getBoolean("on", true)
    }

    // Return the hour it arrives, 0 to 23 (8 to start)
    fun hour(): Int {
        return prefs.getInt("hour", 8)
    }

    // Return the minute it arrives
    fun minute(): Int {
        return prefs.getInt("minute", 0)
    }

    // Turn the summary on or off and set its time
    fun set(on: Boolean, hour: Int, minute: Int) {
        prefs.edit().putBoolean("on", on).putInt("hour", hour).putInt("minute", minute).apply()
    }
}

// A time of day as the app shows it, like "8:00 AM"
fun timeLabel(hour: Int, minute: Int): String {
    return LocalTime.of(hour, minute).format(CLOCK)
}

// The summary's title, like "Today: 3 events"
fun summaryTitle(count: Int): String {
    return if (count == 1) "Today: 1 event" else "Today: $count events"
}

// When an event starts, like "9:15 AM", or "All day"
private fun startText(event: CalendarEvent, zone: ZoneId): String {
    return if (event.allDay) "All day" else Instant.ofEpochMilli(event.begin).atZone(zone).format(CLOCK)
}

// One line per event for the expanded summary, like "9:15 AM  Fencing team arrival" or "7:00 PM  Dinner · Luigi's"
fun summaryLines(events: List<CalendarEvent>, zone: ZoneId): List<String> {
    return events.map { event ->
        val place = if (event.location.isNotBlank()) " · ${event.location}" else ""  // only when there is one
        "${startText(event, zone)}  ${event.title.ifBlank { "(no title)" }}$place"
    }
}

// The one-line preview of the folded summary, like "Fencing team arrival 9:15 AM, Dinner 7:00 PM"
fun summaryPreview(events: List<CalendarEvent>, zone: ZoneId): String {
    return events.joinToString(", ") { "${it.title.ifBlank { "(no title)" }} ${startText(it, zone)}" }
}

// The waiting line under the events, like "2 plans waiting for you" (empty when none are waiting)
fun waitingLine(count: Int): String {
    // Pick the wording for none, one or several
    return when (count) {
        0 -> ""
        1 -> "1 plan waiting for you"
        else -> "$count plans waiting for you"
    }
}

// Keep the events that happen on a day: timed events that overlap it, and all-day events dated that day (all-day
// events are kept at midnight UTC, so they can spill into the day before)
fun eventsOnDay(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId): List<CalendarEvent> {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events.filter {
        if (it.allDay) Instant.ofEpochMilli(it.begin).atZone(ZoneOffset.UTC).toLocalDate() == day
        else it.begin < end && start < it.end
    }
}

// How long to wait from now until the next hour:minute (tomorrow's if today's has passed)
fun delayUntilNext(now: ZonedDateTime, hour: Int, minute: Int): Duration {
    var next = now.toLocalDate().atTime(hour, minute).atZone(now.zone)

    // Use tomorrow's time when today's has already come
    if (!next.isAfter(now)) {
        next = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
    }
    return Duration.between(now, next)
}

// Post today's summary if there are events on the calendars that are switched on; returns how many events it listed
suspend fun showSummary(context: Context): Int {
    val graph = AppGraph.get(context)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()  // a day early for all-day events
    val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val events = eventsOnDay(calendarEvents(context, from, to, graph.calendarSwitches.switchedOff()), today, zone)

    // Days without events (or without permission to notify) get no summary
    if (events.isEmpty() || !canNotify(context)) {
        return 0
    }
    val waiting = graph.dao.suggestionsWithStatus(Suggestion.STATUS_WAITING).size
    val channel = NotificationChannel(SUMMARY_CHANNEL, "Morning summary", NotificationManager.IMPORTANCE_LOW)
    channel.description = "A quiet list of the day's events each morning"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val style = NotificationCompat.InboxStyle()

    // List each event, then the waiting count
    for (line in summaryLines(events, zone) + waitingLine(waiting)) {
        // Skip the waiting line when nothing is waiting
        if (line.isNotBlank()) {
            style.addLine(line)
        }
    }
    val openHome = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_teal))
        .setContentTitle(summaryTitle(events.size))
        .setContentText(summaryPreview(events, zone))
        .setStyle(style.setBigContentTitle(summaryTitle(events.size)))
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, SUMMARY_NOTIFICATION, openHome, flags))
        .build()
    NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION, notification)
    return events.size
}

// Runs at the summary time: posts the summary (when it's on), then books tomorrow's
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Show today's summary and book the next one
    override suspend fun doWork(): Result {
        // Only post while the summary is switched on
        if (AppGraph.get(applicationContext).summarySettings.isOn()) {
            showSummary(applicationContext)
        }
        scheduleSummary(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

// Book the next summary at the chosen time, or cancel it when the summary is off; KEEP leaves a booked one alone
// (used when the app opens), REPLACE re-books it (used when the setting changes)
fun scheduleSummary(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
    val settings = AppGraph.get(context).summarySettings
    val work = WorkManager.getInstance(context)

    // Nothing to book while the summary is off
    if (!settings.isOn()) {
        work.cancelUniqueWork(SUMMARY_WORK)
        return
    }
    val delay = delayUntilNext(ZonedDateTime.now(), settings.hour(), settings.minute())
    val request = OneTimeWorkRequestBuilder<SummaryWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
        .build()
    work.enqueueUniqueWork(SUMMARY_WORK, policy, request)
}

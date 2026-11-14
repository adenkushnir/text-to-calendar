// The follow-up alerts: a nudge to pin down a loose plan, reminders before a bill is due (2 days before and on the
// day), "Still on?" the evening before a plan you never added or ignored, and the Friday week ahead (a paragraph
// Claude writes, opening the Week ahead screen); a background check every 15 minutes sends whichever are due
package io.github.adenkushnir.datebook.followups
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.NUDGE_HOUR
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.followUpDay
import io.github.adenkushnir.datebook.email.EXTRA_OPEN_ROUTE
import io.github.adenkushnir.datebook.pipeline.makeClient
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.summary.eventsOnDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private const val TAG = "Datebook"
const val ROUTE_WEEK = "week"
private const val FOLLOW_UPS_CHANNEL = "follow_ups"
private const val WEEK_AHEAD_CHANNEL = "week_ahead"
private const val WORK = "datebook-follow-ups"
private const val STILL_ON_HOUR = 18  // "Still on?" comes from 6 PM the day before
private const val SNOOZE_DAYS = 3L
private const val FOLLOW_UP_IDS = 1_100_000_000  // notification ids far above any suggestion's
private const val WEEK_AHEAD_ID = 1_000_000_001
private const val ACTION_SNOOZE = "io.github.adenkushnir.datebook.FOLLOW_UP_SNOOZE"
private const val ACTION_DROP = "io.github.adenkushnir.datebook.FOLLOW_UP_DROP"
private const val ACTION_PAID = "io.github.adenkushnir.datebook.FOLLOW_UP_PAID"
private const val ACTION_TOMORROW = "io.github.adenkushnir.datebook.FOLLOW_UP_TOMORROW"
private const val EXTRA_FOLLOW_UP_ID = "io.github.adenkushnir.datebook.FOLLOW_UP_ID"
private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

// Remembers whether the Friday week ahead is on, its time (6:00 PM to start), and the day it was last sent
class WeekAheadSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-week-ahead", Context.MODE_PRIVATE)

    // Return true if the week ahead is on (it starts on)
    fun isOn(): Boolean {
        return prefs.getBoolean("on", true)
    }

    // Return the hour it arrives on Fridays, 0 to 23
    fun hour(): Int {
        return prefs.getInt("hour", 18)
    }

    // Return the minute it arrives
    fun minute(): Int {
        return prefs.getInt("minute", 0)
    }

    // Turn the week ahead on or off and set its time
    fun set(on: Boolean, hour: Int, minute: Int) {
        prefs.edit().putBoolean("on", on).putInt("hour", hour).putInt("minute", minute).apply()
    }

    // The day it was last sent (YYYY-MM-DD, "" before the first)
    fun lastSent(): String {
        return prefs.getString("last_sent", "").orEmpty()
    }

    // Remember the day it was sent
    fun setLastSent(day: String) {
        prefs.edit().putString("last_sent", day).apply()
    }

    // The last week ahead's paragraph, for the Week ahead screen ("" before the first)
    fun brief(): String {
        return prefs.getString("brief", "").orEmpty()
    }

    // Keep the latest paragraph
    fun setBrief(brief: String) {
        prefs.edit().putString("brief", brief).apply()
    }
}

// Remembers which plans already had their "Still on?" reminder (by id and start, so a moved plan gets a new one)
private class StillOnRecord(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-still-on", Context.MODE_PRIVATE)

    // Return true if this plan, at this start, was already reminded
    fun reminded(plan: Suggestion): Boolean {
        return "${plan.id}|${plan.start}" in prefs.getStringSet("reminded", emptySet()).orEmpty()
    }

    // Remember that a plan was reminded, keeping only the latest few hundred
    fun remember(plan: Suggestion) {
        val all = (prefs.getStringSet("reminded", emptySet()).orEmpty() + "${plan.id}|${plan.start}").toList()
        prefs.edit().putStringSet("reminded", all.takeLast(300).toSet()).apply()
    }
}

// 9 AM on a day, in milliseconds
private fun morningOf(day: LocalDate, zone: ZoneId): Long {
    return day.atTime(NUDGE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
}

// When a follow-up's next reminder comes after the one just sent: a bill reminded 2 days early gets one more on its
// day; everything else is done (loose plans are nudged once, then only when you ask for later)
fun nextAfterReminder(followUp: FollowUp, nowMillis: Long, zone: ZoneId): Long {
    val day = runCatching { LocalDate.parse(followUp.date) }.getOrNull() ?: return 0
    return if (followUp.kind == KIND_DUE && morningOf(day, zone) > nowMillis) morningOf(day, zone) else 0
}

// Return true if a waiting plan gets its "Still on?" reminder now: a new plan (not a change) that starts tomorrow, from
// 6 PM today
fun stillOnDue(plan: Suggestion, nowMillis: Long, zone: ZoneId): Boolean {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val dayZone = if (plan.allDay) ZoneId.of("UTC") else zone  // all-day plans are kept at midnight UTC
    val starts = Instant.ofEpochMilli(eventTimes(plan.toEvent()).first).atZone(dayZone)
    return plan.status == Suggestion.STATUS_WAITING && plan.change.isBlank() &&
        starts.toLocalDate() == now.toLocalDate().plusDays(1) && now.hour >= STILL_ON_HOUR
}

// Return true if the week ahead is due now: a Friday, at or after its time, not yet sent today
fun weekAheadDue(nowMillis: Long, zone: ZoneId, hour: Int, minute: Int, lastSent: String): Boolean {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val time = now.toLocalDate().atTime(hour, minute).atZone(zone)
    return now.dayOfWeek == DayOfWeek.FRIDAY && !now.isBefore(time) && lastSent != now.toLocalDate().toString()
}

// The week ahead's lines: each of the next 7 days that has events, like "Sat Nov 15: 9:00 AM Practice, 7:00 PM Dinner"
// (⚠ on events that overlap another), then each bill due in that time, like "Due Fri Nov 20: Phone bill $45"
fun weekAheadLines(events: List<CalendarEvent>, bills: List<FollowUp>, today: LocalDate, zone: ZoneId): List<String> {
    val lines = mutableListOf<String>()

    // One line per day that has events
    for (offset in 1L..7L) {
        val day = today.plusDays(offset)
        val onDay = eventsOnDay(events, day, zone).sortedBy { it.begin }

        // Days with nothing on are left out
        if (onDay.isEmpty()) {
            continue
        }
        val parts = onDay.map { event ->
            val time = if (event.allDay) "All day" else Instant.ofEpochMilli(event.begin).atZone(zone).format(CLOCK)
            val clash = !event.allDay && onDay.any { other ->
                other !== event && !other.allDay && other.begin < event.end && event.begin < other.end
            }
            "$time ${event.title.ifBlank { "(no title)" }}" + if (clash) " ⚠" else ""
        }
        lines.add("${day.format(DAY_NAME)}: ${parts.joinToString(", ")}")
    }
    val lastDay = today.plusDays(7)

    // Then the bills due in the next 7 days
    for (bill in bills.sortedBy { it.date }) {
        val day = runCatching { LocalDate.parse(bill.date) }.getOrNull() ?: continue

        // Only bills due from today through the next 7 days
        if (day >= today && day <= lastDay) {
            val amount = if (bill.amount.isNotBlank()) " ${bill.amount}" else ""  // only when known
            lines.add("Due ${day.format(DAY_NAME)}: ${bill.title}$amount")
        }
    }
    return lines
}

// The week ahead's title, like "Next week: 6 events"
fun weekAheadTitle(count: Int): String {
    // Pick the wording for none, one or several
    return when (count) {
        0 -> "Next week: nothing planned"
        1 -> "Next week: 1 event"
        else -> "Next week: $count events"
    }
}

// Create the two quiet channels once (Android keeps them after that)
private fun makeChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(FOLLOW_UPS_CHANNEL, "Follow-ups",
        NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Loose plans to pin down and bills coming due" })
    manager.createNotificationChannel(NotificationChannel(WEEK_AHEAD_CHANNEL, "Week ahead",
        NotificationManager.IMPORTANCE_LOW).apply { description = "A quiet look at next week, on Friday evenings" })
}

// A button that's handled in the background (snooze, drop, paid, tomorrow) for one follow-up
private fun followUpAction(context: Context, action: String, id: Long, slot: Int): PendingIntent {
    val intent = Intent(context, FollowUpReceiver::class.java).setAction(action).putExtra(EXTRA_FOLLOW_UP_ID, id)
    return PendingIntent.getBroadcast(context, (id * 4 + slot).toInt(), intent, FLAGS)
}

// Opening Datebook (for taps on the notification itself)
private fun openDatebook(context: Context, code: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(context, code, intent, FLAGS)
}

// The nudge for a loose plan: "Pin down lunch with Sam?" with Make a plan (opens the chat app), Not now (again in 3
// days) and Drop
private fun showLooseNudge(context: Context, loose: FollowUp) {
    val id = FOLLOW_UP_IDS + loose.id.toInt()
    val chat = context.packageManager.getLaunchIntentForPackage(loose.app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val makePlan = if (chat != null) PendingIntent.getActivity(context, id, chat, FLAGS) else openDatebook(context, id)
    val who = loose.conversation.ifBlank { loose.sender }
    val title = "Pin down ${loose.title.replaceFirstChar { it.lowercase() }}?"
    val notification = NotificationCompat.Builder(context, FOLLOW_UPS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText("From $who" + followUpDay(loose).let { if (it.isBlank()) "" else " · around $it" })
        .setContentIntent(makePlan)
        .setAutoCancel(true)
        .addAction(0, "Make a plan", makePlan)
        .addAction(0, "Not now", followUpAction(context, ACTION_SNOOZE, loose.id, 0))
        .addAction(0, "Drop", followUpAction(context, ACTION_DROP, loose.id, 1))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

// The reminder for a bill: "Phone bill · $45" due in 2 days or today, with Mark paid and Remind me tomorrow
private fun showBillReminder(context: Context, bill: FollowUp, today: LocalDate) {
    val id = FOLLOW_UP_IDS + bill.id.toInt()
    val dueToday = bill.date == today.toString()
    val title = if (bill.amount.isNotBlank()) "${bill.title} · ${bill.amount}" else bill.title
    val notification = NotificationCompat.Builder(context, FOLLOW_UPS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText(if (dueToday) "Due today" else "Due ${followUpDay(bill)}")
        .setContentIntent(openDatebook(context, id))
        .setAutoCancel(true)
        .addAction(0, "Mark paid", followUpAction(context, ACTION_PAID, bill.id, 2))
        .addAction(0, "Remind me tomorrow", followUpAction(context, ACTION_TOMORROW, bill.id, 3))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

// The Friday week ahead: a quiet notification with Claude's paragraph about next week; tapping it opens the Week
// ahead screen
private fun showWeekAhead(context: Context, title: String, brief: String) {
    val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_ROUTE, ROUTE_WEEK)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val notification = NotificationCompat.Builder(context, WEEK_AHEAD_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText(brief)
        .setStyle(NotificationCompat.BigTextStyle().bigText(brief))
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, WEEK_AHEAD_ID, open, FLAGS))
        .build()
    NotificationManagerCompat.from(context).notify(WEEK_AHEAD_ID, notification)
}

// Claude's paragraph about next week, or null without a key or when Claude can't be reached
private suspend fun weekBrief(graph: AppGraph, lines: List<String>, loosePlans: List<FollowUp>): String? {
    val key = graph.keyStore.load()

    // Without a key there's no one to write it
    if (key.isNullOrBlank()) {
        return null
    }
    val prompt = briefPrompt(lines, loosePlans)
    return runCatching { withContext(Dispatchers.IO) { writeBrief(makeClient(key), graph.modelChoice.get(), prompt) } }
        .getOrNull()?.ifBlank { null }
}

// Send whatever is due now (or everything, when forced from the debug command, or just the week ahead with weekOnly):
// follow-up nudges and reminders, the "Still on?" reminders, and the Friday week ahead; returns a log line for each
// (never message text)
suspend fun runFollowUps(context: Context, nowMillis: Long, force: Boolean = false, weekOnly: Boolean = false):
    List<String> {
    val graph = AppGraph.get(context)
    val dao = graph.dao
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val lines = mutableListOf<String>()

    // Without notification permission nothing can be shown; it all waits on Home
    if (!canNotify(context)) {
        return listOf("follow-ups: can't show notifications")
    }
    makeChannels(context)
    val all = dao.openFollowUps(KIND_LOOSE) + dao.openFollowUps(KIND_DUE)
    val due = if (weekOnly) emptyList() else if (force) all else dao.followUpsDue(nowMillis)

    // Nudge or remind each follow-up that's due, then book its next one
    for (followUp in due) {
        // Loose plans get a nudge, bills a reminder
        if (followUp.kind == KIND_LOOSE) {
            showLooseNudge(context, followUp)
        } else {
            showBillReminder(context, followUp, today)
        }
        dao.updateFollowUp(followUp.copy(nudges = followUp.nudges + 1,
            nextNudge = nextAfterReminder(followUp, nowMillis, zone)))
        lines.add("follow-up: ${followUp.kind} reminded")
    }
    val record = StillOnRecord(context)

    val waiting = if (weekOnly) emptyList() else dao.suggestionsWithStatus(Suggestion.STATUS_WAITING)

    // "Still on?" for waiting plans that start tomorrow
    for (plan in waiting) {
        val tomorrow = stillOnDue(plan, nowMillis, zone) ||
            (force && plan.change.isBlank() && plan.start.startsWith(today.plusDays(1).toString()))

        // Only once per plan and start (the debug command sends it again)
        if (tomorrow && (force || !record.reminded(plan))) {
            graph.notifier.showStillOn(plan)
            record.remember(plan)
            lines.add("still on? sent")
        }
    }
    val settings = WeekAheadSettings(context)
    val weekDue = weekAheadDue(nowMillis, zone, settings.hour(), settings.minute(), settings.lastSent())

    // The week ahead on Friday evenings, while it's on
    if (force || (settings.isOn() && weekDue)) {
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = calendarEvents(context, from, to, graph.calendarSwitches.switchedOff())
        val week = (1L..7L).flatMap { eventsOnDay(events, today.plusDays(it), zone) }.distinct()
        val weekLines = weekAheadLines(events, dao.openFollowUps(KIND_DUE), today, zone)
        val brief = weekBrief(graph, weekLines, dao.openFollowUps(KIND_LOOSE))
            ?: weekLines.joinToString("\n").ifBlank { "Nothing on your calendar next week." }  // the plain list
        settings.setBrief(brief)
        showWeekAhead(context, weekAheadTitle(week.size), brief)
        settings.setLastSent(today.toString())
        lines.add("week ahead sent (${week.size} events)")
    }
    return lines
}

// Handles the buttons on follow-up notifications: Not now (again in 3 days), Drop, Mark paid, Remind me tomorrow
class FollowUpReceiver : BroadcastReceiver() {
    // Update the follow-up and clear its notification
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_FOLLOW_UP_ID, -1)
        val action = intent.action
        val pending = goAsync()
        NotificationManagerCompat.from(context).cancel(FOLLOW_UP_IDS + id.toInt())
        CoroutineScope(Dispatchers.IO).launch {
            // Always tell Android the work is done, even if the follow-up is gone
            try {
                val dao = AppGraph.get(context).dao
                val followUp = dao.followUp(id) ?: return@launch
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)

                // Do what the button says
                when (action) {
                    ACTION_SNOOZE -> dao.updateFollowUp(followUp.copy(nextNudge = morningOf(today.plusDays(SNOOZE_DAYS),
                        zone)))
                    ACTION_TOMORROW -> dao.updateFollowUp(followUp.copy(nextNudge = morningOf(today.plusDays(1), zone)))
                    ACTION_DROP -> dao.setFollowUpStatus(id, FollowUp.DROPPED)
                    ACTION_PAID -> dao.setFollowUpStatus(id, FollowUp.DONE)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

// Runs the follow-up check every 15 minutes
class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Send what's due and log it
    override suspend fun doWork(): Result {
        runFollowUps(applicationContext, System.currentTimeMillis()).forEach { Log.i(TAG, it) }  // one line each
        return Result.success()
    }
}

// Book the follow-up check every 15 minutes, keeping the schedule if it's already booked
fun scheduleFollowUps(context: Context) {
    val request = PeriodicWorkRequestBuilder<FollowUpWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

// Leave-by alerts: for each timed calendar event with a place, work out the travel time and, 10 minutes before you
// need to leave (travel time plus 5 spare minutes before the start), post "Leave by 6:37 PM" with Open in Maps and
// buttons to switch the travel mode
package io.github.adenkushnir.datebook.leaveby
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.flights.isFlightTitle
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.mapsIntent
import io.github.adenkushnir.datebook.places.travelMinutes
import io.github.adenkushnir.datebook.places.travelMode
import io.github.adenkushnir.datebook.prompts.canNotify
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
const val LEAVE_BY_CHANNEL = "leave_by"
const val BUFFER_MINUTES = 5
const val HEADS_UP_MINUTES = 10
private const val MINUTE = 60_000L
private const val LOOK_AHEAD_MILLIS = 4 * 60 * MINUTE
private const val PLAN_WORK = "datebook-leave-by-plan"
private const val ALERT_WORK = "datebook-leave-by-"
private const val EXTRA_KEY = "key"
private const val EXTRA_EVENT = "event"
private const val EXTRA_BEGIN = "begin"
private const val EXTRA_MODE = "mode"
private const val ACTION_MODE = "io.github.adenkushnir.datebook.LEAVE_BY_MODE"
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// The key for one time of one event, like "1794600000000|dinner", so a plan on its card and the same event on the
// calendar share a travel mode
fun eventKey(title: String, begin: Long): String {
    return "$begin|${title.trim().lowercase()}"
}

// Remembers each event's travel mode, its last travel time, when its alert is booked, and whether it was sent; entries
// are dropped a day after the event
class LeaveByStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-leave-by", Context.MODE_PRIVATE)

    // Return the travel mode chosen for an event, or null to use the default
    fun mode(key: String): String? {
        return prefs.getString("mode:$key", null)
    }

    // Choose an event's travel mode, forgetting its old travel time
    fun setMode(key: String, mode: String) {
        prefs.edit().putString("mode:$key", mode).remove("minutes:$key").apply()
    }

    // Return the last travel time worked out for an event, in minutes, or null
    fun minutes(key: String): Int? {
        return if (prefs.contains("minutes:$key")) prefs.getInt("minutes:$key", 0) else null
    }

    // Remember an event's travel time
    fun setMinutes(key: String, minutes: Int) {
        prefs.edit().putInt("minutes:$key", minutes).apply()
    }

    // Return when an event's alert is booked for, or null
    fun bookedFor(key: String): Long? {
        return if (prefs.contains("booked:$key")) prefs.getLong("booked:$key", 0) else null
    }

    // Remember when an event's alert is booked for
    fun setBookedFor(key: String, millis: Long) {
        prefs.edit().putLong("booked:$key", millis).apply()
    }

    // Return true once an event's alert was sent
    fun alerted(key: String): Boolean {
        return prefs.getBoolean("alerted:$key", false)
    }

    // Remember that an event's alert was sent
    fun setAlerted(key: String) {
        prefs.edit().putBoolean("alerted:$key", true).apply()
    }

    // Drop what's remembered about events that started over a day ago
    fun prune(nowMillis: Long) {
        val editor = prefs.edit()

        // Check each remembered entry's event time
        for (name in prefs.all.keys) {
            val begin = name.substringAfter(":").substringBefore("|").toLongOrNull() ?: continue

            // Drop entries for events that started over a day ago
            if (begin < nowMillis - 24 * 60 * MINUTE) {
                editor.remove(name)
            }
        }
        editor.apply()
    }
}

// When to leave: the start, less the travel time and 5 spare minutes
fun leaveTime(begin: Long, minutes: Int): Long {
    return begin - (minutes + BUFFER_MINUTES) * MINUTE
}

// When to send the alert: 10 minutes before the leave time
fun alertTime(begin: Long, minutes: Int): Long {
    return leaveTime(begin, minutes) - HEADS_UP_MINUTES * MINUTE
}

// The events that get leave-by alerts: timed ones with a place, starting within the next 4 hours, except flights
fun leaveByCandidates(events: List<CalendarEvent>, nowMillis: Long): List<CalendarEvent> {
    return events.filter {
        !it.allDay && it.location.isNotBlank() && it.begin > nowMillis && it.begin <= nowMillis + LOOK_AHEAD_MILLIS &&
            !isFlightTitle(it.title)
    }
}

// The trip in words, like "18 min drive", "35 min by transit", "12 min walk" or "20 min bike ride"
fun tripText(minutes: Int, mode: String): String {
    // Pick the words for the mode
    return when (travelMode(mode).id) {
        TRAVEL_TRANSIT -> "$minutes min by transit"
        TRAVEL_WALK -> "$minutes min walk"
        TRAVEL_BIKE -> "$minutes min bike ride"
        else -> "$minutes min drive"
    }
}

// The alert's title, like "Leave by 6:37 PM"
fun leaveByTitle(begin: Long, minutes: Int, zone: ZoneId): String {
    return "Leave by ${Instant.ofEpochMilli(leaveTime(begin, minutes)).atZone(zone).format(CLOCK)}"
}

// The two other modes offered as buttons on the alert (there's room for two next to Open in Maps)
fun otherModes(mode: String): List<String> {
    return listOf(TRAVEL_DRIVE, TRAVEL_TRANSIT, TRAVEL_WALK).filter { it != travelMode(mode).id }.take(2)
}

// Work out an event's travel time now, from where the phone is (or home), by its mode
private fun freshMinutes(context: Context, event: CalendarEvent, mode: String): Int? {
    val graph = AppGraph.get(context)
    return travelMinutes(context, graph.mapsKeyStore.load(), graph.travelSettings.home(), event.location, mode,
        Instant.ofEpochMilli(event.begin))
}

// Post (or update) an event's leave-by alert
fun showLeaveBy(context: Context, event: CalendarEvent, minutes: Int, mode: String) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(LEAVE_BY_CHANNEL, "Leave-by alerts", NotificationManager.IMPORTANCE_HIGH)
    channel.description = "When to leave for events with a place"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val key = eventKey(event.title, event.begin)
    val id = notificationId(key)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val openHome = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val openMaps = mapsIntent(event.location, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val text = "${event.title.ifBlank { "Event" }} · ${tripText(minutes, mode)}"
    val builder = NotificationCompat.Builder(context, LEAVE_BY_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_teal))
        .setContentTitle(leaveByTitle(event.begin, minutes, ZoneId.systemDefault()))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${event.location}"))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, openHome, flags))
        .addAction(0, "Open in Maps", PendingIntent.getActivity(context, id + 1, openMaps, flags))

    // Offer the other travel modes as buttons
    for ((i, other) in otherModes(mode).withIndex()) {
        val change = Intent(context, LeaveByModeReceiver::class.java).setAction(ACTION_MODE)
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_EVENT, event.eventId).putExtra(EXTRA_BEGIN, event.begin)
            .putExtra(EXTRA_MODE, other)
        builder.addAction(0, travelMode(other).label, PendingIntent.getBroadcast(context, id + 2 + i, change, flags))
    }
    NotificationManagerCompat.from(context).notify(id, builder.build())
}

// A notification id for an event's alert, far above suggestion ids and the summary (each alert uses id to id + 3 for
// its buttons)
private fun notificationId(key: String): Int {
    return 1_100_000_000 + (key.hashCode() and 0x0FFFFFFF) % 50_000_000 * 4
}

// Find an event again on the switched-on calendars by its id and start (null if it was moved or deleted)
private fun findEvent(context: Context, eventId: Long, begin: Long): CalendarEvent? {
    val switchedOff = AppGraph.get(context).calendarSwitches.switchedOff()
    return calendarEvents(context, begin - MINUTE, begin + MINUTE, switchedOff)
        .firstOrNull { it.eventId == eventId && it.begin == begin }
}

// Look at the next 4 hours of events and book each alert for 10 minutes before its leave time, working out travel
// times once per event (and again if its mode changes); runs every 15 minutes and when the app opens
suspend fun planLeaveBy(context: Context) {
    val graph = AppGraph.get(context)
    val store = LeaveByStore(context)
    val now = System.currentTimeMillis()

    // Without a Maps key there are no travel times
    if (graph.mapsKeyStore.load().isNullOrBlank()) {
        return
    }
    store.prune(now)
    val events = calendarEvents(context, now, now + LOOK_AHEAD_MILLIS, graph.calendarSwitches.switchedOff())

    // Book an alert for each event with a place that hasn't had one
    for (event in leaveByCandidates(events, now)) {
        val key = eventKey(event.title, event.begin)

        // Skip events already alerted
        if (store.alerted(key)) {
            continue
        }
        val mode = store.mode(key) ?: graph.travelSettings.mode()
        val minutes = store.minutes(key) ?: freshMinutes(context, event, mode)?.also { store.setMinutes(key, it) }

        // Without a travel time there's nothing to book
        if (minutes == null) {
            continue
        }
        val alertAt = alertTime(event.begin, minutes)

        // Only re-book when the alert time changed
        if (store.bookedFor(key) != alertAt) {
            val data = workDataOf(EXTRA_KEY to key, EXTRA_EVENT to event.eventId, EXTRA_BEGIN to event.begin)
            val request = OneTimeWorkRequestBuilder<LeaveByAlertWorker>().setInputData(data)
                .setInitialDelay((alertAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ALERT_WORK + key, ExistingWorkPolicy.REPLACE, request)
            store.setBookedFor(key, alertAt)
        }
    }
}

// Every 15 minutes: book leave-by alerts for the coming events
class LeaveByPlanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Plan the alerts
    override suspend fun doWork(): Result {
        planLeaveBy(applicationContext)
        return Result.success()
    }
}

// At alert time: check the event is still there and not started, work out the travel time again from where you are
// now, and post the alert
class LeaveByAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Send one event's alert
    override suspend fun doWork(): Result {
        val store = LeaveByStore(applicationContext)
        val key = inputData.getString(EXTRA_KEY) ?: return Result.success()
        val event = findEvent(applicationContext, inputData.getLong(EXTRA_EVENT, 0), inputData.getLong(EXTRA_BEGIN, 0))

        // Skip events already alerted, moved, deleted or started
        if (store.alerted(key) || event == null || event.begin <= System.currentTimeMillis()) {
            return Result.success()
        }
        val mode = store.mode(key) ?: AppGraph.get(applicationContext).travelSettings.mode()
        val minutes = freshMinutes(applicationContext, event, mode) ?: store.minutes(key) ?: return Result.success()
        store.setMinutes(key, minutes)
        showLeaveBy(applicationContext, event, minutes, mode)
        store.setAlerted(key)
        Log.i("Datebook", "leave-by alert: ${event.title} · ${tripText(minutes, mode)}")
        return Result.success()
    }
}

// Handles the travel-mode buttons on an alert: saves the mode for that event and updates the alert with the new time
class LeaveByModeReceiver : BroadcastReceiver() {
    // Switch the mode in the background
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val mode = intent.getStringExtra(EXTRA_MODE) ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT, 0)
        val begin = intent.getLongExtra(EXTRA_BEGIN, 0)
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Always tell Android the work is done, even if updating fails
            try {
                LeaveByStore(appContext).setMode(key, mode)
                val event = findEvent(appContext, eventId, begin) ?: return@launch
                val minutes = freshMinutes(appContext, event, mode) ?: return@launch
                LeaveByStore(appContext).setMinutes(key, minutes)
                showLeaveBy(appContext, event, minutes, mode)
            } finally {
                pending.finish()
            }
        }
    }
}

// Start the 15-minute planning job if it isn't running yet
fun scheduleLeaveBy(context: Context) {
    val request = PeriodicWorkRequestBuilder<LeaveByPlanWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PLAN_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

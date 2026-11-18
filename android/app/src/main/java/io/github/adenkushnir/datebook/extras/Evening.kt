// The evening heads-ups about tomorrow: at 7 PM a weather warning for outdoor plans (rain or snow likely, or cold),
// and at 8 PM what to bring, read from the "Bring:" line Datebook puts in an event's notes
package io.github.adenkushnir.datebook.extras
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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.deliberate.PAUSE_EVENING
import io.github.adenkushnir.datebook.nestor.postNotification
import io.github.adenkushnir.datebook.deliberate.Rule
import io.github.adenkushnir.datebook.deliberate.effectsFor
import io.github.adenkushnir.datebook.deliberate.mergeBring
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.summary.delayUntilNext
import io.github.adenkushnir.datebook.summary.eventsOnDay
import io.github.adenkushnir.datebook.weather.Forecast
import io.github.adenkushnir.datebook.weather.RAIN_LIKELY
import io.github.adenkushnir.datebook.weather.SKY_SNOW
import io.github.adenkushnir.datebook.weather.homeForecast
import io.github.adenkushnir.datebook.weather.skyOf
import io.github.adenkushnir.datebook.weather.usesFahrenheit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
const val EVENING_CHANNEL = "evening"
const val BRING_LABEL = "Bring: "
private const val WEATHER_HOUR = 19
private const val BRING_HOUR = 20
private const val WEATHER_WORK = "datebook-evening-weather"
private const val BRING_WORK = "datebook-evening-bring"
private const val WEATHER_NOTIFICATION = 1_000_000_010  // next to the morning summary, far above suggestion ids
private const val BRING_NOTIFICATION = 1_000_000_011
private const val COLD_F = 40
private const val COLD_C = 4
private const val HOUR_MILLIS = 60 * 60 * 1000L
private const val ALL_DAY_FROM = 9  // an all-day plan's weather is checked from 9 AM to 6 PM
private const val ALL_DAY_UNTIL = 18
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val OUTDOOR_WORDS = Regex("\\b(parks?|fields?|beach|stadium|trails?|hike|hiking|picnic|gardens?|lake|golf|" +
    "outdoors?|outside|campsite|camping|zoo|fair|festival|bbq|barbecue|playground|boardwalk)\\b",
    RegexOption.IGNORE_CASE)

// The "Bring: cleats, $20" line for a plan's notes (empty when there's nothing to bring)
fun bringLine(bring: String): String {
    return if (bring.isBlank()) "" else BRING_LABEL + bring.trim()
}

// What to bring, read from an event's notes: "Bring: cleats, $20" gives "cleats, $20" (empty without that line)
fun bringOf(notes: String): String {
    return notes.lines().firstOrNull { it.trim().startsWith(BRING_LABEL) }?.trim()?.removePrefix(BRING_LABEL)?.trim()
        .orEmpty()
}

// Return true if an event's title or place sounds like it's outdoors (a park, a field, a beach...)
fun soundsOutdoor(title: String, location: String): Boolean {
    return OUTDOOR_WORDS.containsMatchIn(title) || OUTDOOR_WORDS.containsMatchIn(location)
}

// The day an event is on: an all-day event's own date (kept at midnight UTC), else its start in the phone's zone
private fun dayOf(event: CalendarEvent, zone: ZoneId): LocalDate {
    val offset = if (event.allDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(event.begin).atZone(offset).toLocalDate()
}

// Return true if a calendar event is outdoors: its title or place sounds like it, or it's a plan Claude marked as
// outdoor (same title, same day)
fun isOutdoor(event: CalendarEvent, outdoorPlans: List<Suggestion>, zone: ZoneId): Boolean {
    val day = dayOf(event, zone).toString()
    val marked = outdoorPlans.any { it.title.equals(event.title, ignoreCase = true) && it.start.take(10) == day }
    return marked || soundsOutdoor(event.title, event.location)
}

// The event's name in a heads-up: "Soccer at 6:00 PM", or just the title for an all-day event
private fun nameOf(event: CalendarEvent, zone: ZoneId): String {
    val title = event.title.ifBlank { "Event" }
    return if (event.allDay) title else "$title at ${Instant.ofEpochMilli(event.begin).atZone(zone).format(CLOCK)}"
}

// The weather warning for an outdoor event, like "Soccer at 6:00 PM: rain likely (70%), cold (36°)", or null when
// the weather looks fine (or isn't known); rain or snow counts from 50% during the event, cold below 40°F (4°C) at
// its start
fun weatherWarning(event: CalendarEvent, forecast: Forecast, fahrenheit: Boolean, zone: ZoneId): String? {
    val day = dayOf(event, zone)
    val from = if (event.allDay) day.atTime(ALL_DAY_FROM, 0).atZone(zone).toInstant().toEpochMilli() else event.begin
    val until = when {
        event.allDay -> day.atTime(ALL_DAY_UNTIL, 0).atZone(zone).toInstant().toEpochMilli()
        event.end > event.begin -> event.end
        else -> event.begin + HOUR_MILLIS  // no end: the first hour
    }
    val hours = forecast.hours.filter { it.time > from - HOUR_MILLIS && it.time < until }
    val wet = hours.maxByOrNull { it.rain }?.takeIf { it.rain >= RAIN_LIKELY }
    val start = hours.firstOrNull()
    val parts = mutableListOf<String>()

    // Rain or snow likely during the event
    if (wet != null) {
        val what = if (skyOf(wet.code, true) == SKY_SNOW) "snow" else "rain"
        parts.add("$what likely (${wet.rain}%)")
    }

    val coldBelow = if (fahrenheit) COLD_F else COLD_C

    // Cold at the start
    if (start != null && start.temp < coldBelow) {
        parts.add("cold (${start.temp}°)")
    }
    return if (parts.isEmpty()) null else "${nameOf(event, zone)}: ${parts.joinToString(", ")}"
}

// One bring line per event, like "Soccer at 6:00 PM: cleats, $20", for the events whose notes (or Deliberate's bring
// rules) say what to bring; events a rule keeps quiet are left out
fun bringLines(events: List<CalendarEvent>, zone: ZoneId, rules: List<Rule> = emptyList()): List<String> {
    return events.mapNotNull { event ->
        val effects = effectsFor(rules, event.title, event.location)
        val bring = mergeBring(bringOf(event.notes), effects.bring)
        if (bring.isBlank() || effects.quiet) null else "${nameOf(event, zone)}: $bring"
    }
}

// Tomorrow's events on the calendars that are switched on
private fun tomorrowsEvents(context: Context, zone: ZoneId): List<CalendarEvent> {
    val tomorrow = LocalDate.now(zone).plusDays(1)
    val from = tomorrow.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()  // a day early for all-day events
    val to = tomorrow.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val switchedOff = AppGraph.get(context).calendarSwitches.switchedOff()
    return eventsOnDay(calendarEvents(context, from, to, switchedOff), tomorrow, zone)
}

// Post a quiet heads-up with one line per event (nothing when there are no lines or no permission to notify)
private fun showHeadsUp(context: Context, id: Int, title: String, lines: List<String>) {
    // Nothing to say, or no way to say it
    if (lines.isEmpty() || !canNotify(context)) {
        return
    }
    val channel = NotificationChannel(EVENING_CHANNEL, "Evening heads-ups", NotificationManager.IMPORTANCE_LOW)
    channel.description = "The evening before: bad weather for outdoor plans, and what to bring"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val style = NotificationCompat.InboxStyle().setBigContentTitle(title)

    // One line per event
    for (line in lines) {
        style.addLine(line)
    }
    val openHome = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, EVENING_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText(lines.first())
        .setStyle(style)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, openHome, flags))
        .build()
    postNotification(context, id, notification)
}

// Warn about tomorrow's outdoor plans when rain or snow is likely or it's cold
suspend fun showWeatherHeadsUp(context: Context) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val graph = AppGraph.get(context)

    // Paused by Deliberate
    if (graph.deliberateSettings.paused(PAUSE_EVENING, now)) {
        return
    }
    val rules = graph.deliberateDao.rules()
    val outdoorPlans = graph.dao.suggestionsWithStatus(Suggestion.STATUS_ADDED).filter { it.outdoor }
    val outdoor = tomorrowsEvents(context, zone).filter { isOutdoor(it, outdoorPlans, zone) }
        .filter { !effectsFor(rules, it.title, it.location).quiet }

    // No outdoor plans tomorrow: no need for the forecast
    if (outdoor.isEmpty()) {
        return
    }
    val forecast = withContext(Dispatchers.IO) { homeForecast(context, now, zone) } ?: return  // offline: skip
    val lines = outdoor.mapNotNull { weatherWarning(it, forecast, usesFahrenheit(), zone) }
    showHeadsUp(context, WEATHER_NOTIFICATION, "Weather for tomorrow's plans", lines)
}

// Remind what to bring to tomorrow's plans (from their notes and Deliberate's bring rules), unless Deliberate paused
// the evening heads-ups
suspend fun showBringHeadsUp(context: Context) {
    val graph = AppGraph.get(context)

    // Paused by Deliberate
    if (graph.deliberateSettings.paused(PAUSE_EVENING)) {
        return
    }
    val zone = ZoneId.systemDefault()
    showHeadsUp(context, BRING_NOTIFICATION, "What to bring tomorrow", bringLines(tomorrowsEvents(context, zone), zone,
        graph.deliberateDao.rules()))
}

// Runs at 7 PM: the weather heads-up, then books tomorrow's
class WeatherHeadsUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Warn, then book the next one
    override suspend fun doWork(): Result {
        showWeatherHeadsUp(applicationContext)
        book(applicationContext, WEATHER_WORK, WEATHER_HOUR, OneTimeWorkRequestBuilder<WeatherHeadsUpWorker>(),
            ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

// Runs at 8 PM: the bring reminder, then books tomorrow's
class BringHeadsUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Remind, then book the next one
    override suspend fun doWork(): Result {
        showBringHeadsUp(applicationContext)
        book(applicationContext, BRING_WORK, BRING_HOUR, OneTimeWorkRequestBuilder<BringHeadsUpWorker>(),
            ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

// Book a job for the next time the clock reaches an hour
private fun book(context: Context, name: String, hour: Int, builder: OneTimeWorkRequest.Builder,
    policy: ExistingWorkPolicy) {
    val delay = delayUntilNext(ZonedDateTime.now(), hour, 0)
    val request = builder.setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
}

// Book the evening heads-ups if they aren't booked yet (called when the app opens)
fun scheduleEvening(context: Context) {
    book(context, WEATHER_WORK, WEATHER_HOUR, OneTimeWorkRequestBuilder<WeatherHeadsUpWorker>(),
        ExistingWorkPolicy.KEEP)
    book(context, BRING_WORK, BRING_HOUR, OneTimeWorkRequestBuilder<BringHeadsUpWorker>(), ExistingWorkPolicy.KEEP)
}

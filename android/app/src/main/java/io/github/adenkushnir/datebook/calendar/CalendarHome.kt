// Home's view of your calendar: the events on your switched-on calendars (or, without calendar access, the plans you
// added through Datebook), what's Next up (or happening now, with what's after it), and the next 3 days that have
// something on them, grouped by day; plans you added through Datebook are matched to their calendar event, so they
// show once, with who they came from
package io.github.adenkushnir.datebook.calendar
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.eventTimes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
const val HOME_DAYS = 3  // days with something on them
const val LOOK_AHEAD_DAYS = 31L  // how far ahead Home looks for them
private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

// One event on Home: its title, start and end in milliseconds (all-day ones at midnight UTC, like the calendar keeps
// them), place, its calendar's colour (0 when it has none), its calendar event (0 for a Datebook plan without
// calendar access), and the Datebook plan it came from, if any
data class HomeEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String = "",
    val color: Int = 0,
    val eventId: Long = 0,
    val source: Suggestion? = null,
)

// Next up: the event happening now (with the one after it) or the next one
data class NextUp(val event: HomeEvent, val happening: Boolean, val then: HomeEvent? = null)

// One day of Coming up, like "Tomorrow" and its events
data class DayGroup(val label: String, val events: List<HomeEvent>)

// What Home shows from the calendar: Next up (null when nothing is ahead) and the next 3 days with events
data class Agenda(val nextUp: NextUp?, val days: List<DayGroup>)

// Turn calendar events into Home events, matching each to the Datebook plan you added for it (same title, starting
// within a day of it, the closest one), so it shows who it came from
fun fromCalendar(events: List<CalendarEvent>, added: List<Suggestion>): List<HomeEvent> {
    val plans = added.filter { it.change.isBlank() }.toMutableList()  // changes you opened aren't plans
    return events.map { event ->
        val source = plans.filter { it.title.trim().equals(event.title.trim(), ignoreCase = true) }
            .filter { abs(eventTimes(it.toEvent()).first - event.begin) < DAY_MILLIS }
            .minByOrNull { abs(eventTimes(it.toEvent()).first - event.begin) }
        source?.let { plans.remove(it) }  // each plan matches one event
        HomeEvent(event.title, event.begin, event.end, event.allDay, event.location, event.color, event.eventId, source)
    }
}

// Without calendar access, Home falls back to the plans you added through Datebook
fun fromPlans(added: List<Suggestion>): List<HomeEvent> {
    return added.filter { it.change.isBlank() }.map { plan ->
        val (begin, end) = eventTimes(plan.toEvent())
        HomeEvent(plan.title, begin, end, plan.allDay, plan.location, source = plan)
    }
}

// The day an event is on (all-day events are kept at midnight UTC)
fun dayOf(event: HomeEvent, zone: ZoneId): LocalDate {
    val zoneOfDay = if (event.allDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(event.begin).atZone(zoneOfDay).toLocalDate()
}

// Return true if an event is over (an all-day event lasts until the end of its last day)
private fun isOver(event: HomeEvent, nowMillis: Long, zone: ZoneId): Boolean {
    // All-day events end at midnight UTC after their last day
    if (event.allDay) {
        val lastDay = Instant.ofEpochMilli(event.end - 1).atZone(ZoneOffset.UTC).toLocalDate()
        return lastDay < Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    }
    return event.end <= nowMillis
}

// Work out Home from the events: Next up is the timed event happening now (the one ending soonest, with the next one
// under it) or else the next timed event; Coming up is the next 3 days that have something on them (empty days don't
// count), with every event on them, Next up's included and all-day events first
fun agendaOf(events: List<HomeEvent>, nowMillis: Long, zone: ZoneId): Agenda {
    val ahead = events.filter { !isOver(it, nowMillis, zone) }.sortedWith(compareBy({ it.begin }, { it.title }))
    val timed = ahead.filter { !it.allDay }
    val happening = timed.filter { it.begin <= nowMillis }.minByOrNull { it.end }
    val next = timed.firstOrNull { it.begin > nowMillis }
    val nextUp = if (happening != null) NextUp(happening, true, next) else next?.let { NextUp(it, false) }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ahead.groupBy { maxOf(dayOf(it, zone), today) }  // a multi-day event that's still on shows today
        .toSortedMap()
        .entries.take(HOME_DAYS)
        .map { (day, list) -> DayGroup(dayLabel(day, today), list.sortedWith(compareBy({ !it.allDay }, { it.begin }))) }
    return Agenda(nextUp, days)
}

// The heading for a day in Coming up: "Today", "Tomorrow", or like "Mon Nov 16"
fun dayLabel(day: LocalDate, today: LocalDate): String {
    // Pick the words for the day
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DAY_NAME)
    }
}

// An event's time in a Coming up row: "7:00 PM", or "All day"
fun timeText(event: HomeEvent, zone: ZoneId): String {
    return if (event.allDay) "All day" else Instant.ofEpochMilli(event.begin).atZone(zone).format(CLOCK)
}

// When and where an event is, for Next up: "Fri Nov 13, 7:00 PM · Joe's" (or "Fri Nov 13 · All day")
fun whenWhere(event: HomeEvent, zone: ZoneId): String {
    val time = if (event.allDay) "${dayOf(event, zone).format(DAY_NAME)} · All day"
        else Instant.ofEpochMilli(event.begin).atZone(zone).format(WHEN)
    return if (event.location.isBlank()) time else "$time · ${event.location}"
}

// The end time of an event happening now, like "8:00 PM"
fun endText(event: HomeEvent, zone: ZoneId): String {
    return Instant.ofEpochMilli(event.end).atZone(zone).format(CLOCK)
}

// A calendar event as a plan for its card: the Datebook plan it came from (so its extras like flight status and trains
// still show) with the calendar's current title, time and place, or a plain plan for any other event; marked as a
// calendar event, never saved
fun planOf(event: HomeEvent, zone: ZoneId): Suggestion {
    val start = if (event.allDay) dayOf(event, zone).toString()
        else Instant.ofEpochMilli(event.begin).atZone(zone).format(LOCAL_MINUTES)
    val end = if (event.allDay) "" else Instant.ofEpochMilli(event.end).atZone(zone).format(LOCAL_MINUTES)
    val base = event.source ?: Suggestion(sender = "", app = "", title = event.title, start = start, end = end,
        allDay = event.allDay, location = "", repeat = "", timezone = "", phoneTimezone = zone.id, createdAt = 0)
    return base.copy(title = event.title, start = start, end = end, allDay = event.allDay, timezone = "",
        phoneTimezone = zone.id, location = event.location.ifBlank { base.location }, change = "", clash = "",
        status = Suggestion.STATUS_CALENDAR, calendarEventId = event.eventId)
}

// Read the events Home needs, from a day ago (to catch ones still going) through the next month, on the calendars
// that are switched on (call it off the main thread)
fun homeCalendarEvents(context: Context, nowMillis: Long, switchedOff: Set<Long>): List<CalendarEvent> {
    return calendarEvents(context, nowMillis - DAY_MILLIS, nowMillis + LOOK_AHEAD_DAYS * DAY_MILLIS, switchedOff)
}

// A signal each time anything in the phone's calendars changes (an event added, edited, moved or deleted), while
// it's being watched
fun calendarChanges(context: Context): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        // Pass each change on
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
}

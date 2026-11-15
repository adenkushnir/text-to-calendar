// Home's Today card: everything on today in order (calendar events, reminders, and assignments and bills due today),
// which rows are over, the event on now or next (lit, with its countdown), and how long until something starts
package io.github.adenkushnir.datebook.today
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.dayOf
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.repeatLabel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
const val TODAY_EVENT = "event"
const val TODAY_REMINDER = "reminder"
const val TODAY_ASSIGNMENT = "assignment"
const val TODAY_BILL = "bill"
private const val MINUTE_MILLIS = 60_000L

// One row of Today: its title, when it starts (null for all-day events, bills and assignments without a time) and
// ends (events only), a second line, what kind of row it is, its calendar's colour, and the event or follow-up
// behind it
data class TodayItem(
    val title: String,
    val at: Long?,
    val end: Long?,
    val detail: String,
    val kind: String,
    val color: Int = 0,
    val event: HomeEvent? = null,
    val followUp: FollowUp? = null,
)

// Today: its rows in order (rows without a time first), and the lit one: the event on now, or else the next event
data class TodayList(val items: List<TodayItem> = emptyList(), val lit: TodayItem? = null)

// Return true if a row is over: an event once it ends, a reminder or assignment once its time passes; rows without a
// time last all day
fun isPast(item: TodayItem, nowMillis: Long): Boolean {
    val over = item.end ?: item.at ?: return false
    return over <= nowMillis
}

// Return true once every row of today is over (or nothing was on)
fun nothingLeft(today: TodayList, nowMillis: Long): Boolean {
    return today.items.all { isPast(it, nowMillis) }
}

// Work out Today from Home's events and the open follow-ups: events on today (all-day ones on each of their days),
// reminders going off today, and assignments and bills due today; the lit row is the timed event on now (the one
// ending soonest) or else the next timed event today
fun todayOf(events: List<HomeEvent>, followUps: List<FollowUp>, nowMillis: Long, zone: ZoneId): TodayList {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val rows = mutableListOf<TodayItem>()

    // Each calendar event that's on today
    for (event in events) {
        // All-day events are kept at midnight UTC, so their days are read in UTC
        if (event.allDay) {
            val lastDay = Instant.ofEpochMilli(maxOf(event.end - 1, event.begin)).atZone(ZoneOffset.UTC).toLocalDate()

            // Keep it when today is one of its days
            if (dayOf(event, zone) <= today && today <= lastDay) {
                rows.add(TodayItem(event.title, null, null, event.location, TODAY_EVENT, event.color, event))
            }
        } else if (event.begin < dayEnd && event.end > dayStart) {  // a timed event with any of it today
            rows.add(TodayItem(event.title, maxOf(event.begin, dayStart), event.end, event.location, TODAY_EVENT,
                event.color, event))
        }
    }

    // Each follow-up that belongs on today
    for (followUp in followUps) {
        // Reminders by when they go off; assignments and bills by their due day
        when {
            followUp.kind == KIND_REMINDER && followUp.nextNudge in dayStart until dayEnd ->
                rows.add(TodayItem(followUp.title, followUp.nextNudge, null,
                    repeatLabel(followUp.repeat).ifBlank { "Reminder" }, TODAY_REMINDER, followUp = followUp))
            followUp.kind == KIND_ASSIGNMENT && followUp.date == today.toString() -> {
                val due = runCatching { LocalTime.parse(followUp.time) }.getOrNull()
                val at = due?.let { today.atTime(it).atZone(zone).toInstant().toEpochMilli() }
                val from = if (followUp.sender.isBlank()) "" else " · from ${followUp.sender}"  // only when known
                rows.add(TodayItem(followUp.title, at, null, "Due$from", TODAY_ASSIGNMENT, followUp = followUp))
            }
            followUp.kind == KIND_DUE && followUp.date == today.toString() -> {
                val amount = if (followUp.amount.isBlank()) "" else "${followUp.amount} · "  // only when known
                rows.add(TodayItem(followUp.title, null, null, "${amount}due today", TODAY_BILL, followUp = followUp))
            }
        }
    }
    val sorted = rows.sortedWith(compareBy({ it.at != null }, { it.at ?: 0 }, { it.title }))
    val timedEvents = sorted.filter { it.kind == TODAY_EVENT && it.at != null }
    val happening = timedEvents.filter { it.at!! <= nowMillis && nowMillis < it.end!! }.minByOrNull { it.end!! }
    val lit = happening ?: timedEvents.firstOrNull { it.at!! > nowMillis }
    return TodayList(sorted, lit)
}

// How long until a time, like "in 25 min", "in 2 h" or "in 2 h 10 min" (rounded up to the minute)
fun startsIn(atMillis: Long, nowMillis: Long): String {
    val minutes = maxOf((atMillis - nowMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS, 1)

    // Under an hour reads in minutes only
    if (minutes < 60) {
        return "in $minutes min"
    }
    val rest = minutes % 60
    return if (rest == 0L) "in ${minutes / 60} h" else "in ${minutes / 60} h $rest min"
}

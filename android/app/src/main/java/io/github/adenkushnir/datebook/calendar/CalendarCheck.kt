// Compares a suggested plan with what's already on your calendar: clashes (overlapping timed events) and duplicates
// (the same plan already added)
package io.github.adenkushnir.datebook.calendar
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.parseLocal
import io.github.adenkushnir.datebook.pipeline.zoneOf
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val SMALL_WORDS = setOf("the", "and", "with", "for", "our", "at", "in", "on", "to", "of", "a", "an",
    "birthday", "bday", "anniversary")

// Return the timed calendar events that overlap a plan; all-day plans and all-day events never clash
fun clashesFor(event: Event, existing: List<CalendarEvent>): List<CalendarEvent> {
    // All-day plans don't clash with anything
    if (event.allDay) {
        return emptyList()
    }
    val (begin, end) = eventTimes(event)
    return existing.filter { !it.allDay && it.begin < end && begin < it.end }
}

// Return true if the plan already looks to be on the calendar: the same start (or the same day for all-day plans)
// and a similar title
fun alreadyOnCalendar(event: Event, existing: List<CalendarEvent>): Boolean {
    // All-day plans match all-day events on the same date (all-day events are stored at midnight UTC)
    if (event.allDay) {
        val day = parseLocal(event.start).toLocalDate()
        return existing.any {
            it.allDay && Instant.ofEpochMilli(it.begin).atZone(ZoneOffset.UTC).toLocalDate() == day &&
                similarTitles(it.title, event.title)
        }
    }
    val (begin, _) = eventTimes(event)
    return existing.any { !it.allDay && it.begin == begin && similarTitles(it.title, event.title) }
}

// Return true if two titles share a real word, like "Soccer scrimmage" and "Scrimmage at the park"
fun similarTitles(first: String, second: String): Boolean {
    return titleWords(first).intersect(titleWords(second)).isNotEmpty()
}

// The words of a title that say what it is: lowercase, without "'s", small words like "the", or words like "birthday"
// that many titles share (so "Mom's birthday" and "Sam's birthday" don't match)
private fun titleWords(title: String): Set<String> {
    val words = title.lowercase().replace("'s", "").split(Regex("[^a-z0-9]+"))
    return words.filter { it.length >= 3 && it !in SMALL_WORDS }.toSet()
}

// The clash warning for a prompt, like "You already have Practice 6:00 PM–8:00 PM" (empty when nothing clashes)
fun clashText(clashes: List<CalendarEvent>, phoneZone: String): String {
    val first = clashes.firstOrNull() ?: return ""  // nothing clashes
    val zone = zoneOf(phoneZone) ?: ZoneId.systemDefault()
    val begin = Instant.ofEpochMilli(first.begin).atZone(zone).format(CLOCK)
    val end = Instant.ofEpochMilli(first.end).atZone(zone).format(CLOCK)
    val more = if (clashes.size > 1) " and ${clashes.size - 1} more" else ""  // only when several clash
    return "You already have ${first.title.ifBlank { "an event" }} $begin–$end$more"
}

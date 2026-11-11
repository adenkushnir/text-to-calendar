// Turns an event into calendar times, repeat words and a one-line summary, in the right time zones (port of events.py)
package io.github.adenkushnir.datebook.pipeline
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
private val REPEAT_RULE = Regex("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,+-]+)*")
private val REPEAT_UNITS = mapOf("DAILY" to "day", "WEEKLY" to "week", "MONTHLY" to "month", "YEARLY" to "year")
private const val HOUR_MILLIS = 60L * 60 * 1000
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val ZONE_FORMAT = DateTimeFormatter.ofPattern("z", Locale.US)

// Return the zone for an IANA name like America/Los_Angeles, or null (meaning the phone's own zone) if empty or unknown
fun zoneOf(name: String?): ZoneId? {
    // Treat a missing or made-up zone name as unknown
    try {
        return if (name.isNullOrBlank()) null else ZoneId.of(name)
    } catch (error: DateTimeException) {  // not a real zone name
        return null
    }
}

// Return the zone the event's times are written in: the zone named in the text, else the phone's zone at the time
fun eventZone(event: Event): ZoneId? {
    // All-day events are plain dates on the phone's calendar
    if (event.allDay) {
        return zoneOf(event.phoneTimezone)
    }
    return zoneOf(event.timezone) ?: zoneOf(event.phoneTimezone)
}

// Read a YYYY-MM-DD or YYYY-MM-DDTHH:MM time, with dates on their own meaning midnight
fun parseLocal(text: String): LocalDateTime {
    // A plain date has no time part
    if (text.length == 10) {
        return LocalDate.parse(text).atStartOfDay()
    }
    return LocalDateTime.parse(text)
}

// Convert a time written in the given zone (null means the device's zone) to milliseconds since 1970
fun toMillis(text: String, zone: ZoneId?): Long {
    return parseLocal(text).atZone(zone ?: ZoneId.systemDefault()).toInstant().toEpochMilli()
}

// Return the start and end in milliseconds, using one hour (or one day) when there is no usable end
fun eventTimes(event: Event): Pair<Long, Long> {
    val zone = eventZone(event)
    val begin = toMillis(event.start, zone)

    // Use the given end when it comes after the start
    if (event.end.isNotBlank()) {
        val end = toMillis(event.end, zone)
        if (end > begin) {  // ignore ends before the start
            return Pair(begin, end)
        }
    }
    val length = if (event.allDay) DAY_MILLIS else HOUR_MILLIS  // default length
    return Pair(begin, begin + length)
}

// Return the event's repeat rule if it is a valid one, like FREQ=WEEKLY;BYDAY=MO, or an empty string
fun repeatRule(event: Event): String {
    val rule = event.repeat.trim().uppercase().removePrefix("RRULE:")
    return if (REPEAT_RULE.matches(rule)) rule else ""  // drop anything that doesn't look like a rule
}

// Describe a repeat rule in words, like "repeats weekly" or "repeats every 2 weeks"
fun repeatLabel(rule: String): String {
    val parts = rule.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }  // each NAME=VALUE pair
    val unit = REPEAT_UNITS.getValue(parts.getValue("FREQ"))
    val interval = parts["INTERVAL"] ?: "1"

    // Say "every N weeks" when it skips some
    if (interval != "1") {
        return "repeats every $interval ${unit}s"
    }
    return if (unit == "day") "repeats daily" else "repeats ${unit}ly"  // daily, weekly, monthly, yearly
}

// Return true if a one-off event has already started (an all-day event counts from midnight, so today still counts)
fun hasStarted(event: Event, nowMillis: Long): Boolean {
    // Repeating events still have future dates, so they are never too late
    if (repeatRule(event).isNotEmpty()) {
        return false
    }

    // Compare the start with now, treating a start that can't be read as not started
    try {
        val begin = toMillis(event.start, eventZone(event))
        return if (event.allDay) begin + DAY_MILLIS <= nowMillis else begin <= nowMillis  // all-day lasts the day
    } catch (error: DateTimeException) {  // unreadable start
        return false
    }
}

// Describe the event in one line in the phone's time, like: Call · Tue Nov 10, 6:00 PM (3:00 PM PST) · repeats weekly
fun summary(event: Event): String {
    val start = parseLocal(event.start)
    var whenText = start.format(DAY_FORMAT)

    // Add the time unless it is an all-day event, converted to the phone's time
    if (!event.allDay) {
        val written = start.atZone(eventZone(event) ?: ZoneId.systemDefault())  // the time as the text gave it
        val local = written.withZoneSameInstant(zoneOf(event.phoneTimezone) ?: ZoneId.systemDefault())
        whenText = "${local.format(DAY_FORMAT)}, ${local.format(CLOCK_FORMAT)}"

        // Show the original time too when the text used a different zone
        if (written.offset != local.offset) {
            whenText += " (${written.format(CLOCK_FORMAT)} ${written.format(ZONE_FORMAT)})"
        }
    }
    val rule = repeatRule(event)
    val repeatText = if (rule.isNotEmpty()) repeatLabel(rule) else ""  // only for repeating events
    val parts = listOf(event.title.ifBlank { "Event" }, whenText, event.location, repeatText)
    return parts.filter { it.isNotBlank() }.joinToString(" · ")  // skip empty parts
}

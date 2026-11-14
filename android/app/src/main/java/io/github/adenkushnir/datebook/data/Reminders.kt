// Reminders and notes: when a reminder goes off (its time, 9 AM on its day, or 9 AM tomorrow when it has none), when
// a repeating one comes back (daily, weekly on chosen days, monthly or yearly, every so many), and how a repeat reads
package io.github.adenkushnir.datebook.data
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
const val KIND_REMINDER = "reminder"
const val KIND_NOTE = "note"
private const val MAX_STEPS = 1000  // how far a repeat is searched for its next time
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
    "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

// The parts of a repeat rule like FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH
private fun ruleParts(rule: String): Map<String, String> {
    return rule.split(";").mapNotNull { part ->
        val pieces = part.split("=")
        if (pieces.size == 2) pieces[0].trim().uppercase() to pieces[1].trim().uppercase() else null
    }.toMap()
}

// The weekdays a weekly rule names (empty when it names none)
private fun ruleDays(parts: Map<String, String>): Set<DayOfWeek> {
    return parts["BYDAY"].orEmpty().split(",").mapNotNull { DAYS[it.takeLast(2)] }.toSet()
}

// The first time a reminder goes off, in milliseconds: its day and time, 9 AM on its day when it has no time, 9 AM
// tomorrow when it has neither; a time already past moves on to its next repeat, or goes off a minute from now
fun firstReminder(date: String, time: String, repeat: String, createdAt: Long, zone: ZoneId): Long {
    val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(createdAt), zone)
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: now.toLocalDate().plusDays(1)
    val clock = runCatching { LocalTime.parse(time) }.getOrNull() ?: LocalTime.of(NUDGE_HOUR, 0)
    var at = day.atTime(clock)

    // A time already past: its next repeat, or right away
    if (!at.isAfter(now)) {
        at = nextRepeat(repeat, at, now) ?: now.plusMinutes(1)
    }
    return at.atZone(zone).toInstant().toEpochMilli()
}

// The next time a repeating reminder that went off at `last` comes back, after `after`; null when it doesn't repeat
// (or the rule can't be read)
fun nextRepeat(rule: String, last: LocalDateTime, after: LocalDateTime): LocalDateTime? {
    val parts = ruleParts(rule)
    val every = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
    val days = ruleDays(parts)
    var next = last

    // Step forward one repeat at a time until it's after the given time
    for (step in 1..MAX_STEPS) {
        next = when (parts["FREQ"]) {
            "DAILY" -> next.plusDays(every)
            "WEEKLY" -> if (days.isEmpty()) next.plusWeeks(every) else nextWeekday(next, days, every, last)
            "MONTHLY" -> next.plusMonths(every)
            "YEARLY" -> next.plusYears(every)
            else -> return null
        }

        // The first time after `after` is the next one
        if (next.isAfter(after)) {
            return next
        }
    }
    return null
}

// The next chosen weekday after a time, keeping the time of day; with an interval over 1, only in every so many weeks
// counted from the first time
private fun nextWeekday(from: LocalDateTime, days: Set<DayOfWeek>, every: Long, first: LocalDateTime): LocalDateTime {
    var next = from.plusDays(1)
    val firstWeek = first.toLocalDate().with(DayOfWeek.MONDAY)

    // Walk day by day to the next chosen weekday in a week that counts
    while (true) {
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(firstWeek, next.toLocalDate().with(DayOfWeek.MONDAY))

        // Stop at a chosen day in a counted week
        if (next.dayOfWeek in days && weeks % every == 0L) {
            return next
        }
        next = next.plusDays(1)
    }
}

// How a repeat reads: "Every day", "Every 3 days", "Every Monday", "Every Mon and Thu", "Every 2 weeks", "Every
// month", "Every year" ("" for no repeat)
fun repeatLabel(rule: String): String {
    val parts = ruleParts(rule)
    val every = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
    val days = ruleDays(parts).sorted()
    val names = days.map { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) }

    // Pick the words for each kind of repeat
    return when (parts["FREQ"]) {
        "DAILY" -> if (every == 1L) "Every day" else "Every $every days"
        "WEEKLY" -> when {
            days.size == 1 && every == 1L ->
                "Every ${days[0].getDisplayName(java.time.format.TextStyle.FULL, Locale.US)}"
            days.isNotEmpty() && every == 1L -> "Every ${names.dropLast(1).joinToString(", ")} and ${names.last()}"
            days.isNotEmpty() -> "Every $every weeks on ${names.joinToString(", ")}"
            every == 1L -> "Every week"
            else -> "Every $every weeks"
        }
        "MONTHLY" -> if (every == 1L) "Every month" else "Every $every months"
        "YEARLY" -> "Every year"
        else -> ""
    }
}

// When a reminder goes off next, in words: "Sun Nov 15, 9:00 AM" ("" when it's done)
fun reminderWhen(reminder: FollowUp, zone: ZoneId): String {
    // A reminder with no next time has gone off
    if (reminder.nextNudge <= 0) {
        return ""
    }
    return java.time.Instant.ofEpochMilli(reminder.nextNudge).atZone(zone).format(WHEN)
}

// A note's title: its first line, cut short
fun noteTitle(text: String): String {
    val first = text.trim().lineSequence().firstOrNull().orEmpty().trim()
    return if (first.length > 60) first.take(57).trimEnd() + "…" else first
}

// A time of day as "9:00 AM"
fun clockText(time: LocalTime): String {
    return time.format(CLOCK)
}

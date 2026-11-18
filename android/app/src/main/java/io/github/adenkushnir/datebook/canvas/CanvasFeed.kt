// Reads Canvas's calendar feed (an .ics file): each entry's id, title and course, and when it's due or happens; tells
// assignments (and quizzes and exams, which Canvas lists as assignments) from class events; and works out what
// changed since the last check: new assignments, moved or removed ones, and class events not offered yet
package io.github.adenkushnir.datebook.canvas
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
const val CANVAS_APP = "datebook.canvas"
const val CANVAS_PACKAGE = "com.instructure.candroid"
private val ASSIGNMENT_IDS = listOf("event-assignment-", "event-sub-assignment-")
private val COURSE = Regex("^(.*?)\\s*\\[([^\\]]+)]\\s*$")
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

// One entry in the feed: its id, its title without the course, the course code, its local day and time ("" for all
// day), when it starts and ends (milliseconds), its place and link, and whether it's work that's due
data class FeedItem(
    val uid: String,
    val title: String,
    val course: String,
    val date: LocalDate,
    val time: String,
    val start: Long,
    val end: Long,
    val location: String = "",
    val url: String = "",
    val isAssignment: Boolean = false,
)

// What a check changes: assignments to add, open ones whose day, time or title changed (old and new), open ones gone
// from Canvas, class events to offer, and waiting class events whose time changed
data class CanvasPlan(
    val newAssignments: List<FeedItem>,
    val changed: List<Pair<FollowUp, FeedItem>>,
    val removed: List<FollowUp>,
    val newClassEvents: List<FeedItem>,
    val movedClassEvents: List<Pair<Suggestion, FeedItem>>,
)

// The feed's lines with folded lines joined back up (a line starting with a space or tab continues the one above)
fun unfold(text: String): List<String> {
    val lines = mutableListOf<String>()

    // Join each continuation to the line before it
    for (raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
        // A continuation, or a new line
        if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
            lines[lines.size - 1] = lines.last() + raw.substring(1)
        } else {
            lines.add(raw)
        }
    }
    return lines
}

// A text value with the feed's escapes undone (\n, \, \; and \\)
fun unescape(value: String): String {
    return value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";")
        .replace("\\\\", "\\")
}

// A title and its course: "Problem Set 3 [PHYS-101]" gives ("Problem Set 3", "PHYS-101")
fun splitCourse(summary: String): Pair<String, String> {
    val match = COURSE.find(summary.trim()) ?: return Pair(summary.trim(), "")
    return Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
}

// A feed time as (milliseconds, local day, "HH:MM" or "" for all day): plain dates are all day, a trailing Z or
// TZID=UTC means UTC, another TZID that zone, and no zone at all the phone's; null when it can't be read
fun feedTime(params: String, value: String, zone: ZoneId): Triple<Long, LocalDate, String>? {
    val text = value.trim()

    // A plain date is an all-day entry, kept at midnight UTC like the phone's calendar keeps all-day events
    if (params.contains("VALUE=DATE", ignoreCase = true) && !params.contains("DATE-TIME", ignoreCase = true) ||
        text.length == 8) {
        val day = runCatching { LocalDate.parse(text, DATE) }.getOrNull() ?: return null
        return Triple(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day, "")
    }
    val local = runCatching { LocalDateTime.parse(text.removeSuffix("Z"), DATE_TIME) }.getOrNull() ?: return null
    val tzid = Regex("TZID=([^;:]+)", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)
    val written = when {
        text.endsWith("Z") -> ZoneOffset.UTC
        tzid != null -> runCatching { ZoneId.of(tzid) }.getOrDefault(ZoneOffset.UTC)
        else -> zone
    }
    val instant = local.atZone(written).toInstant()
    val mine = instant.atZone(zone)
    return Triple(instant.toEpochMilli(), mine.toLocalDate(), mine.toLocalTime().format(TIME))
}

// Return true if a feed id is an assignment (including quizzes, exams and discussion check-ins)
fun isAssignmentId(uid: String): Boolean {
    return ASSIGNMENT_IDS.any { uid.startsWith(it) }
}

// Every entry in a feed (entries without an id, a title or a start are skipped)
fun parseFeed(text: String, zone: ZoneId): List<FeedItem> {
    val items = mutableListOf<FeedItem>()
    var fields: MutableMap<String, Pair<String, String>>? = null

    // Collect each entry's fields between BEGIN:VEVENT and END:VEVENT
    for (line in unfold(text)) {
        // Start, end, or a field of the entry being read
        if (line.trim() == "BEGIN:VEVENT") {
            fields = mutableMapOf()
        } else if (line.trim() == "END:VEVENT") {
            fields?.let { itemOf(it, zone)?.let { item -> items.add(item) } }
            fields = null
        } else if (fields != null && line.contains(":")) {
            val head = line.substringBefore(":")
            val name = head.substringBefore(";").uppercase()
            val params = if (head.contains(";")) head.substringAfter(";") else ""
            fields[name] = Pair(params, line.substringAfter(":"))
        }
    }
    return items
}

// One entry from its fields (null when it's missing what it needs)
private fun itemOf(fields: Map<String, Pair<String, String>>, zone: ZoneId): FeedItem? {
    val uid = fields["UID"]?.second?.trim().orEmpty()
    val summary = unescape(fields["SUMMARY"]?.second.orEmpty())
    val (startParams, startValue) = fields["DTSTART"] ?: return null
    val start = feedTime(startParams, startValue, zone) ?: return null

    // An entry needs an id and a title
    if (uid.isBlank() || summary.isBlank()) {
        return null
    }
    val end = fields["DTEND"]?.let { feedTime(it.first, it.second, zone)?.first } ?: start.first
    val (title, course) = splitCourse(summary)
    return FeedItem(uid, title, course, start.second, start.third, start.first, maxOf(end, start.first),
        unescape(fields["LOCATION"]?.second.orEmpty()).trim(), fields["URL"]?.second?.trim().orEmpty(),
        isAssignmentId(uid))
}

// An assignment's title as Due soon shows it: "Problem Set 3 · PHYS-101"
fun assignmentTitle(item: FeedItem): String {
    return if (item.course.isBlank()) item.title else "${item.title} · ${item.course}"
}

// When an item is due: its time, or the end of its day when it has no time
fun dueMoment(date: LocalDate, time: String, zone: ZoneId): Long {
    val clock = runCatching { LocalTime.parse(time) }.getOrNull() ?: LocalTime.MAX
    return date.atTime(clock).atZone(zone).toInstant().toEpochMilli()
}

// A class event's start as Datebook keeps plan times ("YYYY-MM-DDTHH:MM", or the date for all day)
fun planStart(item: FeedItem): String {
    return if (item.time.isBlank()) item.date.toString() else "${item.date}T${item.time}"
}

// A class event's end in the same form ("" when it ends when it starts)
fun planEnd(item: FeedItem, zone: ZoneId): String {
    // All-day events and ones without a length have no end
    if (item.time.isBlank() || item.end <= item.start) {
        return ""
    }
    return java.time.Instant.ofEpochMilli(item.end).atZone(zone).toLocalDateTime().toString().take(16)
}

// Work out what a check changes, from the feed, the follow-ups and plans Canvas made before, and the time now
fun planCheck(feed: List<FeedItem>, assignments: List<FollowUp>, classPlans: List<Suggestion>, nowMillis: Long,
    zone: ZoneId): CanvasPlan {
    val bySource = assignments.filter { it.sourceId.isNotBlank() }.associateBy { it.sourceId }
    val feedIds = feed.map { it.uid }.toSet()
    val due = feed.filter { it.isAssignment }
    val newAssignments = due.filter { it.uid !in bySource && dueMoment(it.date, it.time, zone) > nowMillis }
    val changed = due.mapNotNull { item ->
        val old = bySource[item.uid]?.takeIf { it.status == FollowUp.OPEN } ?: return@mapNotNull null
        val moved = old.date != item.date.toString() || old.time != item.time
        if (moved || old.title != assignmentTitle(item)) Pair(old, item) else null
    }
    val removed = assignments.filter { it.status == FollowUp.OPEN && it.sourceId.isNotBlank() &&
        it.sourceId !in feedIds && runCatching { dueMoment(LocalDate.parse(it.date), it.time, zone) > nowMillis }
            .getOrDefault(false) }
    val known = classPlans.filter { it.sourceId.isNotBlank() }.associateBy { it.sourceId }
    val classes = feed.filter { !it.isAssignment && it.start > nowMillis }
    val moved = classes.mapNotNull { item ->
        val old = known[item.uid]?.takeIf { it.status == Suggestion.STATUS_WAITING } ?: return@mapNotNull null
        if (old.start != planStart(item)) Pair(old, item) else null
    }
    return CanvasPlan(newAssignments, changed, removed, classes.filter { it.uid !in known }, moved)
}

// Return true if a link looks like a Canvas calendar feed (webcal links are fine too)
fun looksLikeFeed(link: String): Boolean {
    val clean = link.trim().lowercase()
    return (clean.startsWith("https://") || clean.startsWith("webcal://") || clean.startsWith("http://")) &&
        clean.contains("/feeds/calendars/") && clean.contains(".ics")
}

// The link to download: webcal and http become https
fun feedUrl(link: String): String {
    val clean = link.trim()
    return when {
        clean.startsWith("webcal://", ignoreCase = true) -> "https://" + clean.substring(9)
        clean.startsWith("http://", ignoreCase = true) -> "https://" + clean.substring(7)
        else -> clean
    }
}

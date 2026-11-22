// Men's foil tournaments to follow and watch: what Claude's web search found, saved as JSON; which ones fall in the
// next 7 days for the tiles on Home, which one is live, the line for an empty week, and the searches' instructions
package io.github.adenkushnir.datebook.fencing
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
const val WINDOW_DAYS = 7L  // Home shows the next week
const val SEARCH_DAYS = 21L  // the Monday search looks 3 weeks ahead
const val FINISHED_DAYS = 2L  // a finished event stays on Home 2 days, for its results
private const val LIVE_HOURS = 8L  // a stream with no end given counts as live for 8 hours
private val MAPPER = ObjectMapper()
private val JSON_TAGS = Regex("<json>(.*?)</json>", RegexOption.DOT_MATCHES_ALL)
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// One tournament event: its name ("Junior Men's Foil"), the meet ("November NAC"), where, its first and last days,
// when it starts each day (HH:MM in your zone, "" when unknown), the stream's name and link, the results link, and
// from the event-day check: today's stream start and end (HH:MM) and its stage ("Table of 16"), and for USA events
// the tournament's id on FencingTimeLive, looked up with your account
data class Tournament(
    val name: String,
    val meet: String,
    val place: String,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val startTime: String = "",
    val streamName: String = "",
    val streamUrl: String = "",
    val resultsUrl: String = "",
    val todayDate: String = "",
    val todayStart: String = "",
    val todayEnd: String = "",
    val todayNote: String = "",
    val ftlId: String = "",
)

// Return true if a tournament is a USA Fencing national event (NACs, Junior Olympics, Summer Nationals, national
// championships); everything else is treated as an FIE event
fun isUsaEvent(tournament: Tournament): Boolean {
    val words = "${tournament.meet} ${tournament.name}".lowercase()
    return listOf("nac", "north american cup", "junior olympics", "summer nationals", "national championship",
        "usa fencing").any { words.contains(it) }
}

// Where Watch goes: the stream found ("" when none was found, and Watch is hidden)
fun watchLink(tournament: Tournament): String {
    return tournament.streamUrl
}

// The Watch button's words: "▶ FencingTV" or "▶ YouTube", by the stream's site
fun watchLabel(tournament: Tournament): String {
    return if (tournament.streamUrl.contains("youtu")) "▶ YouTube" else "▶ FencingTV"
}

// Where Results goes: for USA events the tournament's schedule on FencingTimeLive (found with your account), else
// FencingTimeLive's front page; for FIE events the competition's page on fie.org, else the FIE's competitions page
fun resultsLink(tournament: Tournament): String {
    // A USA event's schedule, when it was looked up
    if (tournament.ftlId.isNotBlank()) {
        return "$FTL_SITE/tournaments/eventSchedule/${tournament.ftlId}"
    }

    // The FIE's page, when it was found
    if (tournament.resultsUrl.isNotBlank()) {
        return tournament.resultsUrl
    }
    return if (isUsaEvent(tournament)) "$FTL_SITE/" else "https://fie.org/competitions"
}

// A stream link only when it's a page on FencingTV or YouTube, not just the site's front page (anything else is
// dropped)
fun streamLinkOf(value: String): String {
    val link = link(value)
    val uri = runCatching { java.net.URI(link) }.getOrNull() ?: return ""
    val host = uri.host.orEmpty().lowercase()
    val known = listOf("fencingtv.com", "youtube.com", "youtu.be").any { host == it || host.endsWith(".$it") }
    val page = uri.path.orEmpty().trim('/').isNotBlank() || !uri.query.isNullOrBlank()
    return if (known && page) link else ""
}

// A results link only when it's a competition page on the FIE's site (FencingTimeLive links from the web search can't
// be checked, so USA events get theirs from the look-up with your account instead)
fun resultsLinkOf(value: String): String {
    val link = link(value)
    val fie = link.contains("fie.org/competitions/", ignoreCase = true) || link.contains("fie.org/tournaments/")
    return if (fie) link else ""
}

// One tile on Home: the tournament, the day it shows (today when it's on, its first day when it's coming, its last
// day when it's over), whether it's live now, and whether it's finished (kept 2 days for its results)
data class FencingTile(val tournament: Tournament, val day: LocalDate, val live: Boolean,
    val finished: Boolean = false)

// A tournament's key, so a later search or the event-day check updates the same one
fun tournamentKey(tournament: Tournament): String {
    return "${tournament.name.trim().lowercase()}|${tournament.meet.trim().lowercase()}|${tournament.firstDay}"
}

// Tournaments as JSON, for saving
fun tournamentsToJson(list: List<Tournament>): String {
    return MAPPER.writeValueAsString(list.map {
        mapOf("name" to it.name, "meet" to it.meet, "place" to it.place, "first_day" to it.firstDay.toString(),
            "last_day" to it.lastDay.toString(), "start_time" to it.startTime, "stream_name" to it.streamName,
            "stream_url" to it.streamUrl, "results_url" to it.resultsUrl, "today_date" to it.todayDate,
            "today_start" to it.todayStart, "today_end" to it.todayEnd, "today_note" to it.todayNote,
            "ftl_id" to it.ftlId)
    })
}

// A text field of a JSON object, trimmed ("" when missing)
private fun text(node: JsonNode, name: String): String {
    return node.path(name).asText("").trim()
}

// A link only when it's a web link
private fun link(value: String): String {
    return if (value.startsWith("https://") || value.startsWith("http://")) value else ""
}

// A time only when it reads as HH:MM
private fun clockOf(value: String): String {
    return runCatching { LocalTime.parse(value).toString() }.getOrDefault("")
}

// Tournaments from JSON: saved ones, or the events list in Claude's answer (unreadable entries are skipped)
fun tournamentsFrom(node: JsonNode): List<Tournament> {
    return node.mapNotNull { item ->
        val first = runCatching { LocalDate.parse(text(item, "first_day")) }.getOrNull() ?: return@mapNotNull null
        val last = runCatching { LocalDate.parse(text(item, "last_day")) }.getOrNull()?.takeIf { !it.isBefore(first) }
            ?: first
        val name = text(item, "name").ifBlank { return@mapNotNull null }
        Tournament(name, text(item, "meet"), text(item, "place"), first, last, clockOf(text(item, "start_time")),
            text(item, "stream_name"), streamLinkOf(text(item, "stream_url")),
            resultsLinkOf(text(item, "results_url")),
            text(item, "today_date"), clockOf(text(item, "today_start")), clockOf(text(item, "today_end")),
            text(item, "today_note"), text(item, "ftl_id").filter { it.isLetterOrDigit() })
    }
}

// Saved tournaments from JSON (empty for an empty or unreadable string)
fun tournamentsFromJson(json: String): List<Tournament> {
    // Nothing saved yet
    if (json.isBlank()) {
        return emptyList()
    }
    return runCatching { tournamentsFrom(MAPPER.readTree(json)) }.getOrDefault(emptyList())
}

// The JSON object in Claude's answer: inside <json> tags, else the last {...} in it; null when there is none
fun answerJson(answer: String): JsonNode? {
    val tagged = JSON_TAGS.findAll(answer).lastOrNull()?.groupValues?.get(1)
    val open = answer.indexOf('{')
    val close = answer.lastIndexOf('}')
    val candidate = tagged ?: if (open >= 0 && close > open) answer.substring(open, close + 1) else return null
    return runCatching { MAPPER.readTree(candidate.trim()) }.getOrNull()?.takeIf { it.isObject }
}

// The category a tournament is in, from its name: junior, division 1a, division 2, division 1 or senior, and team or
// individual (so "Junior Men's Foil" and "Junior Men's Individual Foil" are the same event)
fun categoryOf(tournament: Tournament): String {
    val name = tournament.name.lowercase()

    // The age or level group
    val group = when {
        name.contains("junior") || name.contains("u20") -> "junior"
        name.contains("1a") || name.contains("ia ") || name.endsWith(" ia") -> "div1a"
        Regex("\\b(ii|2)\\b").containsMatchIn(name) -> "div2"
        else -> "senior"
    }
    return if (name.contains("team")) "$group team" else group
}

// Return true if two found tournaments are the same event: same first day and category, and the same place or meet
// (by their first word, since searches name them slightly differently)
fun sameEvent(one: Tournament, two: Tournament): Boolean {
    val first = { text: String -> text.lowercase().split(" ", ",").firstOrNull { it.isNotBlank() }.orEmpty() }
    val samePlace = first(one.place).isNotBlank() && first(one.place) == first(two.place)
    val sameMeet = first(one.meet).isNotBlank() && first(one.meet) == first(two.meet)
    return one.firstDay == two.firstDay && categoryOf(one) == categoryOf(two) && (samePlace || sameMeet)
}

// Merge a new search into what was saved: searches add up, since each finds a slightly different set; an event found
// again is updated (keeping today's details and any links the new search missed), the rest are kept until they're
// over
fun mergeSearch(saved: List<Tournament>, found: List<Tournament>, today: LocalDate): List<Tournament> {
    val updated = found.map { new ->
        val before = saved.firstOrNull { sameEvent(it, new) } ?: return@map new
        val keepToday = before.todayDate == today.toString()
        new.copy(startTime = new.startTime.ifBlank { before.startTime }, ftlId = new.ftlId.ifBlank { before.ftlId },
            streamName = new.streamName.ifBlank { before.streamName },
            streamUrl = new.streamUrl.ifBlank { before.streamUrl },
            resultsUrl = new.resultsUrl.ifBlank { before.resultsUrl },
            todayDate = if (keepToday) before.todayDate else new.todayDate,
            todayStart = if (keepToday) before.todayStart else new.todayStart,
            todayEnd = if (keepToday) before.todayEnd else new.todayEnd,
            todayNote = if (keepToday) before.todayNote else new.todayNote)
    }
    val kept = saved.filter { old -> updated.none { sameEvent(it, old) } }
    val merged = mutableListOf<Tournament>()

    // Keep one of each event (earlier searches may have saved the same event twice)
    for (event in updated + kept) {
        // Skip an event already kept
        if (merged.none { sameEvent(it, event) }) {
            merged.add(event)
        }
    }
    return merged.filter { !it.lastDay.isBefore(today.minusDays(FINISHED_DAYS)) }.sortedBy { it.firstDay }
}

// Add what the event-day check found about today (matched by name and meet) to the saved tournaments
fun mergeToday(saved: List<Tournament>, found: List<Tournament>, today: LocalDate): List<Tournament> {
    return saved.map { tournament ->
        val match = found.firstOrNull { it.name.equals(tournament.name, ignoreCase = true) &&
            it.meet.equals(tournament.meet, ignoreCase = true) }
        if (match == null) tournament else tournament.copy(
            streamUrl = match.streamUrl.ifBlank { tournament.streamUrl },
            streamName = match.streamName.ifBlank { tournament.streamName },
            resultsUrl = match.resultsUrl.ifBlank { tournament.resultsUrl },
            todayDate = today.toString(), todayStart = match.todayStart, todayEnd = match.todayEnd,
            todayNote = match.todayNote)
    }
}

// Return true if a tournament is on a day
fun isOn(tournament: Tournament, day: LocalDate): Boolean {
    return !day.isBefore(tournament.firstDay) && !day.isAfter(tournament.lastDay)
}

// When today's stream starts and ends, in milliseconds: from the event-day check, else the usual start time (and 8
// hours later); null when neither is known or the tournament isn't on today
fun streamWindow(tournament: Tournament, today: LocalDate, zone: ZoneId): Pair<Long, Long>? {
    // Only on the tournament's days
    if (!isOn(tournament, today)) {
        return null
    }
    val checked = tournament.todayDate == today.toString()
    val start = runCatching { LocalTime.parse(if (checked) tournament.todayStart.ifBlank { tournament.startTime }
        else tournament.startTime) }.getOrNull() ?: return null
    val begin = today.atTime(start).atZone(zone).toInstant().toEpochMilli()
    val endTime = if (checked) runCatching { LocalTime.parse(tournament.todayEnd) }.getOrNull() else null
    val end = endTime?.let { today.atTime(it).atZone(zone).toInstant().toEpochMilli() }?.takeIf { it > begin }
        ?: (begin + LIVE_HOURS * 60 * 60 * 1000)
    return Pair(begin, end)
}

// The tiles for Home: tournaments on in the next 7 days, in order, each on today (when it's on) or its first day,
// with the live one marked
fun fencingTiles(tournaments: List<Tournament>, nowMillis: Long, zone: ZoneId): List<FencingTile> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val last = today.plusDays(WINDOW_DAYS - 1)
    val shown = tournaments.filter { !it.lastDay.isBefore(today.minusDays(FINISHED_DAYS)) &&
        !it.firstDay.isAfter(last) }
    return shown.map { tournament ->
        val window = streamWindow(tournament, today, zone)
        val live = window != null && nowMillis >= window.first && nowMillis < window.second
        val finished = tournament.lastDay.isBefore(today)
        val day = when {
            finished -> tournament.lastDay
            isOn(tournament, today) -> today
            else -> tournament.firstDay
        }
        FencingTile(tournament, day, live, finished)
    }.sortedWith(compareBy({ !it.live }, { it.finished }, { it.day }))
}

// The line for a week with nothing on: "No men's foil events this week. Next: Junior World Cup, Bochum · Sat Dec 5"
fun emptyWeekLine(tournaments: List<Tournament>, today: LocalDate): String {
    val next = tournaments.filter { it.firstDay.isAfter(today.plusDays(WINDOW_DAYS - 1)) }.minByOrNull { it.firstDay }
    val name = next?.let { listOf(it.meet.ifBlank { it.name }, it.place).filter { part -> part.isNotBlank() }
        .joinToString(", ") }
    return "No men's foil events this week." + if (next == null) "" else " Next: $name · ${next.firstDay.format(DAY)}."
}

// A tile's day in big letters ("Sat") and its days ("Fri–Sat", or one day)
fun tileDays(tile: FencingTile): Pair<String, String> {
    val first = tile.tournament.firstDay.format(DAY_NAME)
    val last = tile.tournament.lastDay.format(DAY_NAME)
    return Pair(tile.day.format(DAY_NAME), if (tile.tournament.lastDay == tile.tournament.firstDay) first
        else "$first–$last")
}

// A tile's time line: today's stage when live, else when it starts ("Starts 8:00 AM"; "" when unknown)
fun tileTime(tile: FencingTile): String {
    val tournament = tile.tournament

    // Live tiles say what's on
    if (tile.live) {
        return tournament.todayNote.ifBlank { "Streaming now" }
    }
    val start = runCatching { LocalTime.parse(tournament.todayStart.ifBlank { tournament.startTime }) }.getOrNull()
    return if (start == null) "" else "Starts ${start.format(CLOCK)}"
}

// The Monday search's instructions: what to find, for which days, in which time zone, and the answer's form
fun searchPrompt(today: LocalDate, zone: ZoneId): String {
    val until = today.plusDays(SEARCH_DAYS)
    return """Find every men's foil fencing competition from $today to $until at national level in the USA (USA
Fencing North American Cups, Junior Olympics, Summer Nationals, national championships) or international level (FIE
Junior World Cups, Senior World Cups and Grand Prix, zonal and world championships, the Olympics).
Only these categories: Junior (U20), Division I or Senior, Division IA, Division II. Skip cadet, youth, veteran,
women's events and other weapons. Each category at a meet is its own entry.
For each, find when men's foil starts on its days, converted to the time zone ${zone.id}.
stream_url: where that event is streamed, on FencingTV (check the schedule at https://www.fencingtv.com) or YouTube
(official channels of the FIE, USA Fencing, national federations or the organizer, or a live stream for the event).
Only a link on fencingtv.com or youtube.com; leave it empty when there's no stream.
results_url: the tournament's schedule page on FencingTimeLive, which lists all its events, like
https://www.fencingtimelive.com/tournaments/eventSchedule/<id> (open fencingtimelive.com's tournament list with web
fetch to find it), or for FIE events the competition's page on fie.org. Not a home page.
Never guess dates or links: leave a field empty when you can't find it.
Answer with only this JSON inside <json></json> tags:
{"events": [{"name": "Junior Men's Foil", "meet": "November NAC", "place": "Columbus, OH", "first_day": "YYYY-MM-DD",
"last_day": "YYYY-MM-DD", "start_time": "HH:MM", "stream_name": "", "stream_url": "", "results_url": ""}]}"""
}

// The event-day check's instructions: today's schedule and stream for the tournaments on today
fun todayPrompt(today: LocalDate, zone: ZoneId, onToday: List<Tournament>): String {
    val list = onToday.joinToString("\n") { "- ${it.name} · ${it.meet} · ${it.place}" }
    return """Today is $today. For each of these men's foil fencing events happening today:
$list
find today's live stream (its name and link), when today's stream starts and ends converted to the time zone
${zone.id}, the tournament's schedule page on FencingTimeLive (https://www.fencingtimelive.com/tournaments/eventSchedule/
<id>) or its competition page on fie.org, and what stage is on today (like "Table of 16" or "Finals").
Look for streams on FencingTV's schedule at https://www.fencingtv.com and on YouTube (the FIE's, USA Fencing's, the
national federation's or the organizer's channel). Then open the stream link with web fetch and keep it only if it is
today's men's foil stream (live now or scheduled for today), not an old video or a different weapon. Only links on
fencingtv.com or youtube.com. Never guess: leave a field empty when you can't find it.
Answer with only this JSON inside <json></json> tags, one entry per event above, with its name and meet as given:
{"events": [{"name": "", "meet": "", "first_day": "$today", "last_day": "$today", "stream_name": "", "stream_url": "",
"results_url": "", "today_start": "HH:MM", "today_end": "HH:MM", "today_note": ""}]}"""
}
